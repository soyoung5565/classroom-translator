package com.classroom.translator.audio

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Local vLLM Qwen3-ASR streaming client.
 *
 * Differs from DashScope: Qwen3-ASR is sentence-level streaming — the model
 * transcribes a whole sentence after VAD detects an end point, rather than
 * emitting partial words. So we buffer audio and send accumulated PCM every
 * [flushIntervalMs] as a WAV via vLLM's OpenAI-compatible /chat/completions
 * (content type "audio_url", data URI base64).
 *
 * This matches the proven "分句发送" approach: teacher finishes a sentence,
 * we send that segment, get the full transcription back.
 */
class LocalVllmAsrStream(
    private val baseUrl: String,          // e.g. http://192.168.1.249:8000/v1
    private val model: String = "Qwen3-ASR-1.7B",
    private val onResult: (text: String, isFinal: Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val onDisconnected: () -> Unit = {}
) {
    companion object {
        private const val TAG = "LocalVLLM"
        private const val SAMPLE_RATE = 16000
        internal const val FLUSH_INTERVAL_MS = 2000L   // 每2秒发一段
        internal const val MIN_SILENCE_MS = 700L        // 静音超700ms视为句末
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile private var isActive = false
    private val pcmBuffer = ByteArrayOutputStream()  // 累积 PCM (16k mono 16bit)

    /** 写入一段 PCM（来自 AudioRecorder 的 reader 线程） */
    fun feedAudio(pcm: ByteArray) {
        if (!isActive) return
        synchronized(pcmBuffer) {
            pcmBuffer.write(pcm)
        }
    }

    /** 当前是否活跃（未停止） */
    fun isActive(): Boolean = isActive

    /** 启动流式会话 */
    fun start() {
        isActive = true
    }

    /** 停止累积并清空缓冲 */
    fun stop() {
        isActive = false
        synchronized(pcmBuffer) {
            pcmBuffer.reset()
        }
        onDisconnected()
    }

    /**
     * 把累积音频送去转写（由 VM 每隔 FLUSH_INTERVAL_MS 调用）。
     * @return 转写文本，若有
     */
    suspend fun transcribePending(): String? = withContext(Dispatchers.IO) {
        if (!isActive) return@withContext null
        val pcm: ByteArray
        synchronized(pcmBuffer) {
            pcm = pcmBuffer.toByteArray()
            pcmBuffer.reset()
        }
        if (pcm.size < SAMPLE_RATE) return@withContext null   // 不足1秒，忽略

        val wav = WavEncoder.encode(pcm)  // 16k mono 16bit WAV
        val b64 = Base64.encodeToString(wav, Base64.NO_WRAP)
        val dataUri = "data:audio/wav;base64,$b64"

        try {
            val payload = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "audio_url")
                                put("audio_url", JSONObject().apply {
                                    put("url", dataUri)
                                })
                            })
                        })
                    })
                })
                put("max_tokens", 512)
            }
            val req = Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer not-needed")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                Log.e(TAG, "vLLM error ${resp.code}: ${body.take(300)}")
                onError("vLLM错误 ${resp.code}")
                return@withContext null
            }
            val json = JSONObject(body)
            val content = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            // vLLM Qwen3-ASR 输出形如: "language English<|asr_text|>..." 或 "...<asr_text>..."
            val cleaned = cleanAsrOutput(content)
            if (cleaned.isBlank()) return@withContext null
            onResult(cleaned, true)
            cleaned
        } catch (e: Exception) {
            Log.e(TAG, "vLLM request failed: ${e.message}")
            onError("识别失败: ${e.message}")
            null
        }
    }

    /** 剥离 vLLM Qwen3-ASR 输出的 language 前缀 / 特殊 token */
    internal fun cleanAsrOutput(raw: String): String {
        var s = raw.trim()
        // 1) 取 asr_text 标记之后的内容（<asr_text> 或 <|asr_text|>）
        val marker = Regex("(?i)</?\\|?asr_text\\|?>")
        val m = marker.find(s)
        if (m != null) {
            s = s.substring(m.range.last + 1).trim()
        }
        // 2) 剥 "language English" / "lang English" 前缀
        s = Regex("(?i)^lang(uage)?\\s*:?\\s*[a-z]{2,8}\\b\\s*").replaceFirst(s, "").trim()
        // 3) 若开头是孤立语言名（English/Chinese/Malay），剥掉
        s = Regex("(?i)^(english|chinese|malay)\\b\\s*").replaceFirst(s, "").trim()
        return s
    }
}
