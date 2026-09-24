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
import kotlin.math.sqrt

/**
 * Qwen3-ASR streaming client (vLLM / llama.cpp backend).
 *
 * 真正的流式（非切片）：
 * - VAD 句末检测：RMS 低于阈值持续 [minSilenceMs] 视为一句话说完 → 才发送
 * - 前缀续听：每次请求把"最近已识别文本"作为 text 前缀 + 新音频，模型基于前文续写增量
 * - 增量提取：输出 = 前缀 + 增量，剥掉前缀得到新识别内容
 * 这对应 Qwen3-ASR 官方 streaming（2s chunk + prefix + token 回滚），
 * 而不是孤立 2s 硬切。
 *
 * 防卡死（2026-09-11 修，24.8min 真机实测暴露）：
 * - 每段独立送 ASR（不带历史前缀，避免重复吐前缀/截断）；
 * - 单段音频硬上限 MAX_SEGMENT_MS，超出部分留在缓冲下轮再送。
 */
class LocalVllmAsrStream(
    private val baseUrl: String,          // e.g. http://127.0.0.1:8080/v1
    private val model: String = "Qwen3-ASR-0.6B",
    // null = auto（让模型自动检测语言，支持马来+英语+中文混说）
    private val forceLanguage: String? = null,
    // true = llama-server (llama.cpp) 用 input_audio 格式; false = vLLM 用 audio_url 格式
    private val inputAudioFormat: Boolean = true,   // 本地后端固定是 llama-server，只支持 input_audio
    private val onResult: (text: String, isFinal: Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val onDisconnected: () -> Unit = {}
) {
    companion object {
        private const val TAG = "LocalVLLM"
        private const val SAMPLE_RATE = 16000
        internal const val FLUSH_INTERVAL_MS = 2000L   // 轮询周期（检查句末）
        internal const val MIN_SILENCE_MS = 500L        // 静音超500ms视为句末（快一点出结果）
        private const val SILENCE_RMS = 0.008f          // RMS 低于该值=静音（16k PCM 比例）
        private const val MIN_SEGMENT_MS = 800L          // 段最短 0.8s（太短不送）

        // ===== 前缀续听防卡死 =====
        // 前缀只带最近 N 字符（滑动窗口），禁止全量累积——
        // 全量前缀会把 llama-server(-c 4096) 上下文灌满，单次推理从 1s 膨胀到 22+ 分钟。
        // 累积全文内存保护（只用于存盘/UI，不再参与请求）
        // 单段音频硬上限 8s：推理慢时新音频会继续进缓冲，超过 8s 的部分
        // 留在缓冲下轮再送，防止"推理越慢→缓冲越长→推理更慢"死亡螺旋。
        private const val MAX_SEGMENT_MS = 8000L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile private var isActive = false
    private val pcmBuffer = ByteArrayOutputStream()  // 当前段 PCM (16k mono 16bit)
    @Volatile private var pcmStartMs: Long = 0L      // 当前段首块到达时间
    @Volatile private var lastVoiceMs: Long = 0L     // 最近一次有声音的时间
    @Volatile private var hasVoice = false           // 当前段是否已捕获过声音

    /** 最近一次转写的延迟（段起点 -> 结果回来），ms */
    @Volatile var lastLatencyMs: Long = 0L; private set

    /** 写入一段 PCM（来自 AudioRecorder / PlaybackCaptureRecorder 的 reader 线程） */
    fun feedAudio(pcm: ByteArray) {
        if (!isActive) return
        val now = System.currentTimeMillis()
        synchronized(pcmBuffer) {
            if (pcmBuffer.size() == 0) {
                pcmStartMs = now
                hasVoice = false
            }
            pcmBuffer.write(pcm)
        }
        // 能量检测（静音计时）
        val rms = calculateRms(pcm, pcm.size)
        if (rms >= SILENCE_RMS) {
            lastVoiceMs = now
            hasVoice = true
        }
        // 静音保护: 纯静音缓冲超 5s 直接丢, 不发 ASR 不占内存
        // (之前静音也会攒满 6s 被强发 -> 播完后 ASR 永续空转 = 停了还烫的元凶之一)
        if (!hasVoice) {
            synchronized(pcmBuffer) {
                if (pcmBuffer.size() > SAMPLE_RATE * 2 * 5) {
                    pcmBuffer.reset()
                    pcmStartMs = now
                }
            }
        }
    }

    /** VAD：当前是否检测到句末（有声音 + 静音持续 [MIN_SILENCE_MS]） */
    fun isSentenceEnd(): Boolean {
        if (!isActive) return false
        if (!hasVoice) return false
        val now = System.currentTimeMillis()
        val silence = now - lastVoiceMs
        val segmentMs = now - pcmStartMs
        return silence >= MIN_SILENCE_MS && segmentMs >= MIN_SEGMENT_MS
    }

    /**
     * 是否该把当前缓冲送 ASR（2026-09-24 改）：
     *  - 检测到句末（静音 >= MIN_SILENCE_MS）
     *  - 或缓冲已达单段上限 MAX_SEGMENT_MS（长句切分硬上限）
     * 这样每段都是"完整句子 / 满 8 秒"，避免每 2 秒发半句造成碎片化与内容丢失。
     */
    fun shouldFlush(): Boolean {
        if (!isActive) return false
        if (!hasVoice) return false
        if (isSentenceEnd()) return true
        return bufferedMs() >= MAX_SEGMENT_MS
    }

    /** 当前缓冲音频时长 ms */
    fun bufferedMs(): Long {
        synchronized(pcmBuffer) { return System.currentTimeMillis() - pcmStartMs }
    }

    /** 当前是否活跃（未停止） */
    fun isActive(): Boolean = isActive

    /** 当前缓冲段是否含人声（静音段不值得发给 ASR） */
    fun hasPendingVoice(): Boolean = hasVoice

    /** 启动流式会话 */
    fun start() {
        isActive = true
        synchronized(pcmBuffer) { pcmBuffer.reset() }
    }

    /** 停止累积并清空缓冲 */
    fun stop() {
        isActive = false
        synchronized(pcmBuffer) { pcmBuffer.reset() }
        onDisconnected()
    }

    /**
     * 把当前段送去转写（由 VM 在句末或超时调用）。
     * 请求 = 前缀（最近文本滑动窗口）+ 新音频；输出剥前缀取增量。
     * @return 增量文本，若有
     */
    suspend fun transcribePending(): String? = withContext(Dispatchers.IO) {
        if (!isActive) return@withContext null
        val pcm: ByteArray
        val segStartMs: Long
        synchronized(pcmBuffer) {
            // 单段音频硬上限：只取前 MAX_SEGMENT_MS，多余留在缓冲下轮再送
            val bytesPerMs = SAMPLE_RATE * 2 / 1000   // 16bit mono = 32 B/ms
            val maxBytes = (MAX_SEGMENT_MS * bytesPerMs).toInt()
            val all = pcmBuffer.toByteArray()
            if (all.size > maxBytes) {
                pcm = all.copyOfRange(0, maxBytes)
                pcmBuffer.reset()
                pcmBuffer.write(all, maxBytes, all.size - maxBytes)
            } else {
                pcm = all
                pcmBuffer.reset()
            }
            segStartMs = pcmStartMs
            pcmStartMs = System.currentTimeMillis()
        }
        if (pcm.size < SAMPLE_RATE / 2) return@withContext null   // 不足0.5s，忽略
        if (!hasVoice) return@withContext null  // 无人声段不发 ASR（静音空转发热/假结果）

        val wav = WavEncoder.encode(pcm)  // 16k mono 16bit WAV
        val b64 = Base64.encodeToString(wav, Base64.NO_WRAP)
        val dataUri = "data:audio/wav;base64,$b64"
        // 不再带历史文本前缀（2026-09-24 实测：前缀会让模型重复吐前缀、截断新内容，
        // 导致实时覆盖率仅 53%，而去掉前缀后同一录音离线跑覆盖率达 94.7%）

        try {
            val payload = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        val textContent = buildString {
                            if (forceLanguage != null) append("language $forceLanguage<asr_text>")
                        }
                        put("content", JSONArray().apply {
                            if (textContent.isNotEmpty()) {
                                put(JSONObject().apply { put("type", "text"); put("text", textContent) })
                            }
                            if (inputAudioFormat) {
                                put(JSONObject().apply {
                                    put("type", "input_audio")
                                    put("input_audio", JSONObject().apply {
                                        put("data", b64)
                                        put("format", "wav")
                                    })
                                })
                            } else {
                                put(JSONObject().apply {
                                    put("type", "audio_url")
                                    put("audio_url", JSONObject().apply {
                                        put("url", dataUri)
                                    })
                                })
                            }
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
                onError("识别错误 ${resp.code}")
                return@withContext null
            }
            val json = JSONObject(body)
            val content = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            val cleaned = cleanAsrOutput(content)
            if (cleaned.isBlank()) return@withContext null

            // 无前缀模式：输出即本段完整转写
            val delta: String = cleaned.trim()
            if (delta.isNotEmpty()) {
                if (segStartMs > 0) lastLatencyMs = System.currentTimeMillis() - segStartMs
                Log.i(TAG, "ASR(${lastLatencyMs}ms): $delta")
                onResult(delta, true)
            }
            delta
        } catch (e: Exception) {
            Log.e(TAG, "vLLM request failed: ${e.message}")
            onError("识别失败: ${e.message}")
            null
        }
    }

    /** 剥离 Qwen3-ASR 输出的 language 前缀 / 特殊 token */
    internal fun cleanAsrOutput(raw: String): String {
        var s = raw.trim()
        // 1) 取 asr_text 标记之后的内容（<asr_text> 或 <|asr_text|>）
        val marker = Regex("(?i)</?\\|?asr_text\\|?>")
        val m = marker.find(s)
        if (m != null) s = s.substring(m.range.last + 1).trim()
        // 2) 剥 "language English" / "lang English" 前缀
        s = Regex("(?i)^lang(uage)?\\s*:?\\s*[a-z]{2,8}\\b\\s*").replaceFirst(s, "").trim()
        // 3) 若开头是孤立语言名（English/Chinese/Malay），剥掉
        s = Regex("(?i)^(english|chinese|malay)\\b\\s*").replaceFirst(s, "").trim()
        return s
    }

    private fun calculateRms(buffer: ByteArray, length: Int): Float {
        if (length < 2) return 0f
        var sum = 0.0
        val shorts = ShortArray(length / 2)
        ByteBuffer.wrap(buffer, 0, length)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shorts)
        for (s in shorts) sum += s.toDouble() * s.toDouble()
        val rms = sqrt(sum / shorts.size)
        return (rms / 32768.0).toFloat()
    }
}
