package com.classroom.translator.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 内录（系统声音捕获，测试模式专用）。
 * 用 MediaProjection + AudioPlaybackCapture 抓取系统正在播放的声音，
 * 输出与 AudioRecorder 完全一致的 16kHz mono PCM 16-bit 流（走同一 pcmListener 管道）。
 *
 * 设计原则：麦克风路径（AudioRecorder）完全不动；本类只是测试模式下替代音频源。
 * 用途：播放测试素材（MediaPlayer 输出走媒体流）→ 内录 → 喂流式 ASR，声音无需外放，
 *       音量调到最小也不影响捕获质量，不打扰周围。
 */
class PlaybackCaptureRecorder(private val context: Context) {

    companion object {
        private const val TAG = "PlaybackCapture"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var readerThread: Thread? = null
    @Volatile private var isCapturing = false
    @Volatile var audioLevel: Float = 0f; private set
    @Volatile private var pcmListener: ((ByteArray) -> Unit)? = null

    fun setPcmListener(listener: ((ByteArray) -> Unit)?) { pcmListener = listener }

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= 29

    /**
     * 开始内录。必须先在 Activity 拿到 MediaProjection 授权（createScreenCaptureIntent）。
     */
    @SuppressLint("WrongConstant")
    fun start(mediaProjection: MediaProjection) {
        if (!isSupported) {
            Log.e(TAG, "AudioPlaybackCapture requires API 29+, got ${Build.VERSION.SDK_INT}")
            return
        }
        if (isCapturing) return

        try {
            val capConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                // 只抓媒体播放的声音（测试素材 MediaPlayer 走 USAGE_MEDIA）
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .build()

            val bufferSize = maxOf(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
                SAMPLE_RATE * 2
            )
            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(capConfig)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()

            audioRecord?.let { recorder ->
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "PlaybackCapture AudioRecord init failed")
                    recorder.release()
                    audioRecord = null
                    return
                }
                recorder.startRecording()
                isCapturing = true
                Log.i(TAG, "Playback capture started (16k mono)")

                readerThread = Thread({
                    val readBuf = ByteArray(SAMPLE_RATE)
                    while (isCapturing) {
                        val read = recorder.read(readBuf, 0, readBuf.size)
                        if (read > 0) {
                            audioLevel = calculateRms(readBuf, read)
                            try { pcmListener?.invoke(readBuf.copyOf(read)) } catch (_: Exception) {}
                        } else if (read < 0) {
                            Log.e(TAG, "PlaybackCapture read error: $read")
                            break
                        }
                    }
                    Log.i(TAG, "Playback capture reader stopped")
                }, "PlaybackCaptureReader").also { it.start() }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "PlaybackCapture SecurityException (授权未通过?)", e)
        } catch (e: Exception) {
            Log.e(TAG, "PlaybackCapture start failed", e)
        }
    }

    fun stop() {
        isCapturing = false
        pcmListener = null
        audioLevel = 0f
        try {
            readerThread?.join(2000)
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        readerThread = null
    }

    fun isCapturing(): Boolean = isCapturing

    private fun calculateRms(buffer: ByteArray, length: Int): Float {
        if (length < 2) return 0f
        var sum = 0.0
        val shorts = ShortArray(length / 2)
        java.nio.ByteBuffer.wrap(buffer, 0, length)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shorts)
        for (s in shorts) sum += s.toDouble() * s.toDouble()
        val rms = sqrt(sum / shorts.size)
        return (rms / 32768.0).coerceIn(0.0, 1.0).toFloat()
    }
}
