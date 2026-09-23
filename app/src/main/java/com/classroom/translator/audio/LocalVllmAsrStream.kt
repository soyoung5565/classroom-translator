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
 * - 前缀只带滑动窗口（最近 MAX_PREFIX_CHARS 字符），禁止全量累积；
 * - 单段音频硬上限 MAX_SEGMENT_MS，超出部分留在缓冲下轮再送。
 */
class LocalVllmAsrStream(
    private val baseUrl: String,          // e.g. http://127.0.0.1:8080/v1
    private val model: String = "Qwen3-ASR-0.6B",
    // null = auto（让模型自动检测语言，支持马来+英语+中文混说）
    private val forceLanguage: String? = null,
    // true = llama-server (llama.cpp) 用 input_audio 格式; false = vLLM 用 audio_url 格式
    private val inputAudioFormat: Boolean = false,
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
        private const val MAX_PREFIX_CHARS = 400
        // 累积全文内存保护（只用于存盘/UI，不再参与请求）
        private const val MAX_ACCUMULATED_CHARS = 20000
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

    /** 已识别全文（前缀，供模型续听） */
    @Volatile private var accumulatedText = ""
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
        accumulatedText = ""
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
        // 前缀：只带最近 MAX_PREFIX_CHARS 字符（滑动窗口），禁止全量累积
        val prefix = accumulatedText.trim().takeLast(MAX_PREFIX_CHARS).trim()

        try {
            val payload = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        val textContent = buildString {
                            if (forceLanguage != null) append("language $forceLanguage<asr_text>")
                            if (prefix.isNotEmpty()) append(prefix)
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

            // 增量提取：输出可能 = 前缀 + 增量，剥掉前缀
            val cleanedNorm = cleaned.trim()
            val prefixNorm = prefix.trim()
            val delta: String = extractDelta(prefixNorm, cleanedNorm)

            // 累积全文（内存保护：只保留最近 MAX_ACCUMULATED_CHARS）
            if (delta.isNotEmpty()) {
                accumulatedText = (accumulatedText + " " + delta).trim()
                if (accumulatedText.length > MAX_ACCUMULATED_CHARS) {
                    accumulatedText = accumulatedText.takeLast(MAX_ACCUMULATED_CHARS)
                }
                if (segStartMs > 0) lastLatencyMs = System.currentTimeMillis() - segStartMs
                Log.i(TAG, "ASR delta(${lastLatencyMs}ms): $delta")
                onResult(delta, true)
            }
            delta
        } catch (e: Exception) {
            Log.e(TAG, "vLLM request failed: ${e.message}")
            onError("识别失败: ${e.message}")
            null
        }
    }

    /**
     * 从"前缀+增量"输出中剥出增量。
     * 1) 精确前缀匹配
     * 2) 词级 tail-head 对齐（模型轻微改写也能对上）
     * 3) 字符级最长匹配片段
     * 4) 与前缀高度重叠(>80% 词重复) 视为无新增，返回空串避免重复条目
     * 2026-09-23: 之前固定取前缀最后 20 字符匹配，模型整段重写时兜底"整段当增量"，
     * 24.8min 实测 104 段里 18 段整段重发、5 条重复条目。
     */
    internal fun extractDelta(prefixNorm: String, cleanedNorm: String): String {
        if (prefixNorm.isEmpty()) return cleanedNorm
        if (cleanedNorm.startsWith(prefixNorm)) return cleanedNorm.substring(prefixNorm.length).trim()

        val pw = prefixNorm.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val cw = cleanedNorm.split(Regex("\\s+")).filter { it.isNotEmpty() }
        // 2) 词级: prefix 尾部 k 词 == cleaned 开头 k 词
        val maxK = minOf(pw.size, cw.size, 40)
        var k = maxK
        while (k >= 3) {
            if (pw.takeLast(k) == cw.take(k)) return cw.drop(k).joinToString(" ").trim()
            k--
        }
        // 3) 字符级: prefix 尾部最长片段在 cleaned 中出现
        var cl = minOf(prefixNorm.length, 120)
        while (cl >= 12) {
            val idx = cleanedNorm.lastIndexOf(prefixNorm.takeLast(cl))
            if (idx >= 0) return cleanedNorm.substring(idx + cl).trim()
            cl -= 6
        }
        // 4) 高度重叠 => 模型重写同一段, 无新内容
        val overlap = cw.count { it in pw }
        if (cw.isNotEmpty() && overlap.toDouble() / cw.size > 0.8) return ""
        return cleanedNorm
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
