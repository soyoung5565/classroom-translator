package com.classroom.translator.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * 录音后处理链：直流去除 + 噪声底跟踪 + AGC 自动增益 + 软限幅。
 *
 * 2026-10-20 新增。解决两个线上问题：
 *
 * 1) **声音偏小 → 一个字都识别不到**
 *    手机麦克风的硬件增益因机型/距离差异极大。安静说话时原始 RMS 可能只有 -45 dBFS，
 *    而流式 VAD 的判定线 SILENCE_RMS = 0.008（≈ -42 dBFS）比它还高，
 *    于是语音在**进 ASR 之前**就被自己的 VAD 当成静音丢掉了。
 *    其它 App 能识别是因为它们都做了 AGC / 自动增益，我们没有。
 *    这里把语音稳定拉回 -26 dBFS 附近，VAD 与 ASR 都能正常工作。
 *
 * 2) **完全没有声音 / 忽大忽小**
 *    厂商 HAL 的 AGC/NS 行为不可控（VOICE_RECOGNITION 关掉它，MIC 打开它），
 *    同一个 App 在不同机型/不同距离上表现差异极大。增益改为我们自己控。
 *
 * 设计要点：
 * - 纯 Kotlin（无 Android 依赖）→ 可在 JVM 单元测试里验证。
 * - 按 20ms 帧处理（不是整块 0.5s），否则 VAD/电平的时间分辨率太粗。
 * - 增益跟随做平滑（attack 慢、release 快），避免句间音量跳变。
 * - 低于噪声底的"非语音"不做放大（否则整段底噪被拉到很大）。
 * - 软限幅（tanh）而不是硬削波，避免爆音。
 */
class AudioGainProcessor(
    private val sampleRate: Int = 16000,
    /** 目标语音 RMS（归一化 0~1，0.05 ≈ -26 dBFS） */
    private val targetRms: Float = 0.05f,
    /** 最大放大倍数（64 ≈ +36 dB）—— 覆盖极小声的麦克风 */
    private val maxGain: Float = 64f,
    /**
     * 绝对静音门：低于该电平视为数字静音（全是 0），一律不放大。
     * 只比"纯数字零"高一点（0.00002 ≈ -94 dBFS）。
     */
    private val absoluteSilence: Float = 0.00002f,
    /**
     * 语音判定：帧电平需高于噪声底该倍数。
     * 1.6 ≈ 4 dB —— 人声只要比环境底噪明显高就算"有人在说话"。
     *
     * 【2026-10-20 关键修正】之前用的是**绝对**电平门（0.0008 ≈ -62 dBFS），
     * 而麦克风底噪本身就是 -60 dBFS 量级 —— 于是"很轻的人声"和"底噪"
     * 落在同一个数量级，增益永远不启动，"声音小识别不到"的反馈原封不动保留。
     * 真机实测（MuMu，200Hz 测试音 RMS=0.0003）：gain 恒为 1.0，
     * 录音文件里语音仍是 -70 dBFS，ASR 依然听不见。
     */
    private val noiseMultiplier: Float = 1.6f,
    /**
     * 电平起伏度阈值（相对标准差）。
     * 0.12 —— 语音的音节起伏度通常 > 0.2；空调嗡鸣/风扇噪声/纯音 < 0.05。
     */
    private val modulationThreshold: Float = 0.12f,
    /** 语音电平 EMA 系数（越小越平滑，段内电平越稳定）—— 只用于"变大声"方向 */
    private val speechLevelAlpha: Float = 0.03f,
    /**
     * 语音电平 EMA 系数 —— 用于"变小声"方向。
     *
     * 【2026-10-23 修复】原先升降都用 0.03，导致一旦有人说过一句大声的，
     * speechLevel 就长期停在那个高电平上 → gain = target/speechLevel 被压住，
     * 后面整段小声**几乎不再被放大**（真机证据 user_205230.wav：
     * 前段 -43 dBFS 被抬 +21 dB，20 秒后 -37 dBFS 只抬 -1.9 dB）。
     * 小声方向必须快速跟随：约 1/0.15 ≈ 7 帧（0.13 秒）完成 63% 的跟随。
     */
    private val speechLevelFallAlpha: Float = 0.15f,
    /**
     * 噪声底估计窗口（帧数）：250 帧 = 5 秒。
     *
     * 【2026-10-23 修复】原为 1 秒。课堂场景老师**连续讲话**时，
     * 1 秒窗口里几乎全是语音，10 百分位等于语音电平本身（实测 noiseFloor 冲到
     * -32 dBFS = 正在说话的音量），于是"高于噪声底 1.6 倍"这条语音判据永远不成立
     * → AGC 停摆 → 小声不被抬起。5 秒窗口才能跨过句间停顿抓到真正的环境底噪。
     */
    private val windowFrames: Int = 250,
    /**
     * 噪声底估计的初始值（0.0002 ≈ -74 dBFS）。
     * 刚开始还没统计到足够样本时用它，之后由百分位估计接管。
     */
    private val initialNoiseFloor: Float = 0.0002f,
    /** 一阶高通截止频率（去直流 + 去低频隆隆声） */
    private val highPassHz: Float = 60f,
) {

    companion object {
        /** 一帧 20ms：16kHz 单声道 16bit = 320 采样 = 640 字节 */
        const val FRAME_SAMPLES = 320

        /**
         * 峰值余量：放大后允许达到的最大峰值（满量程 = 1.0）。
         * 留 0.85 给 tanh 软限幅的过渡区，避免波形被压扁失真。
         */
        const val PEAK_HEADROOM = 0.85f

        /** 计算归一化 RMS（静态工具，0~1） */
        fun normalizedRms(pcm: ByteArray, length: Int = pcm.size): Float {
            if (length < 2) return 0f
            val shorts = ShortArray(length / 2)
            ByteBuffer.wrap(pcm, 0, length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(shorts)
            var sum = 0.0
            for (s in shorts) sum += s.toDouble() * s.toDouble()
            return (sqrt(sum / shorts.size) / 32768.0).toFloat()
        }
    }

    // ---- 高通滤波器状态（跨块保持，不能每块重置）----
    //   【2026-10-20 修正】必须用**直接形式**的单极高通：
    //       y[n] = α·( y[n-1] + x[n] - x[n-1] ),  α = RC/(RC+dt),  RC = 1/(2π·fc)
    //   之前写的是 "x - lowpass(x)"（α 同样取 rc/(rc+dt)），那其实是直流阻断器，
    //   截止频率只有 (1-α)/2π ≈ 9 Hz —— 200 Hz 语音被衰减 -27 dB，整段输出归零，
    //   表现为"手机录到声音但 App 完全没声音"。
    private var hpPrevX = 0f        // 上一采样输入
    private var hpPrevY = 0f        // 上一采样输出
    private var hpPrimed = false    // 是否已用首样本预置初值
    private val hpAlpha: Float      // RC / (RC + dt)

    // ---- 噪声底 / 语音电平 / 增益状态 ----
    /** 最近若干帧的 RMS（用于估计噪声底百分位） */
    private val rmsHistory = ArrayDeque<Float>()
    /** 电平起伏度历史（用于区分语音与稳定嗡鸣/纯音） */
    private val modulationHistory = ArrayDeque<Float>()
    private var noiseFloor = initialNoiseFloor
    private var speechLevel = 0f      // 语音电平估计（EMA，跨整段稳定）
    private var gain = 1f
    private var lastFrameRms = 0f
    private var lastFrameSpeech = false
    private var framesSeen = 0
    private var framesSinceVoice = 0
    /** 信号峰值（用于增益上限，保证放大后不削波） */
    private var signalPeak = 0f

    /** 帧缓冲（高通后的样本） */
    private val frameBuf = FloatArray(FRAME_SAMPLES)

    init {
        val rc = 1.0 / (2.0 * Math.PI * highPassHz)
        val dt = 1.0 / sampleRate
        hpAlpha = (rc / (rc + dt)).toFloat()
    }

    /** 上一帧输入 RMS（未增益）—— 用来判断"麦克风到底有没有收到声音" */
    val inputRms: Float get() = lastFrameRms

    /** 上一帧是否判定为语音（高于绝对静音门，且没有明显低于峰值电平） */
    val isSpeech: Boolean get() = lastFrameSpeech

    /** 当前使用的增益倍数 */
    val currentGain: Float get() = gain

    /** 当前估计的环境噪声底（归一化 RMS） */
    val noiseFloorLevel: Float get() = noiseFloor

    /**
     * 处理一块 PCM（16bit little-endian 单声道），返回同样长度的处理后 PCM。
     * 输入输出可以是不同的数组（内部不修改入参）。
     */
    fun process(pcm: ByteArray): ByteArray {
        if (pcm.size < 2) return pcm
        val out = ByteArray(pcm.size)
        val shorts = ShortArray(pcm.size / 2)
        ByteBuffer.wrap(pcm, 0, pcm.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shorts)

        var i = 0
        while (i + FRAME_SAMPLES <= shorts.size) {
            processFrame(shorts, i, FRAME_SAMPLES, out, i * 2)
            i += FRAME_SAMPLES
        }
        // 尾巴不足一帧的部分：按最后一帧的增益处理，避免尾部电平突变
        if (i < shorts.size) {
            processFrame(shorts, i, shorts.size - i, out, i * 2)
        }
        return out
    }

    private fun processFrame(
        shorts: ShortArray,
        offset: Int,
        count: Int,
        out: ByteArray,
        outOffset: Int,
    ) {
        // 1) 高通（去直流）+ 统计帧 RMS
        //    直接形式：y[n] = α·( y[n-1] + x[n] - x[n-1] )
        var sum = 0.0
        for (k in 0 until count) {
            val x = shorts[offset + k] / 32768f
            if (!hpPrimed) {
                // 用首样本预置状态 → 没有启动瞬态。
                // 否则录音开头会有一声"砰"（DC 建立过程），把峰值保持锁死，
                // 后面整段小声都被压着。
                hpPrevX = x
                hpPrevY = 0f
                hpPrimed = true
                frameBuf[k] = 0f
                continue
            }
            val y = hpAlpha * (hpPrevY + x - hpPrevX)
            hpPrevX = x
            hpPrevY = y
            frameBuf[k] = y
            sum += (y * y).toDouble()
        }
        val rms = sqrt(sum / count).toFloat()
        lastFrameRms = rms
        framesSeen++

        // 2) 噪声底估计：最近 windowFrames 帧（5 秒）RMS 的第 10 百分位。
        //    用百分位而不是最小值 —— 最小值会被偶发爆音（碰麦、敲桌）拉低，
        //    百分位对离群值稳健。
        //
        //    【2026-10-23 修复】只取百分位还不够：课堂上老师**几乎连续讲话**时，
        //    5 秒窗口里 10 百分位仍然落在语音电平上，噪声底会一路爬升到"正在说话的音量"
        //    （真机实测 noiseFloor 冲到 0.026 ≈ -32 dBFS），于是"高于噪声底 1.6 倍"
        //    这条判据永远不成立 → AGC 停摆 → 后面小声完全不放大。
        //    改成经典的 minimum statistics 跟踪：**下降快、上升慢**。
        //    测到低电平（句间停顿）立即采用，电平升上来只允许缓慢跟随，
        //    这样连续讲话不会污染噪声底。
        rmsHistory.addLast(rms)
        while (rmsHistory.size > windowFrames) rmsHistory.removeFirst()
        if (rmsHistory.size >= 10) {
            val sorted = rmsHistory.sorted()
            val candidate = max(sorted[sorted.size / 10], 0.000005f)
            noiseFloor = if (candidate < noiseFloor) {
                // 确认更安静 → 立即跟进（句间停顿马上把底噪打下来）
                candidate
            } else {
                // 变响 → 每帧最多 +0.2%（≈ +0.87 dB/秒），环境噪声真变响时才跟得上
                min(candidate, noiseFloor * 1.002f)
            }
        }

        // 3) 电平起伏度：近 0.5 秒（25 帧）内 RMS 的相对标准差。
        //    语音有音节起伏（起伏度大），而空调嗡鸣/风扇噪声/纯音是稳定的（起伏度≈0）——
        //    这是区分"该放大的语音"和"不该放大的稳定噪声"的关键判据。
        val recent = rmsHistory.takeLast(25)
        var modulation = 0f
        if (recent.size >= 5) {
            var mean = 0f
            for (v in recent) mean += v
            mean /= recent.size
            var variance = 0f
            for (v in recent) { val d = v - mean; variance += d * d }
            variance /= recent.size
            modulation = sqrt(variance) / max(mean, 1e-9f)
        }
        modulationHistory.addLast(modulation)
        while (modulationHistory.size > 40) modulationHistory.removeFirst()
        var modAvg = 0f
        for (v in modulationHistory) modAvg += v
        modAvg /= modulationHistory.size

        // 4) 语音判定：帧电平高于噪声底一个倍数 **且** 电平有明显起伏
        val isVoice = rms >= noiseFloor * noiseMultiplier && modAvg > modulationThreshold
        lastFrameSpeech = isVoice

        // 5) 语音电平估计（EMA）：只用语音帧更新，停顿帧不动
        //    → 增益在**一句话内**保持恒定 → 信噪比不受影响。
        //
        //    【2026-10-23 修复】升降必须用不同系数：
        //    - 变大声：慢跟随（0.03），避免一句话里音量起伏带动增益抖动；
        //    - 变小声：快跟随（0.15），否则前面一句大声会把 gain 锁死在高电平对应的
        //      低增益上，后面整段小声都抬不起来 —— 正是用户"声音小就识别不到"的残留成因。
        if (isVoice) {
            framesSinceVoice = 0
            speechLevel = when {
                speechLevel <= 0f -> rms
                rms > speechLevel -> speechLevel + (rms - speechLevel) * speechLevelAlpha
                else -> speechLevel + (rms - speechLevel) * speechLevelFallAlpha
            }
        } else {
            framesSinceVoice++
            // 长时间无声（> 2 秒）才让语音电平缓慢回落
            if (framesSinceVoice > 100) speechLevel *= 0.995f
        }

        // 6) 目标增益：把语音电平拉到 targetRms（只放大，不缩小）
        var target = 1f
        if (speechLevel > 1e-9f) {
            target = (targetRms / speechLevel).coerceIn(1f, maxGain)
        }
        // 增益平滑：
        //  - 升增益慢一点（0.3），跟得上开口但不会瞬间冲高；
        //  - 降增益必须快（0.5）—— 【2026-10-23 修复】原来是 0.08，从大增益
        //    （如 19 倍）回落要 30 帧（0.6 秒）以上，这期间"突然大声说话"
        //    会被过度放大接近削波（真机实测：轻语音段后 1 秒的响语音被多抬 +8 dB）。
        //    宁可短暂偏轻，也不能过载失真。
        gain = if (target > gain) {
            gain + (target - gain) * 0.3f
        } else {
            gain + (target - gain) * 0.5f
        }

        // 6b) 峰值限幅（peak limiting）。
        //     【2026-10-22】只按 RMS 算增益不够：语音的瞬时峰值可以远高于 RMS，
        //     放大后峰值直接冲到满量程 → tanh 软限幅把波形压扁 → 失真 → 识别率下降。
        //     真机证据（用户 10-20 的录音）：RMS-based 增益后 peak = 1.000（满量程削平）。
        //     这里用"帧内峰值"给增益设上限，保证输出峰值不超过 headroom。
        var framePeak = 0f
        for (k in 0 until count) {
            val a = abs(frameBuf[k])
            if (a > framePeak) framePeak = a
        }
        if (framePeak > signalPeak) signalPeak = framePeak else signalPeak = signalPeak * 0.98f
        if (signalPeak > 1e-6f) {
            val maxAllowed = PEAK_HEADROOM / signalPeak
            if (maxAllowed < gain) gain = maxAllowed.coerceAtLeast(1f)
        }

        // 6) 应用增益 + 软限幅。
        //    输出的是**高通后**的信号 —— 直流失真是被去掉的（否则 RMS 被 DC 抬高，
        //    语音会被误判，且模型收到带直流偏置的音频）。
        val g = gain
        for (k in 0 until count) {
            val clipped = softClip(frameBuf[k] * g)
            val s = (clipped * 32767f).toInt()
            out[outOffset + k * 2] = (s and 0xFF).toByte()
            out[outOffset + k * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
    }

    /** tanh 软限幅：|x|<0.9 线性，超过后平滑压缩到 ±1 */
    private fun softClip(x: Float): Float {
        val a = abs(x)
        if (a <= 0.9f) return x
        val over = (a - 0.9f) / 0.1f
        return if (x >= 0) 0.9f + 0.1f * tanh(over.toDouble()).toFloat()
        else -(0.9f + 0.1f * tanh(over.toDouble()).toFloat())
    }

    /** 重置状态（每次开始新录音时调用） */
    fun reset() {
        hpPrevX = 0f
        hpPrevY = 0f
        hpPrimed = false
        rmsHistory.clear()
        modulationHistory.clear()
        noiseFloor = initialNoiseFloor
        speechLevel = 0f
        gain = 1f
        lastFrameRms = 0f
        lastFrameSpeech = false
        framesSeen = 0
        framesSinceVoice = 0
        signalPeak = 0f
    }
}

/** dB → 线性幅度（10^(db/20)） */
private fun dbToLinear(db: Float): Float = exp(db * ln(10f) / 20f)
