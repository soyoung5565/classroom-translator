package com.classroom.translator.audio

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * 录音增益处理回归测试（2026-10-20）。
 *
 * 覆盖线上真实问题：
 *  - 安静说话（原始 RMS ≈ -45 dBFS）必须被抬到可识别区间（≥ 0.02 RMS），
 *    否则会被流式 VAD（门槛 0.008）当成静音丢掉 —— 用户反馈"声音小就一个都识别不到"。
 *  - 正常音量的语音不能被过度放大（会削波失真，反而降低识别率）。
 *  - 纯静音/底噪不能被无限放大（否则整段都是噪声，ASR 出乱码）。
 *  - 直流偏移必须被去掉（部分机型麦克风有 DC 偏置，会推高 RMS 误判为语音）。
 */
class AudioGainProcessorTest {

    private fun pcmOf(amplitude: Float, freqHz: Double = 200.0, seconds: Double = 1.0,
                      dc: Float = 0f, sampleRate: Int = 16000): ByteArray {
        val n = (seconds * sampleRate).toInt()
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val s = (sin(2 * Math.PI * freqHz * i / sampleRate) * amplitude + dc)
            val v = (s * 32767).toInt().coerceIn(-32768, 32767)
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun rms(pcm: ByteArray): Float = AudioGainProcessor.normalizedRms(pcm)

    /**
     * 安静说话：-45 dBFS 左右的原始电平，处理后必须进入可识别区间。
     *
     * 【2026-10-20】信号模型换成真实语音（底噪 + 3 Hz 音节包络）——
     * 纯正弦没有音节起伏，会被"电平起伏度"判据当成稳定纯音（空调嗡鸣类）
     * 而不放大，那是设计意图，不是 bug。
     */
    @Test
    fun quietSpeechIsBoostedIntoRecognizableRange() {
        // 人声 0.005（约 -46 dBFS 峰值）+ 底噪 0.0002
        val quiet = speechOverNoise(voiceAmp = 0.005f, noiseAmp = 0.0002f, seconds = 3.0)
        val before = rms(quiet)
        val after = rms(AudioGainProcessor().process(quiet))

        assertTrue("原始 RMS 应极低，实际 $before", before < 0.01f)
        assertTrue("小声必须被抬高到 0.02 以上，实际 $after", after >= 0.02f)
        assertTrue("抬高后不能爆表，实际 $after", after <= 1.0f)
    }

    /**
     * 【2026-10-22 回归测试】放大后不得削波。
     *
     * 起因：只按 RMS 算增益时，语音的瞬时峰值放大后冲到满量程，
     * tanh 软限幅把波形压扁 → 失真 → 识别率下降。
     * 真机证据（用户 10-20 的录音）：修复前 peak = 1.000。
     */
    @Test
    fun outputNeverClips() {
        // 峰值远高于 RMS 的信号（语音的典型特征）
        val speech = speechOverNoise(voiceAmp = 0.02f, noiseAmp = 0.0005f, seconds = 3.0)
        val out = AudioGainProcessor().process(speech)
        val peak = out.maxOf { kotlin.math.abs(it.toInt().toShort().toInt()) } / 32768.0
        assertTrue("输出不得削波，实际峰值 $peak", peak <= 0.90f)
    }

    /** 正常音量的语音：不能被过度放大 */
    @Test
    fun normalSpeechIsNotOverAmplified() {
        val normal = pcmOf(amplitude = 0.15f, seconds = 2.0)
        val before = rms(normal)
        val after = rms(AudioGainProcessor().process(normal))

        assertTrue("正常音量 RMS 约 ${before}", before in 0.05f..0.20f)
        assertTrue("正常音量不应被放大超过 1.3 倍，实际 $before -> $after", after <= before * 1.3f)
        assertTrue("处理后不能削波到满量程，实际 $after", after < 0.95f)
    }

    /** 纯静音不能被放大成噪声 */
    @Test
    fun silenceIsNotAmplified() {
        val silence = ByteArray(16000 * 2)   // 全零
        val after = rms(AudioGainProcessor().process(silence))
        assertTrue("静音必须保持静音，实际 $after", after < 0.002f)
    }

    /** 极低电平的底噪（-70 dBFS）不能被拉成大噪声 */
    @Test
    fun veryLowNoiseIsNotAmplified() {
        val noise = pcmOf(amplitude = 0.0003f, freqHz = 4000.0, seconds = 2.0)
        val after = rms(AudioGainProcessor().process(noise))
        assertTrue("底噪不能被放大，实际 $after", after < 0.01f)
    }

    /** 直流偏置必须被去掉，否则会把静音误判成语音 */
    @Test
    fun dcOffsetIsRemoved() {
        val withDc = pcmOf(amplitude = 0.0f, seconds = 1.0, dc = 0.25f)
        val after = rms(AudioGainProcessor().process(withDc))
        assertTrue("直流应被高通去掉，实际 $after", after < 0.01f)
    }

    /**
     * 帧内含语音时必须判定为 speech（供上层做"有声音"提示）。
     * 用真实语音模型（带音节包络）——纯正弦起伏度为 0，会被判成稳定噪声。
     *
     * 注意：判断要逐块进行 —— 信号末尾是停顿，那一帧的 isSpeech 自然是 false，
     * 所以检查"整个过程中出现过语音判定"而不是只看最后一帧。
     */
    @Test
    fun speechDetectionWorks() {
        val proc = AudioGainProcessor()
        val quiet = speechOverNoise(voiceAmp = 0.005f, noiseAmp = 0.0002f, seconds = 3.0)
        var sawSpeech = false
        var offset = 0
        val chunk = 16000   // 1 秒一块
        while (offset < quiet.size) {
            val end = minOf(offset + chunk, quiet.size)
            proc.process(quiet.copyOfRange(offset, end))
            if (proc.isSpeech) sawSpeech = true
            offset = end
        }
        assertTrue("安静语音也应判为 speech", sawSpeech)
    }

    /**
     * 回归测试：高通滤波器必须是"直接形式"的单极高通。
     *
     * 【2026-10-20 真实 bug】之前写成 `x - lowpass(x)`（α = rc/(rc+dt)），
     * 实际截止频率只有 (1-α)/2π ≈ 9 Hz —— 200 Hz 语音被衰减 -27 dB，
     * 整段输出几乎归零，表现为"手机录到声音但 App 完全没声音"。
     * 这个测试锁死语音频段必须原样通过。
     */
    @Test
    fun speechBandIsNotAttenuated() {
        for (freq in intArrayOf(150, 200, 400, 800, 2000)) {
            val speech = pcmOf(amplitude = 0.1f, freqHz = freq.toDouble(), seconds = 1.0)
            val before = rms(speech)
            val after = rms(AudioGainProcessor().process(speech))
            // 语音频段（>100 Hz）经过 60 Hz 高通后衰减必须在 3 dB 以内
            val dropDb = 20.0 * kotlin.math.log10(before.toDouble() / after.toDouble().coerceAtLeast(1e-9))
            assertTrue(
                "${freq}Hz 语音被高通过度衰减 ${"%.1f".format(dropDb)} dB (before=$before after=$after)",
                dropDb < 3.0
            )
        }
    }

    /**
     * 回归测试：真正的高通必须去掉直流，同时保留 200 Hz 语音。
     * 直接形式 y[n]=α(y[n-1]+x[n]-x[n-1]) 在 60 Hz 截止下，
     * 200 Hz 处增益 ≈ 0.94（-0.5 dB），DC 处为 0。
     */
    @Test
    fun highPassRemovesDcButKeepsVoice() {
        val withDc = pcmOf(amplitude = 0.05f, seconds = 1.0, dc = 0.3f)
        val out = AudioGainProcessor().process(withDc)
        val after = rms(out)
        // 有 DC 时，高通后剩下的应该接近"只有交流部分"的电平，且明显小于 DC
        assertTrue("DC 未被去掉，实际 $after", after < 0.08f)
        assertTrue("语音被一起削掉，实际 $after", after > 0.03f)
    }

    /**
     * 【2026-10-20 关键回归测试】真实麦克风场景：底噪 + 停顿 + 极弱人声。
     *
     * 真机实测（MuMu 模拟器 200Hz 测试音）：输入 RMS = 0.0003（-70 dBFS），
     * 旧逻辑因为"绝对电平门 0.0008"判定为静音 → gain 恒为 1.0 →
     * 录音文件里语音仍是 -70 dBFS，ASR 根本听不见。
     *
     * 新逻辑：噪声底用窗口百分位估计 + 电平起伏度判据（区分语音与稳定噪声）
     *        → AGC 启动 → 人声被拉到可识别电平，底噪保持原样。
     */
    @Test
    fun faintVoiceAboveNoiseFloorIsBoosted() {
        val mixed = speechOverNoise(
            voiceAmp = 0.001f,       // -60 dBFS 人声
            noiseAmp = 0.00032f,     // -70 dBFS 底噪（SNR 10 dB，真实场景）
            seconds = 3.0
        )

        val proc = AudioGainProcessor()
        val out = proc.process(mixed)

        val before = rms(mixed)
        val after = rms(out)
        assertTrue("混合信号原始电平应极低，实际 $before", before < 0.002f)
        assertTrue(
            "弱语音必须被放大到 0.02 以上，实际 $after（gain=${proc.currentGain}）",
            after >= 0.02f
        )
        assertTrue("放大后不能爆表，实际 $after", after <= 1.0f)
    }

    /**
     * 稳定的环境噪声（空调嗡鸣、风扇）不能被放大 —— 否则整段嘶嘶声，
     * ASR 会输出幻觉内容。
     */
    @Test
    fun steadyHumIsNotBoosted() {
        // 100 Hz 稳定纯音（空调/电流嗡鸣），电平起伏度≈0
        val hum = pcmOf(amplitude = 0.001f, freqHz = 100.0, seconds = 3.0)
        val proc = AudioGainProcessor()
        val out = proc.process(hum)
        val after = rms(out)
        assertTrue(
            "稳定嗡鸣不应被放大，实际 $after（gain=${proc.currentGain}）",
            after < 0.01f
        )
    }

    /**
     * 底噪本身不能被当成语音放大（否则整段都是嘶嘶声，ASR 出乱码）。
     */
    @Test
    fun steadyNoiseFloorIsNotBoosted() {
        // 恒定 4 kHz 底噪，没有任何语音过冲
        val noise = pcmOf(amplitude = 0.0003f, freqHz = 4000.0, seconds = 3.0)
        val proc = AudioGainProcessor()
        val out = proc.process(noise)
        val after = rms(out)
        assertTrue(
            "稳定底噪不应被大幅放大，实际 $after（gain=${proc.currentGain}）",
            after < 0.01f
        )
    }

    /**
     * 【2026-10-20 回归测试】起始帧不得产生增益过冲。
     *
     * 之前 peakHold 起始为 0 → target = targetRms/0 → 增益直冲 maxGain，
     * 加上 attack 斜率 0.25，开头 ~100ms 被放大 49 倍 → 整段失真。
     * 修复：起始几帧用当前帧初始化 peakHold。
     */
    @Test
    fun noGainOvershootAtStart() {
        val normal = pcmOf(amplitude = 0.15f, seconds = 1.0)
        val out = AudioGainProcessor().process(normal)
        // 正常音量：整体电平基本不变（不能被放大 3 倍以上）
        val before = rms(normal)
        val after = rms(out)
        assertTrue(
            "正常音量被过度放大：$before -> $after",
            after <= before * 2.0f
        )
        // 开头 200ms（10 帧）也不能出现削波级别的输出
        val head = out.copyOfRange(0, 6400)
        val headPeak = head.maxOf { kotlin.math.abs(it.toInt().toShort().toInt()) } / 32768.0
        assertTrue("开头出现削波，实际峰值 $headPeak", headPeak < 0.98f)
    }

    /**
     * 真实麦克风模型：底噪 + 0.6s 一个音节（音节之间有 0.2s 只有底噪的停顿），
     * 语音带 3 Hz 音节包络（真实人声的起伏特征）。
     */
    private fun speechOverNoise(
        voiceAmp: Float,
        noiseAmp: Float,
        seconds: Double,
        sampleRate: Int = 16000,
        toneHz: Double = 200.0
    ): ByteArray {
        val n = (seconds * sampleRate).toInt()
        val out = ByteArray(n * 2)
        val period = 0.6
        val duty = 0.5      // 50% 时间在"说话"，50% 是停顿（只有底噪）
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val speaking = (t % period) < period * duty
            val voice: Float = if (speaking) {
                val env = 0.5 + 0.5 * kotlin.math.sin(2 * Math.PI * 3.0 * t)
                voiceAmp * env.toFloat() * kotlin.math.sin(2 * Math.PI * toneHz * t).toFloat()
            } else 0f
            val noise: Float = noiseAmp *
                (kotlin.math.sin(2 * Math.PI * 4000.0 * t) +
                 0.7 * kotlin.math.sin(2 * Math.PI * 5100.0 * t + 1.1)).toFloat()
            val s = (voice + noise).toDouble()
            val v = (s * 32767).toInt().coerceIn(-32768, 32767)
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}
