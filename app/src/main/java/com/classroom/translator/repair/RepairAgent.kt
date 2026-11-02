package com.classroom.translator.repair

import android.content.Context
import android.util.Log
import com.classroom.translator.data.SettingsRepository
import com.classroom.translator.data.AppSettings
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AI 自修复 Agent —— LLM 自己调用工具修好 App。
 *
 * 【和"给建议的 AI"的区别】
 * 旧版 [AiRepairAdvisor] 只输出文字建议，用户还得自己照着做。
 * 这里跑的是完整的 Agent 循环：
 *
 * ```
 *   ┌─→ 把体检结果 + 工具清单发给 LLM
 *   │        ↓
 *   │   LLM 返回 tool_calls（它决定调哪个工具、传什么参数）
 *   │        ↓
 *   │   真实执行工具（重启服务/改配置/下模型/迁移目录/探活端点）
 *   │        ↓
 *   │   把工具的真实结果作为 tool 消息回喂给 LLM
 *   │        ↓
 *   └───── LLM 据此决定继续调工具，还是宣告修好了
 * ```
 *
 * 终止条件：LLM 不再返回 tool_calls（说明它认为修完了）、
 * 或达到最大轮数、或体检显示已无严重问题。
 *
 * 安全约束：
 *  - 工具走白名单（[RepairToolbox.allowed]），AI 造不出别的动作；
 *  - 删除类动作（删模型 / 清课堂记录）**不在**工具清单里，只能用户手动勾选；
 *  - 每轮都体检，模型嘴上说"修好了"不算数 —— 以体检结果为准。
 */
class RepairAgent(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
) {

    companion object {
        private const val TAG = "RepairAgent"
        /** 最多让 LLM 调几轮工具 —— 防止死循环 */
        private const val MAX_ROUNDS = 8
        private const val MAX_TOKENS = 4000
    }

    /** 一轮的工具调用记录（给 UI 展示） */
    data class Round(
        val index: Int,
        val toolName: String,
        val arguments: String,
        val success: Boolean,
        val result: String,
        val modelSaid: String,
    )

    /** Agent 运行结果 */
    data class Result(
        val rounds: List<Round>,
        val finalReport: Diagnostics.Report?,
        val summary: String,
        val stoppedReason: String,
    ) {
        val toolCallCount: Int get() = rounds.size
        val succeeded: Boolean get() = finalReport?.hasFail != true
    }

    private val toolbox = RepairToolbox(context, settingsRepo)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 跑一次完整的自修复 Agent。
     *
     * @param onProgress 进度回调（给界面显示"AI 正在调用 xxx 工具…"）
     */
    suspend fun run(onProgress: (String) -> Unit = {}): Result {
        val settings = settingsRepo.settings.first()
        val diagnostics = Diagnostics(context)

        // 初始体检 —— 作为 Agent 的"现状"
        var report = diagnostics.runFullCheck(settings)
        onProgress("体检完成：健康分 ${report.healthScore}/100")

        val messages = JSONArray()
        messages.put(
            JSONObject().apply {
                put("role", "system")
                put("content", buildSystemPrompt())
            }
        )
        messages.put(
            JSONObject().apply {
                put("role", "user")
                put("content", "【当前体检结果】\n${report.toPromptContext()}\n\n" +
                    "请诊断问题并调用工具修复它。若一切正常，直接说明无需修复。")
            }
        )

        val rounds = mutableListOf<Round>()
        var stopped = "模型认为已修复"
        var summary = ""

        for (roundIdx in 1..MAX_ROUNDS) {
            val resp = chat(settings, messages, toolbox.toolSchemas())
            if (resp == null) {
                stopped = "云端模型调用失败"
                summary = "AI 无法连接云端模型，未执行任何修复。"
                break
            }

            val toolCalls = resp.optJSONArray("tool_calls")
            if (toolCalls == null || toolCalls.length() == 0) {
                // 没有工具调用 = AI 认为不需要再修了
                summary = stripThinking(resp.optString("content", "")).ifBlank { "AI 未给出说明" }
                stopped = "模型认为已修复"
                // 以体检为准，不信模型嘴上说修好了
                report = diagnostics.runFullCheck(settingsRepo.settings.first())
                break
            }

            // 【2026-11-02 修复】assistant 消息必须在工具循环**之前** push 一次。
            //
            // 原先写在循环体内：一轮返回 2 个工具 → 会 push 2 条一模一样的 assistant 消息，
            // 而 tool 消息按协议必须紧跟在其对应的 assistant 之后 —— 消息序列会乱，
            // 云端直接 400。
            // 正确序列：assistant(带全部 tool_calls) → tool(1) → tool(2) → 下一轮
            messages.put(
                JSONObject().apply {
                    put("role", "assistant")
                    put("content", resp.optString("content", ""))
                    put("tool_calls", toolCalls)
                }
            )

            // 执行这一轮的所有工具调用
            for (i in 0 until toolCalls.length()) {
                val tc = toolCalls.optJSONObject(i) ?: continue
                val fn = tc.optJSONObject("function") ?: continue
                val name = fn.optString("name", "")
                val argsStr = fn.optString("arguments", "{}")
                val args = try {
                    JSONObject(argsStr)
                } catch (t: Throwable) {
                    JSONObject()
                }

                onProgress("AI 调用工具：$name")
                Log.i(TAG, "round $roundIdx: tool=$name args=$argsStr")

                val res = toolbox.execute(name, args)
                rounds.add(
                    Round(
                        index = roundIdx,
                        toolName = name,
                        arguments = argsStr,
                        success = res.success,
                        result = res.message,
                        modelSaid = stripThinking(resp.optString("content", "")),
                    )
                )

                // 回喂工具结果（assistant 消息已在上方 push 过）
                messages.put(
                    JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", tc.optString("id", "call_$i"))
                        put("name", name)
                        put("content", res.toFeedBack())
                    }
                )
            }

            // 每轮后体检，看问题是否减少
            report = diagnostics.runFullCheck(settingsRepo.settings.first())
            onProgress("第 $roundIdx 轮完成，健康分 ${report.healthScore}/100")

            // 已经不严重了 —— 但让 AI 有一次收尾机会（它可能还想做点别的）
            if (!report.hasFail && roundIdx < MAX_ROUNDS) {
                // 再问一次，让它确认或继续
                messages.put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", "当前体检已无严重问题：\n${report.toPromptContext()}\n" +
                            "如果还有能改进的地方就继续调用工具，否则直接总结修复结果。")
                    }
                )
            }
        }

        if (rounds.size >= MAX_ROUNDS) stopped = "达到最大轮数 ${MAX_ROUNDS}，已停止"
        if (summary.isBlank()) {
            summary = buildString {
                append("共执行 ${rounds.size} 次工具调用")
                val ok = rounds.count { it.success }
                append("（成功 $ok 次）")
                append("。当前健康分 ${report.healthScore}/100")
                val fails = report.findings.filter { it.severity == Diagnostics.Severity.FAIL }
                if (fails.isEmpty()) {
                    append("，所有严重问题已消除。")
                } else {
                    append("。仍有问题：")
                    append(fails.joinToString("；") { it.title })
                }
            }
        }

        Log.i(TAG, "agent finished: rounds=${rounds.size} score=${report.healthScore} reason=$stopped")
        return Result(rounds, report, summary, stopped)
    }

    /**
     * 系统提示词 —— 教 AI 怎么用工具。
     * 关键点：让它**先诊断再动手**，且以工具的真实返回为准。
     */
    private fun buildSystemPrompt(): String = """
你是这个 Android 课堂翻译 App 的**自修复工程师**。你可以通过调用工具**真正修改 App 的状态**，而不只是给建议。

工作方式：
1. 先调用 diagnose_app 了解当前真实状态（不要凭猜测）。
2. 根据体检结果决定调用哪些工具。每个工具都会返回真实执行结果，你要读结果再决定下一步。
3. 工具执行失败时，读它的错误信息，换个思路再试（例如服务起不来可能是模型缺失，先 download_missing_models 再 restart_local_service）。
4. 反复调用工具直到体检结果里没有严重问题为止。
5. 全部修好后，用中文简短总结你做了什么。

重要约束：
- 只能使用提供的工具，不要臆造不存在的动作。
- 不要建议用户去手动改设置 —— 你有工具，直接动手。
- 不要谎报成功：判断修没修好以 diagnose_app / 工具的真实返回为准。
- 模型缺失时下载需要 1-2GB，会花几分钟，这是正常的 patiently 等。
- 最终回答用中文，简明扼要。
""".trimIndent()

    /**
     * 调云端模型（带 tools）。
     * @return message 对象（含 tool_calls / content），失败返回 null
     */
    private suspend fun chat(
        settings: AppSettings,
        messages: JSONArray,
        tools: List<JSONObject>,
    ): JSONObject? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("model", settings.cloudModel)
                put("messages", messages)
                put("tools", JSONArray(tools))
                put("tool_choice", "auto")
                put("max_tokens", MAX_TOKENS)
                put("temperature", 0.2)
            }
            val anthropic = settings.cloudBaseUrl.contains("anthropic")
            val url = if (anthropic) "${settings.cloudBaseUrl}/v1/messages" else "${settings.cloudBaseUrl}/chat/completions"
            val builder = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
            if (anthropic) {
                builder.addHeader("X-Api-Key", settings.cloudApiKey)
                builder.addHeader("anthropic-version", "2023-06-01")
            } else {
                builder.addHeader("Authorization", "Bearer ${settings.cloudApiKey}")
            }
            val request = builder
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: return@use null
                if (!resp.isSuccessful) {
                    Log.e(TAG, "chat HTTP ${resp.code}: ${body.take(300)}")
                    return@use null
                }
                val json = JSONObject(body)
                val ch = json.optJSONArray("choices")?.optJSONObject(0)
                    ?: return@use null
                val msg = ch.optJSONObject("message") ?: return@use null
                Log.i(TAG, "chat finish=${ch.optString("finish_reason")} toolCalls=${msg.optJSONArray("tool_calls")?.length() ?: 0}")
                msg
            }
        } catch (t: Throwable) {
            Log.e(TAG, "chat failed: ${t.message}", t)
            null
        }
    }

    private fun stripThinking(text: String): String = text
        .replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("<think>.*", RegexOption.DOT_MATCHES_ALL), "")
        .trim()
}
