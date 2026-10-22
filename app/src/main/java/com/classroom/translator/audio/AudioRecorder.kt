package com.classroom.translator.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Records audio from microphone in 16kHz mono PCM 16-bit format.
 * Uses a background thread to continuously read audio data.
 */
class AudioRecorder(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        // pcmBuffer 只被 flush()（DashScope/MIMO 分段模式）消费；
        // 流式模式（local_vllm）无人 flush，不设上限会无限膨胀（32KB/s）
        // -> GC 压力 -> 发热。WAV 存档走独立文件流，清缓冲不丢录音。
        private const val MAX_PCM_BUFFER_BYTES = 2 * 1024 * 1024  // ~64s 音频
        /** 连续多少次 read() 返回 0（无声）后判定麦克风异常 */
        private const val SILENT_READ_THRESHOLD = 20   // 20 * 0.5s = 10s
    }

    private var audioRecord: AudioRecord? = null
    private var readerThread: Thread? = null
    @Volatile private var isRecording = false
    private val bufferLock = Any()
    private val pcmBuffer = ByteArrayOutputStream()

    /** 增益处理链（AGC + 高通 + 软限幅）：小声也能被 ASR 识别 */
    private val gainProcessor = AudioGainProcessor(SAMPLE_RATE)

    @Volatile var audioLevel: Float = 0f; private set
    /** 原始（未经增益）输入电平 —— 用于判断"麦克风到底有没有收到声音" */
    @Volatile var rawAudioLevel: Float = 0f; private set
    /** 当前 AGC 增益倍数（诊断用：增益很高说明输入电平低） */
    val currentGain: Float get() = gainProcessor.currentGain
    /** 麦克风异常回调（被占用 / 读失败）—— UI 需要提示用户 */
    @Volatile var onMicError: ((String) -> Unit)? = null

    /** 每次从麦克风读出 PCM 块后回调（用于 LocalVllmAsrStream 实时喂音频） */
    @Volatile private var pcmListener: ((ByteArray) -> Unit)? = null
    fun setPcmListener(listener: ((ByteArray) -> Unit)?) { pcmListener = listener }
    private var audioFilePath: String? = null
    private var audioFileOutputStream: FileOutputStream? = null

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (isRecording) return
        if (!hasPermission) {
            Log.e(TAG, "No RECORD_AUDIO permission")
            return
        }
        // 【2026-10-22 修复】先清空上一次的录音路径。
        // 若本次 start() 早退（AudioRecord 初始化失败 / 麦克风被占用），
        // audioFilePath 会**残留上一次的路径** → stopRecording() 拿到旧路径 →
        // 新会话的 session JSON 指向了上一段录音（回放出来是另一段内容）。
        // 现在：每次 start 都从 null 开始，失败就是 null（= 本次没有录音文件）。
        audioFilePath = null

        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
            SAMPLE_RATE * 2
        )

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            ).also { recorder ->
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize")
                    recorder.release()
                    return
                }
                synchronized(bufferLock) {
                    pcmBuffer.reset()
                }
                recorder.startRecording()
                isRecording = true
                gainProcessor.reset()
                rawAudioLevel = 0f
                // Create audio file for saving
                try {
                    val dir = File(context.getExternalFilesDir(null), "recordings")
                    dir.mkdirs()
                    val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                    val file = File(dir, "recording_$timestamp.wav")
                    audioFilePath = file.absolutePath
                    // Write WAV header (will update data size later)
                    audioFileOutputStream = FileOutputStream(file)
                    val header = createWavHeader(0)
                    audioFileOutputStream?.write(header)
                    Log.i(TAG, "Audio saving to: ${file.absolutePath}")
                } catch (e: Exception) { Log.e(TAG, "Failed to create audio file", e) }
                Log.i(TAG, "Recording started: ${SAMPLE_RATE}Hz mono 16-bit")

                // Start background reader thread
                readerThread = Thread({
                    val readBuf = ByteArray(SAMPLE_RATE) // 0.5s buffer
                    var silentReads = 0
                    // 当前使用的 AudioRecord（出错时可替换为重建的实例）
                    var rec: AudioRecord = recorder
                    while (isRecording) {
                        val read = try {
                            rec.read(readBuf, 0, readBuf.size)
                        } catch (e: Exception) {
                            Log.e(TAG, "AudioRecord.read threw", e)
                            -1
                        }
                        if (read > 0) {
                            silentReads = 0
                            // 增益处理：小声被抬到可识别电平；高通去直流；软限幅防削波
                            val processed = gainProcessor.process(readBuf.copyOf(read))
                            // 原始电平（未增益）—— 用来判断麦克风是否真的收到了声音
                            rawAudioLevel = AudioGainProcessor.normalizedRms(readBuf, read)
                            audioLevel = AudioGainProcessor.normalizedRms(processed)
                            synchronized(bufferLock) {
                                pcmBuffer.write(processed, 0, read)
                                // 上限保护：流式模式无人 flush()，无上限会无限膨胀（32KB/s）
                                // -> GC 压力 -> 发热。超限时丢弃最旧的数据。
                                if (pcmBuffer.size() > MAX_PCM_BUFFER_BYTES) {
                                    val excess = pcmBuffer.size() - MAX_PCM_BUFFER_BYTES
                                    val keep = pcmBuffer.toByteArray()
                                    pcmBuffer.reset()
                                    pcmBuffer.write(keep, excess, keep.size - excess)
                                    Log.w(TAG, "pcmBuffer overflow, dropped $excess bytes")
                                }
                            }
                            // 回调给 LocalVllmAsrStream 等消费方
                            try { pcmListener?.invoke(processed) } catch (e: Exception) {}
                            // Save to file
                            try { audioFileOutputStream?.write(processed, 0, read) } catch (e: Exception) {}
                        } else if (read < 0) {
                            // 麦克风被其它 App 占用（ERROR_DEAD_OBJECT=-6）或读失败。
                            // 【2026-10-20 修复】原先直接 break 退出读线程 → 录音线程死掉，
                            // 但 UI 仍显示"正在听..."，用户完全不知道发生了什么（"完全没有声音"）。
                            // 现在：报告错误 + 重建 AudioRecord 继续录。
                            Log.e(TAG, "AudioRecord.read returned $read")
                            onMicError?.invoke("麦克风读取失败（可能被其它应用占用），正在尝试恢复")
                            try { rec.stop(); rec.release() } catch (e: Exception) { }
                            val recreated = try {
                                AudioRecord(
                                    MediaRecorder.AudioSource.MIC,
                                    SAMPLE_RATE,
                                    CHANNEL_CONFIG,
                                    AUDIO_FORMAT,
                                    maxOf(
                                        AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
                                        SAMPLE_RATE * 2
                                    )
                                ).also {
                                    if (it.state == AudioRecord.STATE_INITIALIZED) it.startRecording() else it.release()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to recreate AudioRecord", e); null
                            }
                            if (recreated == null || recreated.state != AudioRecord.STATE_INITIALIZED) {
                                Log.e(TAG, "AudioRecord recovery failed, stopping capture")
                                onMicError?.invoke("麦克风无法恢复，请关闭占用麦克风的应用后重试")
                                break
                            }
                            audioRecord = recreated
                            rec = recreated
                            Log.i(TAG, "AudioRecord recreated, resuming capture")
                        } else {
                            // read == 0：读到 0 字节（罕见）。连续多次说明麦克风异常
                            silentReads++
                            if (silentReads >= SILENT_READ_THRESHOLD) {
                                Log.e(TAG, "AudioRecord.read returned 0 for $silentReads consecutive times")
                                onMicError?.invoke("麦克风持续无数据，请检查是否有应用占用")
                                silentReads = 0
                            }
                        }
                    }
                    Log.i(TAG, "Reader thread stopped")
                }, "AudioReader").also { it.start() }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: no permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
        }
    }

    /**
     * Flush and return all accumulated PCM data since last flush.
     */
    fun flush(): ByteArray? {
        synchronized(bufferLock) {
            val data = pcmBuffer.toByteArray()
            pcmBuffer.reset()
            return if (data.isEmpty()) null else data
        }
    }

    fun stop() {
        isRecording = false
        pcmListener = null
        // 【2026-10-22 修复】先等读线程退出，再关闭文件流 + 修 WAV 头。
        // 原顺序：close() → 改 header → join(读线程)。
        //   读线程此刻还在循环里，可能正在 write() → 撞上已关闭的流 → 抛异常被
        //   catch 吞掉 → **最后一段音频丢失**；而且 header 里的 dataSize 是在
        //   读线程可能还在写的时候算的 → 长度也对不上。
        // 现在：isRecording=false 让读线程自然退出 → join 等它真正结束 →
        //       再 close + 按最终文件长度回填 header。
        try { readerThread?.join(2000) } catch (e: Exception) { Log.e(TAG, "join reader", e) }
        // Close audio file and fix WAV header
        try {
            audioFileOutputStream?.close()
            audioFilePath?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    val dataSize = file.length() - 44
                    val header = createWavHeader(dataSize.toInt())
                    RandomAccessFile(file, "rw").use { it.write(header) }
                    Log.i(TAG, "Audio saved: $path (${dataSize} bytes)")
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Failed to finalize audio file", e) }
        audioFileOutputStream = null
        audioLevel = 0f
        rawAudioLevel = 0f
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recorder", e)
        }
        audioRecord = null
        readerThread = null
        synchronized(bufferLock) {
            pcmBuffer.reset()
        }
    }

    fun isRecording(): Boolean = isRecording

    private fun createWavHeader(dataSize: Int): ByteArray {
        val totalSize = 44 + dataSize
        val byteRate = SAMPLE_RATE * 1 * 16 / 8
        val header = ByteArray(44)
        System.arraycopy("RIFF".toByteArray(), 0, header, 0, 4)
        writeInt(header, 4, totalSize - 8)
        System.arraycopy("WAVE".toByteArray(), 0, header, 8, 4)
        System.arraycopy("fmt ".toByteArray(), 0, header, 12, 4)
        writeInt(header, 16, 16)
        writeShort(header, 20, 1)
        writeShort(header, 22, 1)
        writeInt(header, 24, SAMPLE_RATE)
        writeInt(header, 28, byteRate)
        writeShort(header, 32, 2)
        writeShort(header, 34, 16)
        System.arraycopy("data".toByteArray(), 0, header, 36, 4)
        writeInt(header, 40, dataSize)
        return header
    }
    private fun writeInt(arr: ByteArray, offset: Int, value: Int) {
        arr[offset] = (value and 0xFF).toByte()
        arr[offset+1] = ((value shr 8) and 0xFF).toByte()
        arr[offset+2] = ((value shr 16) and 0xFF).toByte()
        arr[offset+3] = ((value shr 24) and 0xFF).toByte()
    }
    private fun writeShort(arr: ByteArray, offset: Int, value: Int) {
        arr[offset] = (value and 0xFF).toByte()
        arr[offset+1] = ((value shr 8) and 0xFF).toByte()
    }

    fun getAudioFilePath(): String? = audioFilePath

    private fun calculateRms(buffer: ByteArray, length: Int): Float {
        var sum = 0.0
        val shorts = ShortArray(length / 2)
        ByteBuffer.wrap(buffer, 0, length)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shorts)
        for (s in shorts) {
            sum += s.toDouble() * s.toDouble()
        }
        val rms = sqrt(sum / shorts.size)
        return (rms / 32768.0).coerceIn(0.0, 1.0).toFloat()
    }
}
