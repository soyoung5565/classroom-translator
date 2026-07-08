package com.classroom.translator.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes raw PCM data to WAV format for API submission.
 */
object WavEncoder {

    fun encode(pcmData: ByteArray, sampleRate: Int = 16000, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val totalSize = 44 + dataSize

        val out = ByteArrayOutputStream(totalSize)
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        out.write("RIFF".toByteArray())
        buf.putInt(totalSize - 8); out.write(buf.array()); buf.clear()
        out.write("WAVE".toByteArray())

        // fmt chunk
        out.write("fmt ".toByteArray())
        buf.putInt(16); out.write(buf.array()); buf.clear()       // chunk size
        buf.putShort(1); out.write(buf.array().sliceArray(0..1)); buf.clear()  // PCM format
        buf.putShort(channels.toShort()); out.write(buf.array().sliceArray(0..1)); buf.clear()
        buf.putInt(sampleRate); out.write(buf.array()); buf.clear()
        buf.putInt(byteRate); out.write(buf.array()); buf.clear()
        buf.putShort(blockAlign.toShort()); out.write(buf.array().sliceArray(0..1)); buf.clear()
        buf.putShort(bitsPerSample.toShort()); out.write(buf.array().sliceArray(0..1)); buf.clear()

        // data chunk
        out.write("data".toByteArray())
        buf.putInt(dataSize); out.write(buf.array()); buf.clear()
        out.write(pcmData)

        return out.toByteArray()
    }
}
