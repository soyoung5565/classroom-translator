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
    }

    private var audioRecord: AudioRecord? = null
    private var readerThread: Thread? = null
    @Volatile private var isRecording = false
    private val bufferLock = Any()
    private val pcmBuffer = ByteArrayOutputStream()

    @Volatile var audioLevel: Float = 0f; private set

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
                    while (isRecording) {
                        val read = recorder.read(readBuf, 0, readBuf.size)
                        if (read > 0) {
                            synchronized(bufferLock) {
                                pcmBuffer.write(readBuf, 0, read)
                            }
                            audioLevel = calculateRms(readBuf, read)
                            // 回调给 LocalVllmAsrStream 等消费方
                            try { pcmListener?.invoke(readBuf.copyOf(read)) } catch (e: Exception) {}
                            // Save to file
                            try { audioFileOutputStream?.write(readBuf, 0, read) } catch(e: Exception) {}
                        } else if (read < 0) {
                            Log.e(TAG, "AudioRecord.read returned $read")
                            break
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
        try {
            readerThread?.join(2000)
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
