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
import com.classroom.translator.ui.TranscriptMerger
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
    /**
     * 【2026-11-03】Android Context —— 用于向 LocalLlmServerManager 回报
     * ASR 请求成功/失败（连续失败 3 次 = 引擎挂死 → 强制重启）。
     */
    private val ctx: android.content.Context,
    /**
     * 【2026-11-03】对外 ASR 地址的**提供者**（每次请求时解析）。
     *
     * ⚠️ 之前是固定字符串 `baseUrl` —— 引擎轮换后对外端口会变
     * （A: 8000 ↔ B: 8100），而流对象在录音开始时只创建一次 → 轮换之后
     * 所有请求都打到已经下线的引擎 → 翻译直接停摆。
     *
     * 现在改成 lambda：每次发请求都问管理器"当前对外的 URL 是多少"。
     */
    private val baseUrlProvider: () -> String,
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
        private const val FLUSH_INTERVAL_MS = 2000L   // 轮询周期（检查句末）
        internal const val MIN_SILENCE_MS = 500L        // 静音超500ms视为句末（快一点出结果）
        /**
         * 静音判定阈值（归一化 RMS）。
         * 【2026-10-20 下调】原为 0.008（≈ -42 dBFS）。但 AudioRecorder 现在会先做
         * AGC 把语音抬到 ~0.05 RMS，0.008 这条线反而太低了 —— 底噪也会被判成"有声音"，
         * 导致每段都发 ASR（空转发热）。同时下限必须留够余量给"刚开口的半个字"。
         */
        private const val SILENCE_RMS = 0.004f          // RMS 低于该值=静音（16k PCM 比例）
        /**
         * 语音判定阈值：必须高于这个电平才算"有人在说话"。
         * 【2026-10-20】原逻辑只看"不是静音"就算有声音 → 底噪、键盘声、
         * 桌面敲击都会被送去 ASR（浪费算力 + 出乱码 + 触发幻觉转写）。
         * 现在要求明显高于底噪才认定为语音。
         */
        private const val VOICE_RMS = 0.012f           // 约 -38 dBFS，AGC 后正常语音远超此值
        private const val MIN_SEGMENT_MS = 800L          // 段最短 0.8s（太短不送）

        // ===== 前缀续听防卡死 =====
        // 前缀只带最近 N 字符（滑动窗口），禁止全量累积——
        // 全量前缀会把 llama-server(-c 4096) 上下文灌满，单次推理从 1s 膨胀到 22+ 分钟。
        // 累积全文内存保护（只用于存盘/UI，不再参与请求）
        // 单段音频硬上限 8s：推理慢时新音频会继续进缓冲，超过 8s 的部分
        // 留在缓冲下轮再送，防止"推理越慢→缓冲越长→推理更慢"死亡螺旋。
        private const val MAX_SEGMENT_MS = 8000L
        /**
         * 【2026-11-03】跨硬切段合并时的文本长度上限。
         * 超过就不再合并 —— 避免老师长时间连续说话时，几十秒原文挤成一张卡片、
         * 翻译迟迟出不来（课堂延迟是硬指标）。
         */
        private const val MAX_MERGE_CHARS = 400

        /**
         * 剥离 Qwen3-ASR 输出的 language 前缀 / 特殊 token。
         *
         * 【2026-11-03 修复】原第 3 步是 `^(english|chinese|malay)\b\s*` —— 无条件剥掉开头的
         * 语言名。**老师上课第一句就可能是 "English is easy to learn."** →
         * 识别结果被剥成 "is easy to learn." → 界面上凭空少了一个词。
         * 这就是用户说的「识别结果没有显示完整」的一个真实来源。
         *
         * 现在只有两种情况才剥：
         *   ① 带冒号的语言标签（"English: ..." / "English：..."）—— 一定是标签
         *   ② 整个输出就是这个语言名（"English"）—— 没有任何正文
         * 其余情况一律保留原文。
         */
        internal fun cleanAsrOutput(raw: String): String {
            var s = raw.trim()
            // 1) 取 asr_text 标记之后的内容（<asr_text> 或 <|asr_text|>）
            val marker = Regex("(?i)</?\\|?asr_text\\|?>")
            val m = marker.find(s)
            if (m != null) s = s.substring(m.range.last + 1).trim()
            // 2) 剥 "language English" / "lang English" 前缀（这是明确的语言标签）
            s = Regex("(?i)^lang(uage)?\\s*:?\\s*[a-z]{2,8}\\b\\s*").replaceFirst(s, "").trim()
            // 3) 带冒号的语言标签："English: xxx" / "English：xxx"
            s = Regex("(?i)^(english|chinese|malay)\\s*[:：]\\s*").replaceFirst(s, "").trim()
            // 4) 整个输出只有语言名（没有任何正文）→ 剥掉
            if (Regex("(?i)^(english|chinese|malay)\\s*$").matches(s)) s = ""
            return s
        }
    }

    /**
     * 拿到 Application（LocalLlmServerManager.get() 要 Application）。
     * 用 applicationContext 避免持有 Activity（防泄漏）。
     */
    private val appCtx: android.app.Application?
        get() = ctx.applicationContext as? android.app.Application

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

    /**
     * 【2026-11-03】跨「硬切段」的累积文本。
     *
     * 用户反馈「识别结果没有显示完整」：卡片上英文和中文都停在半句话，
     * 两边断在同一个位置 → 不是 UI 截断，是**数据本身被截断了**。
     *
     * 根因：单段音频有 8 秒硬上限（MAX_SEGMENT_MS）。超过 8 秒的句子被切成两段，
     * 两段是**独立**识别的（不带前缀续听，见下方注释），于是第一段转写就停在 8 秒处，
     * 翻译照着这半句翻 → 中英文一起断在半句。
     *
     * 修法：识别到「本段是被硬切开的」时，把文本留在 pendingContinuation；
     * 下一段到达时用 [TranscriptMerger] 合并（去边界重复词）后作为**完整文本**输出。
     * 上层 addOrMergeEntry 会用新文本替换上一条 → 卡片上最终显示完整句子。
     */
    private val pendingContinuation = StringBuilder()

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
        // 【2026-10-20】两级判定：
        //   低于 SILENCE_RMS → 静音（用于句末计时）
        //   高于 VOICE_RMS   → 确认有人在说话（用于"这一段要不要送 ASR"）
        //   介于两者之间     → 存疑：可能是在开口的第一个字，也可能只是底噪。
        //     存疑时保持"上一个状态"不变（既不当静音也不当语音），
        //     这样开口的第一个字不会被当成静音丢掉，而纯底噪也不会被当语音。
        val rms = calculateRms(pcm, pcm.size)
        if (rms >= VOICE_RMS) {
            lastVoiceMs = now
            hasVoice = true
        } else if (rms < SILENCE_RMS) {
            // 明确的静音 → 更新句末计时（但不立刻清 hasVoice，由 isSentenceEnd 判断）
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
        synchronized(pendingContinuation) { pendingContinuation.setLength(0) }
    }

    /** 停止累积并清空缓冲 */
    fun stop() {
        isActive = false
        synchronized(pcmBuffer) { pcmBuffer.reset() }
        synchronized(pendingContinuation) { pendingContinuation.setLength(0) }
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
        /**
         * 【2026-11-03】本段是否被 8 秒硬上限切开。
         * true = 这句话还没说完，文本要跟下一段合并（否则卡片上就是半句话）。
         */
        var hardCut = false
        synchronized(pcmBuffer) {
            // 单段音频硬上限：只取前 MAX_SEGMENT_MS，多余留在缓冲下轮再送
            val bytesPerMs = SAMPLE_RATE * 2 / 1000   // 16bit mono = 32 B/ms
            val maxBytes = (MAX_SEGMENT_MS * bytesPerMs).toInt()
            val all = pcmBuffer.toByteArray()
            if (all.size > maxBytes) {
                pcm = all.copyOfRange(0, maxBytes)
                pcmBuffer.reset()
                pcmBuffer.write(all, maxBytes, all.size - maxBytes)
                hardCut = true      // ← 后面还有音频没送，这句没说完
            } else {
                pcm = all
                pcmBuffer.reset()
            }
            segStartMs = pcmStartMs
            pcmStartMs = System.currentTimeMillis()
        }
        if (pcm.size < SAMPLE_RATE / 2) {
            /**
             * 【2026-11-03】不足 0.5s 的片段**放回缓冲**，不要丢。
             *
             * 原代码直接 return → 这 0.5s 音频从缓冲里已经被取走了（上面 reset 过），
             * 永远送不出去 → 老师的开头几个字被吃掉。
             * 这正是「识别结果没有显示完整」的另一个来源。
             */
            if (pcm.isNotEmpty()) {
                synchronized(pcmBuffer) {
                    val keep = pcmBuffer.toByteArray()
                    pcmBuffer.reset()
                    pcmBuffer.write(pcm, 0, pcm.size)
                    if (keep.isNotEmpty()) pcmBuffer.write(keep, 0, keep.size)
                }
            }
            return@withContext null
        }
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
                .url("${baseUrlProvider().trimEnd('/')}/chat/completions")
                .addHeader("Authorization", "Bearer not-needed")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                Log.e(TAG, "vLLM error ${resp.code}: ${body.take(300)}")
                // 【2026-11-03】连续失败 3 次 → 判定引擎挂死 → 强制重启
                runCatching { appCtx?.let { com.classroom.translator.local.LocalLlmServerManager.get(it).reportAsrFailure() } }
                onError("识别错误 ${resp.code}")
                return@withContext null
            }
            val json = JSONObject(body)
            val content = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            // 成功返回 → 挂死计数清零
            runCatching { appCtx?.let { com.classroom.translator.local.LocalLlmServerManager.get(it).reportAsrSuccess() } }

            val cleaned = cleanAsrOutput(content)
            if (cleaned.isBlank()) return@withContext null

            /**
             * 【2026-11-03】跨硬切段的文本合并。
             *
             * 上一段若是被 8 秒硬上限切开的（half=true），它的文本只到半句话；
             * 本段接上后用 [TranscriptMerger.merge] 拼成完整句子再输出。
             * 上层 addOrMergeEntry 会用「更长且以旧文本开头」的新文本替换上一条
             * → 卡片上最终显示的是完整句子，而不是停在半句。
             *
             * 合并后如果**仍然**是硬切（还有音频没说完），把合并结果留在 pending 里，
             * 等下一段继续接。
             */
            val delta: String = synchronized(pendingContinuation) {
                val prev = pendingContinuation.toString()
                pendingContinuation.setLength(0)
                /**
                 * 【2026-11-03】合并长度封顶。
                 *
                 * 老师连续说话很久（比如 30 秒不停）→ 会被切成 4 段 8 秒 → 全部合并
                 * → 一张卡片上 30 秒的原文，翻译要等最后一段才出。
                 * 延迟会爆掉（课堂场景不可接受）。
                 *
                 * 所以：累积文本超过 MAX_MERGE_CHARS 就**不再合并**，新开一段。
                 * 这保证「合并只用来修边界，不会把整段课吞成一条」。
                 */
                val merged = when {
                    prev.isEmpty() -> cleaned.trim()
                    prev.length >= MAX_MERGE_CHARS -> {
                        Log.i(TAG, "累积文本已达 ${prev.length} 字，本段不再合并")
                        cleaned.trim()
                    }
                    else -> TranscriptMerger.merge(prev, cleaned.trim())
                }
                if (hardCut) pendingContinuation.append(merged)
                merged
            }
            if (delta.isNotEmpty()) {
                if (segStartMs > 0) lastLatencyMs = System.currentTimeMillis() - segStartMs
                Log.i(TAG, "ASR(${lastLatencyMs}ms)${if (hardCut) "[硬切,待续]" else ""}: $delta")
                onResult(delta, true)
            }
            delta
        } catch (e: Exception) {
            Log.e(TAG, "vLLM request failed: ${e.message}")
            // 【2026-11-03】超时/连接错也算失败 → 连续 3 次强制重启引擎
            runCatching { appCtx?.let { com.classroom.translator.local.LocalLlmServerManager.get(it).reportAsrFailure() } }
            onError("识别失败: ${e.message}")
            null
        }
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
