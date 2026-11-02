package com.classroom.translator.repair

import android.util.Log
import com.classroom.translator.api.OpenAiApi
import com.classroom.translator.data.AppSettings
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 自修复工具的 AI 诊断层。
 *
 * 定位：**确定性的问题由代码修，有歧义的问题由 LLM 解释和指路**。
 * 纯 LLM 修代码不可靠（它会把好的配置改坏），纯代码又解释不清"为什么坏"。
 * 所以分两层：
 *   1. [Diagnostics] 跑确定性体检，产出可执行修复动作 —— 这层自己就能修好绝大多数问题；
 *   2. 本类把体检结果交给云端 LLM，让它用自然语言解释问题、给优先级建议、
 *      并在体检没覆盖到的地方提出额外检查项。
 *
 * LLM 的输出**永远不会直接改配置** —— 它只产出建议清单，
 * 真正执行动作的是受控的修复器。这样即使模型胡说，也不会破坏 App 状态。
 */
class AiRepairAdvisor(private val api: OpenAiApi) {

    companion object {
        private const val TAG = "AiRepairAdvisor"
        private const val MAX_TOKENS = 3000
    }

    /** AI 给出的建议 */
    data class Advice(
        val summary: String,
        val severity: String,
        val suggestions: List<Suggestion>,
        val extraChecks: List<String>,
        val raw: String = "",
    ) {
        data class Suggestion(
            val title: String,
            val why: String,
            val action: String,
            val priority: Int,
        )

        val isEmpty: Boolean get() = suggestions.isEmpty()
    }

    /**
     * 让 LLM 对体检报告做解读。
     * @param report 体检结果
     * @param settings 当前设置（脱敏后给模型看）
     * @return 建议；调用失败时返回带错误说明的 Advice（不抛异常，界面要能显示）
     */
    suspend fun advise(report: Diagnostics.Report, settings: AppSettings): Advice {
        val sysPrompt = buildString {
            append("你是 Android 课堂翻译 App 的资深运维工程师。\n")
            append("用户报告了问题，程序已经跑完一次确定性体检，把结果给你。\n\n")
            append("你的任务：\n")
            append("1. 用中文解释这些体检项说明了什么状况（用户看得懂的话，不要术语堆砌）。\n")
            append("2. 按优先级给出修复建议，priority 数字越小越优先（1 = 最紧急）。\n")
            append("3. 指出体检没覆盖到的、值得再查的方面（extraChecks）。\n\n")
            append("只输出 JSON，不要任何解释性文字或代码围栏。格式：\n")
            append("""{"summary":"整体结论","severity":"info|warn|critical","""")
            append(""""suggestions":[{"title":"建议标题","why":"为什么","action":"具体怎么做","priority":1}],"""")
            append(""""extraChecks":["还可以检查什么"]}""")
            append("\n\n重要：不要建议用户做会丢失课堂记录的操作；不要编造不存在的设置项名称。")
        }

        val userPrompt = buildString {
            append("【体检报告】健康分：${report.healthScore}/100\n")
            append(report.toPromptContext())
            append("\n\n【当前设置（已脱敏）】\n")
            append("识别: ${settings.asrModel} @ ${settings.asrBaseUrl}  模式=${settings.asrMode}\n")
            append("本地翻译: ${settings.translateModel} @ ${settings.translateBaseUrl}\n")
            append("云端: ${settings.cloudModel} @ ${settings.cloudBaseUrl}  密钥=${maskKey(settings.cloudApiKey)}\n")
            append("语种: ${settings.asrLanguage}\n")
        }

        return try {
            val res = api.cloudChatFull(
                baseUrl = settings.cloudBaseUrl,
                apiKey = settings.cloudApiKey,
                model = settings.cloudModel,
                system = sysPrompt,
                user = userPrompt,
                maxTokens = MAX_TOKENS,
                temperature = 0.2,
            )
            parseAdvice(res.text, res.finishReason)
        } catch (e: Exception) {
            Log.e(TAG, "advise failed: ${e.message}", e)
            Advice(
                summary = "AI 诊断不可用：${e.message}",
                severity = "warn",
                suggestions = emptyList(),
                extraChecks = emptyList(),
            )
        }
    }

    /** 密钥脱敏：只留前后 4 位 */
    private fun maskKey(key: String): String = when {
        key.isBlank() -> "(未配置)"
        key.length <= 8 -> "****"
        else -> "${key.take(4)}****${key.takeLast(4)}"
    }

    /**
     * 解析 LLM 输出。
     * 复用课堂总结那套加固思路（org.json 会忽略对象后面的垃圾 → 必须用 JSONTokener 验证消费完整）。
     */
    fun parseAdvice(raw: String, finishReason: String = ""): Advice {
        val text = raw
            .replace(Regex("(?s).*?</think>"), "")
            .replace(Regex("<think>.*"), "")
            .replace(Regex("```(?:json)?"), "")
            .trim()

        val obj = firstJsonObject(text)
        if (obj == null) {
            Log.e(TAG, "parseAdvice failed, finish=$finishReason, raw=${text.take(300)}")
            return Advice(
                summary = "AI 返回的内容无法解析（finish=$finishReason）",
                severity = "warn",
                suggestions = emptyList(),
                extraChecks = emptyList(),
                raw = text,
            )
        }

        val suggestions = mutableListOf<Advice.Suggestion>()
        val arr = obj.optJSONArray("suggestions") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val title = s.optString("title").trim()
            if (title.isBlank()) continue
            suggestions.add(
                Advice.Suggestion(
                    title = title,
                    why = s.optString("why").trim(),
                    action = s.optString("action").trim(),
                    priority = s.optInt("priority", 99).coerceIn(1, 99),
                )
            )
        }
        suggestions.sortBy { it.priority }

        val checks = mutableListOf<String>()
        val ec = obj.optJSONArray("extraChecks") ?: JSONArray()
        for (i in 0 until ec.length()) {
            val t = ec.optString(i).trim()
            if (t.isNotBlank()) checks.add(t)
        }

        return Advice(
            summary = obj.optString("summary").ifBlank { "AI 未给出结论" },
            severity = obj.optString("severity", "info").ifBlank { "info" },
            suggestions = suggestions,
            extraChecks = checks,
            raw = text,
        )
    }

    /**
     * 从可能混着废话的文本里抠出第一个完整 JSON 对象。
     * 用 JSONTokener 验证"消费完整串" —— org.json 的 JSONObject 构造器会静默忽略后面的垃圾，
     * 导致把「示例对象」当成结果（课堂总结踩过这个坑）。
     */
    private fun firstJsonObject(text: String): JSONObject? {
        val start = text.indexOf('{')
        if (start < 0) return null
        return try {
            val tokener = JSONTokener(text.substring(start))
            val value = tokener.nextValue()
            val rest = tokener.nextClean()
            if (rest.code != 0 || value !is JSONObject) null else value as JSONObject
        } catch (e: Exception) {
            try {
                JSONObject(text.substring(start))
            } catch (e2: Exception) {
                null
            }
        }
    }
}
