package com.classroom.translator.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Generic OpenAI-compatible API client.
 * Works with MIMO, DeepSeek, Qwen, or any OpenAI-compatible endpoint.
 */
class OpenAiApi {
    private fun stripThinking(text: String): String {
        var result = text
        result = result.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
        result = result.replace(Regex("<answer>(.*?)</answer>", RegexOption.DOT_MATCHES_ALL)) { it.groupValues[1] }
        result = result.replace(Regex("<think>.*", RegexOption.DOT_MATCHES_ALL), "")
        return result.trim()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Send audio for ASR transcription.
     * @param baseUrl e.g. "https://token-plan-cn.xiaomimimo.com/v1"
     * @param apiKey API key
     * @param model e.g. "mimo-v2.5" or "mimo-v2.5-asr"
     * @param audioBase64 Base64-encoded WAV/MP3 audio
     * @param format "wav" or "mp3"
     * @return Transcribed text
     */
    suspend fun transcribeAudio(
        baseUrl: String,
        apiKey: String,
        model: String,
        audioBase64: String,
        format: String = "wav",
        language: String = "en"
    ): String = withContext(Dispatchers.IO) {
        val contentArray = if (model.contains("asr")) {
            // mimo-v2.5-asr: no text prompt allowed
            JSONArray().put(JSONObject().apply {
                put("type", "input_audio")
                put("input_audio", JSONObject().apply {
                    put("data", "data:audio/$format;base64,$audioBase64")
                    put("format", format)
                })
            })
        } else {
            // mimo-v2.5 / mimo-v2-omni: with text prompt
            JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", "请将这段音频的内容完整逐字转录成文字。只输出转录结果，不要添加任何说明。")
                })
                put(JSONObject().apply {
                    put("type", "input_audio")
                    put("input_audio", JSONObject().apply {
                        put("data", "data:audio/$format;base64,$audioBase64")
                        put("format", format)
                    })
                })
            }
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", contentArray)
            }))
            put("max_tokens", 4000)
            put("asr_options", JSONObject().apply {
                put("language", language)
            })
        }

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty response")

        if (!response.isSuccessful) {
            throw Exception("ASR API error ${response.code}: $body")
        }

        val json = JSONObject(body)
        json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .let { stripThinking(it) }
    }

    /**
     * Translate text using chat completions API.
     */
    suspend fun translate(
        baseUrl: String,
        apiKey: String,
        model: String,
        text: String,
        sourceLang: String = "English",
        targetLang: String = "Chinese"
    ): String = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are a professional interpreter. Translate the following $sourceLang text to $targetLang. ONLY output the $targetLang translation, NO thinking, NO explanation, NO preface. Output must be concise.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", text)
                })
            })
            // max_tokens 800: 句子翻译不会超过这个,即使模型想 think 也装不下
            put("max_tokens", 800)
            // Qwen3.5+: chat_template_kwargs.enable_thinking=false 在新版 llama.cpp 有效
            // Qwen3-1.7B 及更早: 上面 user content 末尾加 /no_think 才有效
            // (9/22 真机测试 Qwen3.5-2B 在旧 llama-server 上仍卡,所以默认翻译模型切回 Qwen3-1.7B)
            if (model.contains("qwen3.5", ignoreCase = true) || model.contains("qwen2", ignoreCase = true)) {
                put("chat_template_kwargs", JSONObject().apply {
                    put("enable_thinking", false)
                })
            } else if (model.contains("qwen", ignoreCase = true)) {
                // Qwen3-1.7B 等: 追加 /no_think 到 user 内容
                val msgs = getJSONArray("messages")
                val userMsg = msgs.getJSONObject(msgs.length() - 1)
                userMsg.put("content", userMsg.getString("content") + "\n/no_think")
            }
        }

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty response")

        if (!response.isSuccessful) {
            throw Exception("Translate API error ${response.code}: $body")
        }

        val json = JSONObject(body)
        json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .let { stripThinking(it) }
    }


    /** Anthropic 协议端点(如 MiniMax /anthropic) 与 OpenAI 兼容端点(火山方舟 等) 自适应 */
    private fun isAnthropicProtocol(baseUrl: String) = baseUrl.contains("anthropic")

    private fun cloudTextFromResponse(body: String, anthropic: Boolean): String {
        val json = JSONObject(body)
        return if (anthropic) {
            val contentArr = json.getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until contentArr.length()) {
                val c = contentArr.getJSONObject(i)
                if (c.optString("type") == "text") sb.append(c.getString("text"))
            }
            sb.toString()
        } else {
            json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    }

    /** 云端对话结果：文本 + finish_reason（length=被截断，诊断"非 JSON"输出用） */
    data class CloudChatResult(val text: String, val finishReason: String, val rawLen: Int)

    /**
     * 云端单轮对话（协议自适应）—— 课堂总结生成用。
     * 默认云端 = 火山方舟 Agent Plan (OpenAI 兼容: POST {baseUrl}/chat/completions);
     * baseUrl 含 "anthropic" 时走 Anthropic Messages 协议 (MiniMax 等)。
     */
    suspend fun cloudChat(
        baseUrl: String,
        apiKey: String,
        model: String,
        system: String,
        user: String,
        maxTokens: Int = 3000,
        temperature: Double = 0.3,
        reasoningEffort: String? = null
    ): String = cloudChatFull(baseUrl, apiKey, model, system, user, maxTokens, temperature, reasoningEffort).text

    /**
     * 同 cloudChat，但返回 finish_reason 等诊断信息。
     * 2026-09-29 加固：① content 为空时回退 reasoning_content（有些网关把答案放进思考字段）；
     * ② finish_reason 记入日志（"length" = max_tokens 截断，是"模型输出非 JSON"的高发原因）。
     */
    suspend fun cloudChatFull(
        baseUrl: String,
        apiKey: String,
        model: String,
        system: String,
        user: String,
        maxTokens: Int = 3000,
        temperature: Double = 0.3,
        reasoningEffort: String? = null
    ): CloudChatResult = withContext(Dispatchers.IO) {
        val anthropic = isAnthropicProtocol(baseUrl)
        val url = if (anthropic) "$baseUrl/v1/messages" else "$baseUrl/chat/completions"
        val payload = if (anthropic) {
            JSONObject().apply {
                put("model", model)
                put("max_tokens", maxTokens)
                put("system", system)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "user"); put("content", user) })
                })
            }
        } else {
            JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", system) })
                    put(JSONObject().apply { put("role", "user"); put("content", user) })
                })
                put("max_tokens", maxTokens)
                put("temperature", temperature)
                // 思考深度（火山方舟官方字段，底层同名参数）：
                // deepseek-v4 家族支持 none/minimal/low/high/max；默认 high。
                // 2026-09-26 实测：none→思考量精确归零（0 tok）证明通道生效；max→思考量最高（592 tok vs 默认230）。
                if (!reasoningEffort.isNullOrBlank()) put("reasoning_effort", reasoningEffort)
            }
        }
        val builder = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0")
            .addHeader("Content-Type", "application/json")
        if (anthropic) {
            builder.addHeader("X-Api-Key", apiKey)  // Anthropic protocol uses X-Api-Key, not Bearer
            builder.addHeader("anthropic-version", "2023-06-01")
        } else {
            builder.addHeader("Authorization", "Bearer $apiKey")
        }
        val request = builder.post(payload.toString().toRequestBody("application/json".toMediaType())).build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty cloud response")
        if (!response.isSuccessful) throw Exception("Cloud API error ${response.code}: ${body.take(300)}")
        val json = JSONObject(body)
        // usage 记录（含 reasoning_tokens，验证思考深度是否生效；可在 logcat 查看）
        try {
            val u = json.optJSONObject("usage")
            if (u != null) {
                val rt = u.optJSONObject("completion_tokens_details")?.optInt("reasoning_tokens", -1) ?: -1
                Log.i("OpenAiApi", "cloudChat[$model] prompt=${u.optInt("prompt_tokens", -1)} completion=${u.optInt("completion_tokens", -1)} reasoning_tokens=$rt")
            }
        } catch (_: Exception) {}

        val finish: String
        val rawText: String
        if (anthropic) {
            finish = json.optString("stop_reason", "")
            val contentArr = json.optJSONArray("content")
            val sb = StringBuilder()
            if (contentArr != null) {
                for (i in 0 until contentArr.length()) {
                    val c = contentArr.optJSONObject(i) ?: continue
                    if (c.optString("type") == "text") sb.append(c.optString("text"))
                }
            }
            rawText = sb.toString()
        } else {
            val ch = json.optJSONArray("choices")?.optJSONObject(0)
                ?: throw Exception("Cloud API 无 choices: ${body.take(200)}")
            finish = ch.optString("finish_reason", "")
            val msg = ch.optJSONObject("message") ?: JSONObject()
            val content = msg.optString("content", "")
            // content 为空时回退 reasoning_content（有些网关/档位把答案写进思考字段）
            rawText = if (content.isNotBlank()) content else msg.optString("reasoning_content", "")
        }
        val cleaned = stripThinking(rawText)
        Log.i("OpenAiApi", "cloudChat[$model] finish=$finish textLen=${cleaned.length}")
        CloudChatResult(cleaned, finish, cleaned.length)
    }

    /**
     * 云端批量翻译 —— 严格 1:1（逐条独立翻译，不合并、不丢弃、不重排），协议自适应。
     *
     * 【2026-11-03 重大修正：只返回中文，不再回传英文】
     *
     * 用户反馈「识别结果没有显示完整」：卡片上英文和中文都停在半句话，
     * 例如 英文 "Okay, I will get back to you. Stand up. What is"
     *       中文 "好的，我会回复你。站起来。什么是"
     * 两边断在同一个位置 —— 因为原实现让云端**同时回传英文**，
     * 而云端模型会把自己的"复述版"英文写回去。复述时它会顺手压缩/截断，
     * 于是英文被云端截短，中文照着被截短的英文翻 → 中英文一起断在半句。
     *
     * 修法：云端**只负责中文**。英文原样保留 ASR 的识别结果（那才是老师实际说的话，
     * 是唯一的事实来源）。中文译文与输入段一一对应，按位置替换。
     *
     * @return 与 segments 等长、同序的中文译文列表；解析失败时该位置为 ""。
     */
    suspend fun cloudBatchTranslate(
        baseUrl: String,
        apiKey: String,
        model: String,
        segments: List<String>
    ): List<String> {
        if (segments.isEmpty()) return emptyList()
        val segmentsText = segments.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
        // 严格 1:1: 调用方按位置把第 i 个译文覆盖到第 i 条本地译文上, 合并/丢弃会错位
        // (2026-09-22 实测: 允许合并时 3 段只回 1 对, 只覆盖 1/3 —— 改为逐条直译)
        val prompt = "You are a professional interpreter. Receive exactly " + segments.size + " numbered ASR segments. " +
            "Translate EACH segment to Chinese independently. " +
            "Return ONLY a JSON array with EXACTLY " + segments.size + " Chinese strings, " +
            "same order and count as the input segments. Do NOT merge, do NOT drop, do NOT reorder. " +
            "Translate the FULL text of each segment, including incomplete sentences, do NOT summarize or shorten."
        val content = cloudChat(
            baseUrl = baseUrl, apiKey = apiKey, model = model,
            system = prompt, user = segmentsText,
            maxTokens = maxOf(1500, segments.size * 220), temperature = 0.1
        )
        return parseChineseList(content, segments.size)
    }

    /**
     * 解析云端返回的中文数组。
     *
     * 兼容三种格式（云端模型不听话是常态）：
     *   ["中文1","中文2"]                       —— 纯字符串数组
     *   [{"chinese":"中文1"},{"zh":"中文2"}]    —— 对象数组
     *   [["英文1","中文1"],["英文2","中文2"]]   —— 旧版的二元组格式
     *
     * ⚠️ 用 JSONTokener 验证"消费完整串"——org.json 的 JSONObject 构造器会静默忽略
     * 后面的垃圾，会把"示例对象"当成结果（课堂总结已踩过这个坑）。
     */
    private fun parseChineseList(text0: String, expected: Int): List<String> {
        // 剥思考块泄漏 + markdown 代码围栏
        val text = text0.replace(Regex("(?s).*?</think>"), "").replace(Regex("```(?:json)?"), "")
        return try {
            val jsonStr = text.let {
                val start = it.indexOf('[')
                val end = it.lastIndexOf(']')
                if (start >= 0 && end > start) it.substring(start, end + 1) else it
            }
            val tokener = org.json.JSONTokener(jsonStr)
            val arr = tokener.nextValue() as? org.json.JSONArray
            val results = mutableListOf<String>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val pair = arr.optJSONArray(i)
                    val obj = arr.optJSONObject(i)
                    val zh = when {
                        pair != null && pair.length() >= 2 -> pair.optString(1)
                        obj != null -> obj.optString("chinese").ifEmpty { obj.optString("zh") }
                        else -> arr.optString(i)
                    }
                    results.add(zh)
                }
            }
            // 数量对不上就补齐/截断 —— 宁可少译一条，也别把译文错位贴到别的句子上
            while (results.size < expected) results.add("")
            if (results.size > expected) results.subList(expected, results.size).clear()
            results
        } catch (e: Exception) {
            Log.e("OpenAiApi", "Chinese list parse error: " + e.message + " raw: " + text.take(200))
            List(expected) { "" }
        }
    }

}
