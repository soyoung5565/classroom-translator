package com.classroom.translator.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts audio files (M4A, AAC, etc.) to WAV format using Android's MediaCodec.
 */
object AudioConverter {

    private const val TAG = "AudioConverter"
    private const val TARGET_SAMPLE_RATE = 16000
    private const val TARGET_CHANNELS = 1

    /**
     * Convert any audio file to 16kHz mono WAV bytes.
     * Supports: M4A, AAC, MP3, OGG, FLAC, etc.
     */
    fun convertToWav(context: Context, inputBytes: ByteArray, fileName: String): ByteArray? {
        // Write input to temp file
        val tempIn = File(context.cacheDir, "audio_in_${System.currentTimeMillis()}")
        val tempOut = File(context.cacheDir, "audio_out_${System.currentTimeMillis()}.wav")
        try {
            tempIn.writeBytes(inputBytes)
            return convertFileToWav(tempIn)
        } catch (e: Exception) {
            Log.e(TAG, "Conversion failed: ${e.message}", e)
            return null
        } finally {
            tempIn.delete()
            tempOut.delete()
        }
    }

    /**
     * Sniff audio format from magic bytes (not the file name).
     * OpenDocument URIs often have no extension in lastPathSegment,
     * so a real .wav would be misrouted into MediaCodec re-encode
     * (which truncates long files on some devices). Returns
     * "wav"/"mp3" or null if unknown.
     */
    fun sniffFormat(bytes: ByteArray): String? {
        if (bytes.size >= 12 &&
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x41.toByte() &&
            bytes[10] == 0x56.toByte() && bytes[11] == 0x45.toByte()) return "wav"
        if (bytes.size >= 3 &&
            bytes[0] == 0x49.toByte() && bytes[1] == 0x44.toByte() &&
            bytes[2] == 0x33.toByte()) return "mp3"
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() &&
            (bytes[1].toInt() and 0xE0) == 0xE0) return "mp3"
        return null
    }

    /**
     * Check if file needs conversion (not WAV or MP3)
     */
    fun needsConversion(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.').lowercase()
        return ext !in listOf("wav", "mp3")
    }

    private fun convertFileToWav(inputFile: File): ByteArray? {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputFile.absolutePath)

        // Find audio track
        val trackIndex = findAudioTrack(extractor) ?: return null
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null

        Log.i(TAG, "Input: $mime, ${sampleRate}Hz, ${channelCount}ch")

        // Create decoder
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val pcmOutput = ByteArrayOutputStream()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val timeoutUs = 10000L

        while (!outputDone) {
            // Feed input
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            // Read output
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (outputIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                val chunk = ByteArray(bufferInfo.size)
                outputBuffer.get(chunk)
                pcmOutput.write(chunk)
                codec.releaseOutputBuffer(outputIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                }
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        val pcmData = pcmOutput.toByteArray()
        Log.i(TAG, "Decoded: ${pcmData.size} bytes PCM at ${sampleRate}Hz")

        // Resample to 16kHz mono if needed
        val resampled = if (sampleRate != TARGET_SAMPLE_RATE || channelCount != TARGET_CHANNELS) {
            Log.i(TAG, "Resampling ${sampleRate}Hz->${TARGET_SAMPLE_RATE}Hz, ${channelCount}ch->${TARGET_CHANNELS}ch")
            resamplePcm(pcmData, sampleRate, channelCount)
        } else {
            pcmData
        }

        return WavEncoder.encode(resampled, TARGET_SAMPLE_RATE, TARGET_CHANNELS, 16)
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    private fun resamplePcm(input: ByteArray, srcSampleRate: Int, srcChannels: Int): ByteArray {
        // Convert to short samples
        val shorts = ShortArray(input.size / 2)
        ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)

        // Mix to mono if stereo
        val mono = if (srcChannels > 1) {
            ShortArray(shorts.size / srcChannels) { i ->
                var sum = 0L
                for (ch in 0 until srcChannels) {
                    sum += shorts[i * srcChannels + ch]
                }
                (sum / srcChannels).toShort()
            }
        } else shorts

        // Resample to target sample rate
        if (srcSampleRate == TARGET_SAMPLE_RATE) {
            val output = ByteArray(mono.size * 2)
            ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(mono)
            return output
        }

        val ratio = srcSampleRate.toDouble() / TARGET_SAMPLE_RATE
        val outputLength = (mono.size / ratio).toInt()
        val output = ShortArray(outputLength)
        for (i in 0 until outputLength) {
            val srcIndex = (i * ratio).toInt().coerceIn(0, mono.size - 1)
            output[i] = mono[srcIndex]
        }

        val outputBytes = ByteArray(output.size * 2)
        ByteBuffer.wrap(outputBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(output)
        return outputBytes
    }
}
