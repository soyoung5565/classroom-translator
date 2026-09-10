package com.classroom.translator.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * On-device batch scorer (debug builds). Reads wav+refs straight from
 * /sdcard/Download, no file-picker involved. Results -> devtest_results.json.
 * Split mirrors PC harness: p01-12/test02/test03 = ms, p13-24/test01/04/05/06 = en.
 */
object DevTestRunner {
    private const val TAG = "DevTest"
    @Volatile var running = false
    @Volatile var progress = ""
    @Volatile var doneJson: String = ""

    private fun loadRefs(f: File): Map<String, String> {
        val m = mutableMapOf<String, String>()
        if (!f.exists()) return m
        for (line in f.readLines()) {
            val i = line.indexOf('\t')
            if (i > 0) m[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
        return m
    }

    suspend fun runAll(ctx: Context, onTick: (String) -> Unit = {}) = withContext(Dispatchers.Default) {
        if (running) return@withContext
        running = true
        try {
            val dl = File("/sdcard/Download")
            try { File(dl, "devtest_results.json").delete() } catch (_: Exception) { }
            val refs = mutableMapOf<String, String>()
            refs.putAll(loadRefs(File(dl, "phonetest24/refs24.txt")))
            refs.putAll(loadRefs(File(dl, "phonetest/refs.txt")))
            val msSet = ((1..12).map { "p%02d.wav".format(it) } + listOf("test02.wav", "test03.wav")).toSet()
            val files = mutableListOf<Pair<File, String>>()
            for (w in File(dl, "phonetest24").listFiles() ?: emptyArray()) {
                if (w.extension.lowercase() == "wav") {
                    val ref = refs[w.name] ?: continue
                    files.add(w to if (w.name in msSet) "ms" else "en")
                }
            }
            for (w in File(dl, "phonetest").listFiles() ?: emptyArray()) {
                if (w.extension.lowercase() == "wav") {
                    val ref = refs[w.name] ?: continue
                    files.add(w to if (w.name in msSet) "ms" else "en")
                }
            }
            files.sortBy { it.first.name }
            val filesSel = files.filter { (_, lang) -> lang == "en" }.toMutableList()
            // 三模型全量对比：whisper-small / qwen3-asr / funasr-nano
            val kinds = if (filesSel.isNotEmpty())
                listOf("whisper-small", "qwen3-asr", "funasr-nano") else emptyList()
            val out = File(dl, "devtest_results.json")
            var done = 0
            for (kind in kinds) {
            for ((w, lang) in filesSel) {
                val t0 = System.currentTimeMillis()
                var hyp = ""
                var err = ""
                try {
                    val r = OfflineAsrEngine.transcribe(ctx, w.readBytes(), kind, lang)
                    hyp = r.text
                } catch (e: Exception) {
                    err = (e.message ?: "error").take(120)
                    Log.e(TAG, "fail " + w.name, e)
                }
                val dt = System.currentTimeMillis() - t0
                val row = JSONObject()
                    .put("file", w.name).put("lang", lang).put("kind", kind)
                    .put("ref", refs[w.name]).put("hyp", hyp)
                    .put("err", err).put("ms", dt)
                out.appendText(row.toString() + "\n")
                done++
                progress = "$done/${filesSel.size * kinds.size} [$kind] ${w.name} ${dt}ms"
                onTick(progress)
                Log.i(TAG, progress + " hyp=" + hyp.take(50))
            }
            doneJson = out.absolutePath
            }
        } finally {
            running = false
        }
    }
}
