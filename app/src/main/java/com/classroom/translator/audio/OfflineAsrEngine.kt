package com.classroom.translator.audio

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineFunAsrNanoModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device ASR via sherpa-onnx (P2, offline mode).
 * Models live in /sdcard/models/<kind>/, copied once to app filesDir.
 * kinds: whisper-small (ms/en instances), funasr-nano (challenger, files TBD).
 */
object OfflineAsrEngine {
    private const val TAG = "OfflineAsr"
    private val lock = Any()
    private var whisperMs: OfflineRecognizer? = null
    private var whisperEn: OfflineRecognizer? = null

    data class Result(val text: String, val elapsedMs: Long, val audioSec: Float)

    fun appModelsDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "models")

    private fun needFiles(kind: String): Array<String> = when (kind) {
        "funasr-nano" -> arrayOf("encoder_adaptor.int8.onnx", "llm.int8.onnx", "embedding.int8.onnx",
            "Qwen3-0.6B/tokenizer.json", "Qwen3-0.6B/vocab.json", "Qwen3-0.6B/merges.txt")
        "qwen3-asr" -> arrayOf("conv_frontend.onnx", "encoder.int8.onnx", "decoder.int8.onnx",
            "tokenizer/vocab.json", "tokenizer/merges.txt", "tokenizer/tokenizer_config.json")
        else -> arrayOf("small-encoder.int8.onnx", "small-decoder.int8.onnx", "small-tokens.txt")
    }

    private fun stageDir(ctx: Context, kind: String): File {
        val dst = File(ctx.filesDir, "offline/" + kind)
        if (!File(dst, ".ready2").exists() || !needFiles(kind).all { File(dst, it).exists() }) {
            File(dst, ".ready").delete()
            File(dst, ".ready2").delete()
            dst.mkdirs()
            val src = File("/sdcard/models/" + kind)
            var ok = true
            fun copyTree(from: java.io.File, to: java.io.File) {
                if (from.isDirectory) {
                    to.mkdirs()
                    for (c in from.listFiles() ?: emptyArray()) copyTree(c, java.io.File(to, c.name))
                } else {
                    try { from.copyTo(to, overwrite = true) } catch (e: Exception) { Log.e(TAG, "copy failed " + from.name, e); ok = false }
                }
            }
            copyTree(src, dst)
            var haveAll = needFiles(kind).all { File(dst, it).exists() }
            if (ok && haveAll) File(dst, ".ready2").createNewFile()
        }
        return dst
    }

    private fun whisper(
        ctx: Context, lang: String, numThreads: Int = 4
    ): OfflineRecognizer {
        val d = stageDir(ctx, "whisper-small")
        val enc = File(d, "small-encoder.int8.onnx").absolutePath
        val dec = File(d, "small-decoder.int8.onnx").absolutePath
        val tok = File(d, "small-tokens.txt").absolutePath
        val w = OfflineWhisperModelConfig(
            encoder = enc, decoder = dec, language = lang, task = "transcribe"
        )
        val model = OfflineModelConfig(whisper = w, tokens = tok, numThreads = numThreads)
        val cfg = OfflineRecognizerConfig(
            featConfig = FeatureConfig(), modelConfig = model
        )
        return OfflineRecognizer(null, cfg)
    }

    fun recognizer(ctx: Context, kind: String, lang: String): OfflineRecognizer {
        synchronized(lock) {
            if (kind == "qwen3-asr") {
                val d = stageDir(ctx, "qwen3-asr")
                return Qwen3AsrBridge.create(
                    File(d, "conv_frontend.onnx").absolutePath,
                    File(d, "encoder.int8.onnx").absolutePath,
                    File(d, "decoder.int8.onnx").absolutePath,
                    File(d, "tokenizer").absolutePath,
                    4, 200
                )
            }
            if (kind == "funasr-nano") {
                val d = stageDir(ctx, "funasr-nano")
                val f = OfflineFunAsrNanoModelConfig(
                    encoderAdaptor = File(d, "encoder_adaptor.int8.onnx").absolutePath,
                    llm = File(d, "llm.int8.onnx").absolutePath,
                    embedding = File(d, "embedding.int8.onnx").absolutePath,
                    tokenizer = File(d, "Qwen3-0.6B").absolutePath,
                    maxNewTokens = 512
                )
                val m = OfflineModelConfig(funasrNano = f, modelType = "funasr_nano", numThreads = 4)
                return OfflineRecognizer(null, OfflineRecognizerConfig(featConfig = FeatureConfig(), modelConfig = m))
            }
            return if (lang == "en") {
                if (whisperEn == null) whisperEn = whisper(ctx, "en")
                whisperEn!!
            } else {
                if (whisperMs == null) whisperMs = whisper(ctx, "ms")
                whisperMs!!
            }
        }
    }

    /** Parse 16kHz mono PCM16 WAV bytes to float samples. Returns null if not compliant. */
    fun wavToFloat(wav: ByteArray): FloatArray? {
        try {
            if (wav.size < 44) return null
            val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
            if (bb.get(0) != 0x52.toByte() || bb.get(8) != 0x57.toByte()) return null // RIFF....WAVE
            var pos = 12
            var audioFmt = 1; var channels = 1; var sampleRate = 16000
            var dataOff = -1; var dataLen = 0
            while (pos + 8 <= wav.size) {
                val id = String(wav, pos, 4, Charsets.US_ASCII)
                val sz = bb.getInt(pos + 4)
                if (id == "fmt ") {
                    audioFmt = bb.getShort(pos + 8).toInt()
                    channels = bb.getShort(pos + 10).toInt()
                    sampleRate = bb.getInt(pos + 12)
                } else if (id == "data") {
                    dataOff = pos + 8; dataLen = sz; break
                }
                pos += 8 + sz
            }
            if (dataOff < 0 || audioFmt != 1 || channels != 1 || sampleRate != 16000) return null
            val n = dataLen / 2
            val out = FloatArray(n)
            val db = ByteBuffer.wrap(wav, dataOff, dataLen).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) out[i] = db.getShort().toFloat() / 32768.0f
            return out
        } catch (e: Exception) {
            Log.e(TAG, "wav parse failed", e); return null
        }
    }

    fun transcribe(
        ctx: Context, wavBytes: ByteArray, kind: String, lang: String
    ): Result {
        val samples = wavToFloat(wavBytes)
            ?: return Result("[unsupported audio: need 16k mono wav]", 0, 0f)
        val rec = recognizer(ctx, kind, lang)
        val stream = rec.createStream()
        val t0 = System.currentTimeMillis()
        stream.acceptWaveform(samples, 16000)
        rec.decode(stream)
        val text = rec.getResult(stream).text.trim()
        stream.release()
        val dt = System.currentTimeMillis() - t0
        return Result(text, dt, samples.size / 16000.0f)
    }

    fun releaseAll() {
        synchronized(lock) {
            try { whisperMs?.release() } catch (_: Exception) { }
            try { whisperEn?.release() } catch (_: Exception) { }
            whisperMs = null; whisperEn = null
        }
    }
}
