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
            .trim()
    }

    /**
     * Translate text using chat completions API.
     */
    private fun stripThinking(text: String): String {
        var result = text
        // Strip <think>...</think>
        result = result.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
        result = result.replace(Regex("<answer>(.*?)</answer>", RegexOption.DOT_MATCHES_ALL)) { it.groupValues[1] }
        result = result.replace(Regex("<think>.*", RegexOption.DOT_MATCHES_ALL), "")
        return result.trim()
    }

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
        }

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
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
            .trim()
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
            .getString("content").trim()

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

}