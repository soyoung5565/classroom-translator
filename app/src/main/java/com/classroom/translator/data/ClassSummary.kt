package com.classroom.translator.data

import org.json.JSONArray
import org.json.JSONObject

/** 课堂总结 · 重点条目（含录音时间区间，秒） */
data class SummaryPoint(val title: String, val detail: String, val startSec: Int, val endSec: Int)

/** 课堂总结 · 知识点讲解（词汇/语法/术语/概念） */
data class SummaryExplain(val term: String, val kind: String, val explanation: String, val startSec: Int, val endSec: Int)

/** 课堂总结 · 复习自测题 */
data class SummaryQA(val q: String, val a: String, val startSec: Int)

/**
 * 结构化课堂总结 —— 由云端大模型生成（默认火山方舟 deepseek-v4-1-flash）。
 * 持久化于 TranslationSession.classSummary（JSON 字符串）。
 *
 * 时间区间：模型只输出"条目编号 at"，本类按条目的录音偏移（startSec）换算成秒区间，
 * 从而支持「点击总结条目 → 跳转录音对应位置」。
 */
data class ClassSummary(
    val overview: String = "",
    val points: List<SummaryPoint> = emptyList(),
    val explainers: List<SummaryExplain> = emptyList(),
    val homework: List<String> = emptyList(),
    val questions: List<SummaryQA> = emptyList(),
    val keywords: List<String> = emptyList(),
    val generatedAt: Long = 0L,
    val model: String = ""
) {

    fun toJson(): String {
        val o = JSONObject()
        o.put("overview", overview)
        o.put("points", JSONArray().apply {
            points.forEach { p ->
                put(JSONObject().apply {
                    put("title", p.title)
                    put("detail", p.detail)
                    put("startSec", p.startSec)
                    put("endSec", p.endSec)
                })
            }
        })
        o.put("explainers", JSONArray().apply {
            explainers.forEach { e ->
                put(JSONObject().apply {
                    put("term", e.term)
                    put("kind", e.kind)
                    put("exp", e.explanation)
                    put("startSec", e.startSec)
                    put("endSec", e.endSec)
                })
            }
        })
        o.put("homework", JSONArray().apply { homework.forEach { put(it) } })
        o.put("questions", JSONArray().apply {
            questions.forEach { q ->
                put(JSONObject().apply {
                    put("q", q.q)
                    put("a", q.a)
                    put("startSec", q.startSec)
                })
            }
        })
        o.put("keywords", JSONArray().apply { keywords.forEach { put(it) } })
        o.put("generatedAt", generatedAt)
        o.put("model", model)
        return o.toString()
    }

    companion object {

        fun fromJson(raw: String?): ClassSummary? {
            if (raw.isNullOrBlank()) return null
            return try {
                val o = JSONObject(raw)
                val points = ArrayList<SummaryPoint>()
                o.optJSONArray("points")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val p = arr.optJSONObject(i) ?: continue
                        points.add(
                            SummaryPoint(
                                p.optString("title", "重点"),
                                p.optString("detail", ""),
                                p.optInt("startSec", -1),
                                p.optInt("endSec", -1)
                            )
                        )
                    }
                }
                val explains = ArrayList<SummaryExplain>()
                o.optJSONArray("explainers")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val p = arr.optJSONObject(i) ?: continue
                        explains.add(
                            SummaryExplain(
                                p.optString("term", ""),
                                p.optString("kind", "term"),
                                p.optString("exp", ""),
                                p.optInt("startSec", -1),
                                p.optInt("endSec", -1)
                            )
                        )
                    }
                }
                val hw = ArrayList<String>()
                o.optJSONArray("homework")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val v = arr.optString(i, "")
                        if (v.isNotBlank()) hw.add(v)
                    }
                }
                val qs = ArrayList<SummaryQA>()
                o.optJSONArray("questions")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val p = arr.optJSONObject(i) ?: continue
                        qs.add(SummaryQA(p.optString("q", ""), p.optString("a", ""), p.optInt("startSec", -1)))
                    }
                }
                val kw = ArrayList<String>()
                o.optJSONArray("keywords")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val v = arr.optString(i, "")
                        if (v.isNotBlank()) kw.add(v)
                    }
                }
                ClassSummary(
                    overview = o.optString("overview", ""),
                    points = points,
                    explainers = explains,
                    homework = hw,
                    questions = qs,
                    keywords = kw,
                    generatedAt = o.optLong("generatedAt", 0L),
                    model = o.optString("model", "")
                )
            } catch (e: Exception) {
                null
            }
        }

        // ---- 提示词（2026-09-26 用火山 deepseek-v4-1-flash dry-run 验证版）----

        fun buildSystemPrompt(): String = (
            "你是一名课堂学习助手,为一堂英语课(英文授课、中文翻译)生成课后总结。" +
            "输入是逐句转写(编号 + 录音偏移秒数 + 英文原文 + 中文翻译)。输出一个 JSON 对象,字段:\n" +
            "overview: 本课概要,2-3 句中文;\n" +
            "points: 重点内容数组(3-6条),每条 {\"title\":\"短标题\",\"detail\":\"中文讲解/要点\",\"at\":起始条目编号};\n" +
            "explainers: 知识点讲解数组(3-6条),每条 {\"term\":\"英文词/短语/语法点\",\"kind\":\"vocab|grammar|term|concept\",\"exp\":\"面向中国学生的中文讲解\",\"at\":起始条目编号};\n" +
            "homework: 课后作业/待办(字符串数组,没有则空数组);\n" +
            "questions: 复习自测题数组(3-5条),每条 {\"q\":\"中文问题\",\"a\":\"参考答案\",\"at\":联系到的条目编号};\n" +
            "keywords: 本课关键词(3-6个字符串)。\n" +
            "规则: 1) at 必须是给定条目编号; 2) 除 term 外全部中文; 3) 只输出 JSON,不要任何其他文字。"
        )

        fun buildUserPrompt(entries: List<TranslationEntryData>): String {
            val sb = StringBuilder("转写内容:\n")
            entries.forEachIndexed { i, e ->
                sb.append('[').append(i).append(']')
                if (e.startSec >= 0) sb.append(" (+").append(e.startSec).append("s)")
                sb.append(" EN: ").append(e.english).append(" || CN: ").append(e.chinese).append('\n')
            }
            return sb.toString()
        }

        /** 条目录音偏移列表（start, end 秒；-1 = 未知）。优先条目自带偏移；回退解析时间戳。 */
        fun entryOffsets(entries: List<TranslationEntryData>, sessionStart: String): List<Pair<Int, Int>> {
            val base = parseClockSec(sessionStart)
            val raw = entries.map { e ->
                when {
                    e.startSec >= 0 -> e.startSec to (if (e.endSec >= e.startSec) e.endSec else -1)
                    else -> {
                        // 整课转录导入: "mm:ss - mm:ss"
                        val m = Regex("^\\s*(\\d+):(\\d{2})\\s*-\\s*(\\d+):(\\d{2})").find(e.timestamp)
                        if (m != null) {
                            val s = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
                            val en = m.groupValues[3].toInt() * 60 + m.groupValues[4].toInt()
                            s to en
                        } else {
                            val ts = parseClockSec(e.timestamp)
                            if (ts != null && base != null) {
                                var d = ts - base
                                if (d < 0) d += 86400
                                d to -1
                            } else -1 to -1
                        }
                    }
                }
            }
            return raw.mapIndexed { i, p ->
                val s = p.first
                val minEnd = if (p.second >= 0) p.second else {
                    val nxt = raw.drop(i + 1).firstOrNull { it.first >= 0 }?.first
                    if (nxt != null && nxt > s) nxt else if (s >= 0) s + 30 else -1
                }
                s to minEnd
            }
        }

        /** 解析模型输出 → ClassSummary（含 at 编号 → 秒区间 映射）。失败返回 null。 */
        fun parseModelOutput(
            raw: String,
            entries: List<TranslationEntryData>,
            sessionStart: String,
            model: String
        ): ClassSummary? {
            return try {
                val text = raw.let {
                    val s = it.indexOf('{')
                    val e = it.lastIndexOf('}')
                    if (s >= 0 && e > s) it.substring(s, e + 1) else it
                }
                val o = JSONObject(text)
                val offs = entryOffsets(entries, sessionStart)
                val lastEnd = offs.map { it.second }.lastOrNull { it >= 0 } ?: -1

                fun secAt(idx: Int): Int = if (idx in offs.indices) offs[idx].first else -1
                fun rangeEnd(start: Int, nextStart: Int?): Int {
                    if (start < 0) return -1
                    if (nextStart != null && nextStart > start) return nextStart
                    val cap = if (lastEnd > start) lastEnd else start + 120
                    return minOf(start + 120, cap).coerceAtLeast(start + 15)
                }

                val ptsJson = o.optJSONArray("points") ?: JSONArray()
                val ptsRaw = ArrayList<Triple<String, String, Int>>()
                for (i in 0 until ptsJson.length()) {
                    val p = ptsJson.optJSONObject(i) ?: continue
                    ptsRaw.add(Triple(p.optString("title", "重点"), p.optString("detail", ""), p.optInt("at", -1)))
                }
                val points = ArrayList<SummaryPoint>()
                ptsRaw.forEachIndexed { i, t ->
                    val s = secAt(t.third)
                    val nxt = ptsRaw.getOrNull(i + 1)?.third?.let { secAt(it) }
                    points.add(SummaryPoint(t.first, t.second, s, rangeEnd(s, nxt)))
                }

                val exJson = o.optJSONArray("explainers") ?: JSONArray()
                val exAts = (0 until exJson.length()).map { i -> exJson.optJSONObject(i)?.optInt("at", -1) ?: -1 }
                val explains = ArrayList<SummaryExplain>()
                for (i in 0 until exJson.length()) {
                    val p = exJson.optJSONObject(i) ?: continue
                    val s = secAt(p.optInt("at", -1))
                    val nextS = exAts.drop(i + 1).firstOrNull { it >= 0 }?.let { secAt(it) }
                    val e = if (s >= 0) {
                        minOf(rangeEnd(s, nextS), s + 90).coerceAtLeast(s + 10)
                    } else -1
                    explains.add(
                        SummaryExplain(
                            term = p.optString("term", ""),
                            kind = p.optString("kind", "term"),
                            explanation = p.optString("exp", p.optString("explanation", "")),
                            startSec = s,
                            endSec = e
                        )
                    )
                }

                val hw = ArrayList<String>()
                o.optJSONArray("homework")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val v = arr.optString(i, "")
                        if (v.isNotBlank()) hw.add(v)
                    }
                }

                val qs = ArrayList<SummaryQA>()
                o.optJSONArray("questions")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val p = arr.optJSONObject(i) ?: continue
                        qs.add(SummaryQA(p.optString("q", ""), p.optString("a", ""), secAt(p.optInt("at", -1))))
                    }
                }

                val kw = ArrayList<String>()
                o.optJSONArray("keywords")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val v = arr.optString(i, "")
                        if (v.isNotBlank()) kw.add(v)
                    }
                }

                ClassSummary(
                    overview = o.optString("overview", ""),
                    points = points,
                    explainers = explains,
                    homework = hw,
                    questions = qs,
                    keywords = kw,
                    generatedAt = System.currentTimeMillis(),
                    model = model
                )
            } catch (e: Exception) {
                null
            }
        }

        private fun parseClockSec(t: String?): Int? {
            if (t.isNullOrBlank()) return null
            return try {
                val p = t.split(":")
                if (p.size >= 2) {
                    val h = p[0].trim().toInt()
                    val m = p[1].trim().toInt()
                    val s = if (p.size >= 3) p[2].trim().toInt() else 0
                    h * 3600 + m * 60 + s
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }
}
