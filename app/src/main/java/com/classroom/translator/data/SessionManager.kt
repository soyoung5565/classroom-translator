package com.classroom.translator.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TranslationEntryData(
    val timestamp: String = "",
    val english: String = "",
    val chinese: String = "",
    /** 录音内偏移秒数（-1 = 未知）；课堂总结的"录音时间区间"依赖它 */
    val startSec: Int = -1,
    val endSec: Int = -1
)

data class TranslationSession(
    val id: Long = System.currentTimeMillis(),
    val date: String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()),
    val startTime: String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
    val endTime: String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
    val entries: List<TranslationEntryData> = emptyList(),
    val summary: String = "",
    val audioPath: String = "",
    /** AI 课堂总结（JSON 字符串，见 ClassSummary）；空 = 尚未生成 */
    val classSummary: String = "",
    /** 会话结束后是否已云端整课重译（润色） */
    val polished: Boolean = false
) {
    val entryCount: Int get() = entries.size
}

class SessionManager(private val context: Context) {

    companion object {
        private const val TAG = "SessionManager"
    }

    private val sessionsDir: File by lazy {
        File(context.getExternalFilesDir(null), "sessions").also { it.mkdirs() }
    }

    fun saveSession(session: TranslationSession) {
        try {
            val json = JSONObject().apply {
                put("id", session.id)
                put("date", session.date)
                put("startTime", session.startTime)
                put("endTime", session.endTime)
                put("summary", session.summary)
                put("audioPath", session.audioPath)
                put("classSummary", session.classSummary)
                put("polished", session.polished)
                put("entries", JSONArray().apply {
                    session.entries.forEach { entry ->
                        put(JSONObject().apply {
                            put("timestamp", entry.timestamp)
                            put("english", entry.english)
                            put("chinese", entry.chinese)
                            put("startSec", entry.startSec)
                            put("endSec", entry.endSec)
                        })
                    }
                })
            }
            val file = File(sessionsDir, "session_${session.id}.json")
            file.writeText(json.toString(2))
            Log.i(TAG, "Saved session: ${session.id} (${session.entryCount} entries)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save session", e)
        }
    }

    fun loadSessions(): List<TranslationSession> {
        return try {
            val files = sessionsDir.listFiles() ?: return emptyList()
            files.filter { it.name.endsWith(".json") }
                .mapNotNull { file ->
                    try {
                        parseSession(file)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse ${file.name}", e)
                        null
                    }
                }
                .sortedByDescending { "${it.date} ${it.startTime}" }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load sessions", e)
            emptyList()
        }
    }

    fun deleteSession(sessionId: Long) {
        try {
            val file = File(sessionsDir, "session_$sessionId.json")
            if (file.exists()) {
                file.delete()
                Log.i(TAG, "Deleted session: $sessionId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete session", e)
        }
    }

    fun exportSessionToText(session: TranslationSession): String {
        val sb = StringBuilder()
        sb.append("课堂翻译记录")
        sb.append("━━━━━━━━━━━━━━━━━━━━━━")
        sb.append("日期: ${session.date}")
        sb.append("时间: ${session.startTime} - ${session.endTime}")
        sb.append("共 ${session.entryCount} 段翻译")
        sb.append("━━━━━━━━━━━━━━━━━━━━━━")
        sb.appendLine()

        // English paragraph
        sb.append("[English]")
        val englishText = session.entries.joinToString(" ") { it.english }
        sb.append(englishText)
        sb.appendLine()

        // Chinese paragraph
        sb.append("[中文]")
        val chineseText = session.entries.joinToString("") { it.chinese }
        sb.append(chineseText)
        sb.appendLine()

        // Detailed entries
        sb.append("━━━━━━━━━━━━━━━━━━━━━━")
        sb.append("详细记录:")
        session.entries.forEach { entry ->
            sb.appendLine()
            sb.append("[${entry.timestamp}]")
            sb.append("  EN: ${entry.english}")
            sb.append("  CN: ${entry.chinese}")
        }

        return sb.toString()
    }

    private fun parseSession(file: File): TranslationSession {
        val json = JSONObject(file.readText())
        val entriesArray = json.getJSONArray("entries")
        val entries = (0 until entriesArray.length()).map { i ->
            val obj = entriesArray.getJSONObject(i)
            TranslationEntryData(
                timestamp = obj.optString("timestamp", ""),
                english = obj.optString("english", ""),
                chinese = obj.optString("chinese", ""),
                startSec = obj.optInt("startSec", -1),
                endSec = obj.optInt("endSec", -1)
            )
        }
        return TranslationSession(
            id = json.optLong("id", file.nameWithoutExtension.removePrefix("session_").toLongOrNull() ?: 0L),
            date = json.optString("date", ""),
            startTime = json.optString("startTime", ""),
            endTime = json.optString("endTime", ""),
            entries = entries,
            summary = json.optString("summary", ""),
                audioPath = json.optString("audioPath", ""),
                classSummary = json.optString("classSummary", ""),
                polished = json.optBoolean("polished", false)
        )
    }


    fun exportSessionToWord(session: TranslationSession): String {
        val dir = File(context.getExternalFilesDir(null), "exports")
        dir.mkdirs()
        val file = File(dir, "翻译_${session.date}_${session.startTime.replace(":", "")}.docx")
        
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
        sb.append("""<w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:rPr><w:b/><w:sz w:val="36"/></w:rPr><w:t>课堂翻译记录</w:t></w:r></w:p>""")
        sb.append("""<w:p><w:r><w:rPr><w:sz w:val="20"/></w:rPr><w:t>日期：${session.date}  时间：${session.startTime} - ${session.endTime}  共${session.entries.size}段</w:t></w:r></w:p>""")
        sb.append("""<w:p><w:pPr><w:pBdr><w:bottom w:val="single" w:sz="6" w:space="1" w:color="CCCCCC"/></w:pBdr></w:pPr></w:p>""")
        
        sb.append("""<w:p><w:r><w:rPr><w:b/><w:sz w:val="28"/></w:rPr><w:t>English</w:t></w:r></w:p>""")
        for (entry in session.entries) {
            val en = entry.english.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            sb.append("""<w:p><w:r><w:rPr><w:sz w:val="24"/></w:rPr><w:t xml:space="preserve">$en </w:t></w:r></w:p>""")
        }
        
        sb.append("""<w:p><w:pPr><w:pBdr><w:top w:val="single" w:sz="6" w:space="1" w:color="CCCCCC"/></w:pBdr></w:pPr></w:p>""")
        sb.append("""<w:p><w:r><w:rPr><w:b/><w:sz w:val="28"/></w:rPr><w:t>中文翻译</w:t></w:r></w:p>""")
        for (entry in session.entries) {
            val zh = entry.chinese.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            sb.append("""<w:p><w:r><w:rPr><w:sz w:val="24"/></w:rPr><w:t xml:space="preserve">$zh </w:t></w:r></w:p>""")
        }
        
        sb.append("""</w:body></w:document>""")
        
        val docXml = sb.toString()
        
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("[Content_Types].xml"))
            zip.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("_rels/.rels"))
            zip.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
            zip.write(docXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return file.absolutePath
    }

}