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
        .readTimeout(120, TimeUnit.SECONDS)
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
                    put("content", "You are a professional interpreter. Translate the following $sourceLang text to $targetLang. Only output the $targetLang translation, nothing else. Keep it natural and concise.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", text)
                })
            })
            put("max_tokens", 2000)
            // Qwen3.x on llama.cpp: /no_think string suffix is IGNORED — the model
            // still spends all max_tokens on thinking and returns empty content.
            // Correct fix is chat_template_kwargs.enable_thinking=false, which
            // llama.cpp maps to <think>...</think> removal in the Jinja template.
            // Verified 2026-09-22 phone-side: 17s empty → 0.44-0.69s real translation.
            if (model.contains("qwen", ignoreCase = true)) {
                put("chat_template_kwargs", JSONObject().apply {
                    put("enable_thinking", false)
                })
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


    /**
     * Batch translate and merge: sends multiple ASR segments to AI for smart translation.
     * AI merges split sentences, discards hallucinations, and returns clean bilingual pairs.
     */


    suspend fun batchTranslateAndMerge(
        baseUrl: String,
        apiKey: String,
        model: String,
        segments: List<String>
    ): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val segmentsText = segments.mapIndexed { i, s -> "${i+1}. $s" }.joinToString("\n")
        val prompt = "You are a professional interpreter. Receive numbered ASR segments from an English lecture. " +
            "Tasks: 1) Translate to Chinese 2) Merge consecutive incomplete sentences 3) Discard hallucinated segments " +
            "(random words like 'yeah','okay','think' alone without context) 4) Return ONLY a JSON array of [english,chinese] pairs."

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", prompt) })
                put(JSONObject().apply { put("role", "user"); put("content", segmentsText) })
            })
            put("max_tokens", 4000)
            put("temperature", 0.1)
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
        if (!response.isSuccessful) throw Exception("Batch API error ${response.code}: $body")

        val json = JSONObject(body)
        val content = json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")

        try {
            val jsonStr = content.let {
                val start = it.indexOf('[')
                val end = it.lastIndexOf(']')
                if (start >= 0 && end > start) it.substring(start, end + 1) else it
            }
            val arr = org.json.JSONArray(jsonStr)
            val results = mutableListOf<Pair<String, String>>()
            for (i in 0 until arr.length()) {
                val pair = arr.getJSONArray(i)
                if (pair.length() >= 2) results.add(Pair(pair.getString(0), pair.getString(1)))
            }
            results.ifEmpty { segments.map { Pair(it, "[无翻译]") } }
        } catch (e: Exception) {
            Log.e("OpenAiApi", "Batch parse error: " + e.message + " raw: " + content.take(200))
            segments.map { Pair(it, "[解析失败]") }
        }
    }

    /**
     * Batch translate via cloud MiniMax-M3 (Anthropic-compatible protocol).
     * Used for the "cloud override" path: local translation shows first (fast),
     * cloud translation arrives later and REPLACES (覆盖) the local one.
     *
     * Unlike batchTranslateAndMerge() which uses OpenAI-compatible protocol,
     * MiniMax-M3 is reached via Anthropic Messages API at api.minimaxi.com/anthropic
     * with X-Api-Key header (not Authorization Bearer).
     *
     * Verified 2026-09-22: M3 batch translate returns clean JSON array of
     * [english,chinese] pairs in ~1.2-2s for 3-5 segments.
     */
    suspend fun batchTranslateAndMergeCloud(
        segments: List<String>
    ): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val apiKey = com.classroom.translator.BuildConfig.MINIMAX_API_KEY
        val baseUrl = com.classroom.translator.BuildConfig.MINIMAX_BASE_URL
        val model = com.classroom.translator.BuildConfig.MINIMAX_MODEL

        val segmentsText = segments.mapIndexed { i, s -> "${i+1}. $s" }.joinToString("\n")
        val prompt = "You are a professional interpreter. Receive numbered ASR segments from an English lecture. " +
            "Tasks: 1) Translate to Chinese 2) Merge consecutive incomplete sentences 3) Discard hallucinated segments " +
            "(random words like 'yeah','okay','think' alone without context) 4) Return ONLY a JSON array of [english,chinese] pairs."

        // Anthropic Messages API format
        val payload = JSONObject().apply {
            put("model", model)
            put("max_tokens", 4000)
            put("system", prompt)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "user"); put("content", segmentsText) })
            })
        }

        val request = Request.Builder()
            .url("$baseUrl/v1/messages")
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0")
            .addHeader("X-Api-Key", apiKey)  // Anthropic protocol uses X-Api-Key, not Bearer
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty cloud response")
        if (!response.isSuccessful) throw Exception("Cloud API error ${'$'}{response.code}: ${'$'}body")

        // Anthropic response: {"content":[{"type":"text","text":"..."}], ...}
        val json = JSONObject(body)
        val contentArr = json.getJSONArray("content")
        val content = StringBuilder()
        for (i in 0 until contentArr.length()) {
            val c = contentArr.getJSONObject(i)
            if (c.optString("type") == "text") content.append(c.getString("text"))
        }
        val text = content.toString()

        try {
            val jsonStr = text.let {
                val start = it.indexOf('[')
                val end = it.lastIndexOf(']')
                if (start >= 0 && end > start) it.substring(start, end + 1) else it
            }
            val arr = org.json.JSONArray(jsonStr)
            val results = mutableListOf<Pair<String, String>>()
            for (i in 0 until arr.length()) {
                val pair = arr.getJSONArray(i)
                if (pair.length() >= 2) results.add(Pair(pair.getString(0), pair.getString(1)))
            }
            results.ifEmpty { segments.map { Pair(it, "[无翻译]") } }
        } catch (e: Exception) {
            Log.e("OpenAiApi", "Cloud batch parse error: ${'$'}{e.message} raw: ${'$'}{text.take(200)}")
            segments.map { Pair(it, "[解析失败]") }
        }
    }

}