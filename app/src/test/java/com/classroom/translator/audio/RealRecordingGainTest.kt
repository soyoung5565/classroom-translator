package com.classroom.translator.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 真机录音回归：验证 v1.4.3 的增益链在**用户真实录音**上的表现。
 *
 * 数据：用户 2026-10-20 的真机录音（从设备 pull 下来的 recording_20261020_205230.wav）。
 * 该录音是"手机麦克风录到但识别不出"的现场素材，里面既有很轻的说话段（-43 dBFS），
 * 也有已经足够响的段落（-19 dBFS）——正好覆盖"该放大"和"不该放大"两类输入。
 *
 * 断言（对齐 AudioGainProcessor 的设计意图）：
 *  1. **轻语音必须被抬起来**：原始电平低于 -35 dBFS 的 1 秒窗口，
 *     处理后至少提升 +6 dB（否则 ASR 的 VAD 仍会把它当静音丢掉）；
 *  2. **不得削波**：输出峰值 <= 0.95（tanh 软限幅的线性区内）；
 *  3. **已经很响的语音不被继续放大**：原始高于 -25 dBFS 的窗口，
 *     处理后电平不得再涨 +6 dB 以上（否则削波失真、识别率反而下降）；
 *  4. **数字静音不被放大成"语音"**：输入 RMS 低于 -80 dBFS 的窗口，
 *     处理后仍须低于 ASR 的 VOICE_RMS 阈（0.012）。
 *
 * 注：不要用"能量最高的那 1 秒"当说话段来断言"必须变大"——最高能量段本来就是
 * 已经够响的部分，AGC 按设计**不会**再放大它。2026-10-23 的旧版测试正是踩了这个坑。
 */
class RealRecordingGainTest {

    private val sampleRate = 16000

    /** 从 WAV 文件读 PCM（只处理 16bit 单声道） */
    private fun readWav(path: String): ShortArray {
        val f = File(path)
        if (!f.exists()) {
            println("SKIP: 找不到真机录音 $path")
            return ShortArray(0)
        }
        return readWavStream(f)
    }

    /** 从 classpath 资源读 WAV —— 测试夹具随代码走，不依赖本机绝对路径 */
    private fun readWavFromClasspath(resource: String): ShortArray {
        val stream = javaClass.classLoader?.getResourceAsStream(resource) ?: return ShortArray(0)
        return stream.use { input ->
            val tmp = File.createTempFile("fixture", ".wav")
            try {
                tmp.outputStream().use { input.copyTo(it) }
                readWavStream(tmp)
            } finally {
                tmp.delete()
            }
        }
    }

    private fun readWavStream(f: File): ShortArray {
        RandomAccessFile(f, "r").use { raf ->
            val riff = ByteArray(4); raf.read(riff)
            if (String(riff) != "RIFF") return ShortArray(0)
            raf.skipBytes(4) // RIFF size
            val wave = ByteArray(4); raf.read(wave)
            if (String(wave) != "WAVE") return ShortArray(0)

            var pos = 12
            while (pos + 8 <= f.length()) {
                raf.seek(pos.toLong())
                val chunkId = ByteArray(4); raf.read(chunkId)
                val sizeBuf = ByteArray(4); raf.read(sizeBuf)
                val chunkSize = ByteBuffer.wrap(sizeBuf).order(ByteOrder.LITTLE_ENDIAN).int
                val bodyStart = pos + 8
                if (String(chunkId) == "data") {
                    val avail = (f.length() - bodyStart).toInt()
                    val n = minOf(chunkSize, avail)
                    if (n <= 0) return ShortArray(0)
                    val data = ByteArray(n)
                    raf.read(data)
                    val out = ShortArray(n / 2)
                    val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in out.indices) out[i] = bb.short
                    return out
                }
                pos = bodyStart + chunkSize + (chunkSize and 1)
            }
        }
        return ShortArray(0)
    }

    private fun rms(a: ShortArray, from: Int, to: Int): Float {
        val end = minOf(to, a.size)
        if (end <= from) return 0f
        var s = 0.0
        for (i in from until end) s += a[i].toDouble() * a[i].toDouble()
        return (Math.sqrt(s / (end - from)) / 32768.0).toFloat()
    }

    private fun db(x: Float): Double = if (x <= 0f) -999.0 else 20 * Math.log10(x.toDouble())

    @Test
    fun realQuietSpeechIsBoostedAndLoudSpeechIsUntouched() {
        // 测试夹具：用户 2026-10-20 的真机录音（23.5 秒），直接从设备录音目录复制的原始 WAV。
        // 走 classpath 而不是绝对路径 —— 换机器/清缓存后依然可跑，回归不会静默失效。
        val pcm = readWavFromClasspath("fixtures/user_205230_quiet_speech.wav")
        if (pcm.isEmpty()) {
            throw AssertionError("测试夹具缺失：app/src/test/resources/fixtures/user_205230_quiet_speech.wav")
        }
        println("真机录音: ${pcm.size} 采样 = ${"%.1f".format(pcm.size.toDouble() / sampleRate)} 秒")

        // 逐帧过一遍增益链（与 AudioRecorder 的调用方式一致：20ms 一帧）
        val proc = AudioGainProcessor()
        val out = ShortArray(pcm.size)
        var off = 0
        while (off + AudioGainProcessor.FRAME_SAMPLES <= pcm.size) {
            val chunk = ShortArray(AudioGainProcessor.FRAME_SAMPLES)
            System.arraycopy(pcm, off, chunk, 0, chunk.size)
            val bytes = ByteArray(chunk.size * 2)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (s in chunk) bb.putShort(s)
            val processed = proc.process(bytes)
            val pb = ByteBuffer.wrap(processed).order(ByteOrder.LITTLE_ENDIAN)
            val n = minOf(AudioGainProcessor.FRAME_SAMPLES, out.size - off)
            for (i in 0 until n) out[off + i] = pb.getShort(i * 2)
            off += AudioGainProcessor.FRAME_SAMPLES
        }

        // 按 1 秒窗口统计
        val win = sampleRate
        var quietWindows = 0
        var quietBoosted = 0
        var loudWindows = 0
        var loudOverBoosted = 0
        var silentWindows = 0
        var silentLeaked = 0
        var peak = 0f
        for (v in out) { val x = Math.abs(v.toFloat()) / 32768f; if (x > peak) peak = x }

        var i = 0
        while (i + win <= pcm.size) {
            val before = rms(pcm, i, i + win)
            val after = rms(out, i, i + win)
            val bDb = db(before)
            val aDb = db(after)
            when {
                bDb < -80.0 -> {
                    silentWindows++
                    if (after >= 0.012f) silentLeaked++
                }
                bDb < -35.0 -> {
                    quietWindows++
                    if (aDb - bDb >= 6.0) quietBoosted++
                    println("  轻语音 t=%2ds  %6.1f -> %6.1f dBFS  (+${"%.1f".format(aDb - bDb)} dB)"
                        .format(i / sampleRate, bDb, aDb))
                }
                bDb > -25.0 -> {
                    loudWindows++
                    if (aDb - bDb >= 6.0) loudOverBoosted++
                }
            }
            i += win
        }
        println("统计：轻语音 $quietWindows 段 / 已提升 $quietBoosted；" +
            "响语音 $loudWindows 段 / 被过度放大 $loudOverBoosted；" +
            "数字静音 $silentWindows 段 / 漏放 $silentLeaked")
        println("输出峰值 peak = ${"%.3f".format(peak)}")

        // 1) 轻语音必须被抬起来（这是用户反馈的核心）
        assertTrue("录音里应有低于 -35 dBFS 的轻语音段", quietWindows > 0)
        assertTrue(
            "轻语音段必须被提升 >=6 dB（实际 $quietBoosted/$quietWindows 段）",
            quietBoosted >= quietWindows * 0.8
        )
        // 2) 不得削波
        assertTrue("不得削波，实际 peak=$peak", peak <= 0.95f)
        // 3) 已经很响的语音不该再被放大
        assertTrue(
            "响语音不得再被放大 >=6 dB（实际 $loudOverBoosted/$loudWindows 段）",
            loudOverBoosted == 0
        )
        // 4) 数字静音不得被放大成"语音"
        assertTrue("数字静音被放大到语音阈值以上（$silentLeaked 段）", silentLeaked == 0)
    }
}
