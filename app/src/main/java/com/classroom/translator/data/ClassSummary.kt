package com.classroom.translator.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** 课堂总结 · 重点条目（含录音时间区间，秒；entryIndex=对应转写条目下标，-1=未知） */
data class SummaryPoint(val title: String, val detail: String, val startSec: Int, val endSec: Int, val entryIndex: Int = -1)

/** 课堂总结 · 知识点讲解（词汇/语法/术语/概念） */
data class SummaryExplain(val term: String, val kind: String, val explanation: String, val startSec: Int, val endSec: Int, val entryIndex: Int = -1)

/** 课堂总结 · 复习自测题 */
data class SummaryQA(val q: String, val a: String, val startSec: Int, val entryIndex: Int = -1)

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
                    put("entryIdx", p.entryIndex)
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
                    put("entryIdx", e.entryIndex)
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
                    put("entryIdx", q.entryIndex)
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
                                p.optInt("endSec", -1),
                                p.optInt("entryIdx", -1)
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
                                p.optInt("endSec", -1),
                                p.optInt("entryIdx", -1)
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
                        qs.add(
                            SummaryQA(
                                p.optString("q", ""),
                                p.optString("a", ""),
                                p.optInt("startSec", -1),
                                p.optInt("entryIdx", -1)
                            )
                        )
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

        /** 解析失败重试时追加的系统约束（强化"只输出一个完整 JSON"）。 */
        const val RETRY_SYSTEM_SUFFIX: String =
            "注意：你上一次的输出不是有效 JSON（可能被截断或夹带了说明文字）。" +
            "这次务必只输出一个完整、严格有效的 JSON 对象：不要 markdown 代码块、不要任何解释、" +
            "不要在 JSON 前后写任何文字，并确保所有括号成对匹配、JSON 完整闭合。"

        fun buildUserPrompt(entries: List<TranslationEntryData>): String {
            val sb = StringBuilder("转写内容:\n")
            entries.forEachIndexed { i, e ->
                sb.append('[').append(i).append(']')
                if (e.startSec >= 0) sb.append(" (+").append(e.startSec).append("s)")
                sb.append(" EN: ").append(e.english).append(" || CN: ").append(e.chinese).append('\n')
            }
            return sb.toString()
        }

        /**
         * 条目录音偏移列表（start, end 秒；-1 = 未知）。优先条目自带偏移；回退解析时间戳。
         *
         * 2026-09-29 修复「时间完全错乱」：时间戳回退换算的基准必须是**录音开始时间**——
         * 从音频文件名 recording_YYYYMMDD_HHMMSS.wav 取（真实录音起点）；
         * sessionStart（保存时刻，等于结束时间）只能兜底。旧算法拿保存时刻当基准，
         * 差值变负数后错误 +86400 回绕 → 61 秒的条目标成 23小时+。
         */
        fun entryOffsets(
            entries: List<TranslationEntryData>,
            sessionStart: String,
            audioPath: String = ""
        ): List<Pair<Int, Int>> {
            val base = recordingStartSec(audioPath) ?: parseClockSec(sessionStart)
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
                                if (d < -12 * 3600) d += 86400 // 跨午夜（录音 23:5x 开始、条目 00:0x）
                                if (d < 0) d = 0                // 基准前几秒的零星条目 → 归 0
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

        /** 录音开始时刻（秒）——从 audioPath 里的 recording_yyyyMMdd_HHmmss 文件名解析。 */
        private fun recordingStartSec(audioPath: String?): Int? {
            if (audioPath.isNullOrBlank()) return null
            val m = Regex("recording_\\d{8}_(\\d{2})(\\d{2})(\\d{2})").find(audioPath) ?: return null
            return m.groupValues[1].toInt() * 3600 + m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt()
        }

        /** 把总结条目解析到"对应转写条目下标"（新总结直接带 entryIdx；旧总结按秒数就近匹配）。 */
        fun resolveEntryIndex(entries: List<TranslationEntryData>, entryIndex: Int, startSec: Int): Int {
            if (entryIndex in entries.indices) return entryIndex
            if (startSec >= 0) {
                val i = entries.indexOfFirst { it.startSec >= 0 && kotlin.math.abs(it.startSec - startSec) <= 3 }
                if (i >= 0) return i
            }
            return -1
        }

        /** 解析模型输出 → ClassSummary（含 at 编号 → 秒区间 映射）。失败返回 null。 */
        fun parseModelOutput(
            raw: String,
            entries: List<TranslationEntryData>,
            sessionStart: String,
            model: String,
            audioPath: String = ""
        ): ClassSummary? {
            for (cand in jsonCandidates(raw)) {
                val o = tryParseJsonObject(cand) ?: continue
                if (KNOWN_KEYS.none { o.has(it) }) continue
                return buildFrom(o, entries, sessionStart, model, audioPath)
            }
            return null
        }

        /** 从已解析的 JSONObject 组装 ClassSummary（at 编号 → 秒区间映射） */
        private fun buildFrom(
            o: JSONObject,
            entries: List<TranslationEntryData>,
            sessionStart: String,
            model: String,
            audioPath: String = ""
        ): ClassSummary? {
            return try {
                val offs = entryOffsets(entries, sessionStart, audioPath)
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
                    points.add(SummaryPoint(t.first, t.second, s, rangeEnd(s, nxt), t.third))
                }

                val exJson = o.optJSONArray("explainers") ?: JSONArray()
                val exAts = (0 until exJson.length()).map { i -> exJson.optJSONObject(i)?.optInt("at", -1) ?: -1 }
                val explains = ArrayList<SummaryExplain>()
                for (i in 0 until exJson.length()) {
                    val p = exJson.optJSONObject(i) ?: continue
                    val at = p.optInt("at", -1)
                    val s = secAt(at)
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
                            endSec = e,
                            entryIndex = at
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
                        val at = p.optInt("at", -1)
                        qs.add(SummaryQA(p.optString("q", ""), p.optString("a", ""), secAt(at), at))
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

        // ---- 稳健提取（2026-09-29 加固：模型输出非 JSON / 被截断时的多层兜底）----

        private val KNOWN_KEYS = listOf("overview", "points", "explainers", "questions", "keywords", "homework")

        /** 收集 JSON 候选串（按可信度排序）：整体 → first{..last} → 平衡括号扫描 → 截断补全 → 格式修复变体。 */
        private fun jsonCandidates(raw0: String): List<String> {
            val out = LinkedHashSet<String>()
            fun add(s: String?) {
                val t = s?.trim().orEmpty()
                if (t.length >= 2 && t.startsWith("{")) out.add(t)
            }
            // 剥 <think> 残块与 markdown 围栏（cloudChat 已剥一层，这里保底）
            var base = raw0.replace("\uFEFF", "")
            base = base.replace(Regex("(?s)<think>.*?</think>"), "")
            base = base.replace(Regex("(?s)</think>"), "")
            base = base.replace("```json", "").replace("```JSON", "").replace("```", "")
            base = base.trim()

            add(base)
            run {
                val s = base.indexOf('{')
                val e = base.lastIndexOf('}')
                if (s >= 0 && e > s) add(base.substring(s, e + 1))
            }
            // 平衡括号扫描：优先含已知字段名、体积大的对象
            scanBalancedObjects(base)
                .sortedWith(
                    compareByDescending<String> { c -> KNOWN_KEYS.count { c.contains("\"$it\"") } }
                        .thenByDescending { it.length }
                )
                .forEach { add(it) }
            // 截断补全（finish_reason=length 时最常见的形态）
            run {
                val s = base.indexOf('{')
                if (s >= 0) add(closeTruncated(base.substring(s)))
            }
            // 格式修复变体（尾逗号 / 智能引号 / 控制字符 / 单引号）
            for (fixed in repairVariants(base)) {
                add(fixed)
                val s = fixed.indexOf('{')
                val e = fixed.lastIndexOf('}')
                if (s >= 0 && e > s) add(fixed.substring(s, e + 1))
                add(closeTruncated(fixed.substring(minOf(s.coerceAtLeast(0), fixed.length))))
            }
            return out.toList().take(24)
        }

        /** 字符串感知地扫描所有平衡 { ... } 片段 */
        private fun scanBalancedObjects(t: String): List<String> {
            val res = ArrayList<String>()
            var depth = 0
            var start = -1
            var inStr = false
            var esc = false
            for (i in t.indices) {
                val c = t[i]
                if (inStr) {
                    when {
                        esc -> esc = false
                        c == '\\' -> esc = true
                        c == '"' -> inStr = false
                    }
                    continue
                }
                when (c) {
                    '"' -> inStr = true
                    '{' -> {
                        if (depth == 0) start = i
                        depth++
                    }
                    '}' -> if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            res.add(t.substring(start, i + 1))
                            start = -1
                        }
                    }
                }
            }
            return res.filter { it.length >= 12 }
        }

        /** 常见格式修复变体（保守 → 激进） */
        private fun repairVariants(base: String): List<String> {
            val noTrailingComma: (String) -> String = { s -> s.replace(Regex(",\\s*([}\\]])"), "$1") }
            val smartQuotes: (String) -> String = { s -> s.replace(Regex("[\u201C\u201D]"), "\"") }
            val ctrlChars: (String) -> String = { s ->
                s.map { ch -> if (ch.code < 0x20 && ch != '\n' && ch != '\r' && ch != '\t') ' ' else ch }.joinToString("")
            }
            val singleQuotes: (String) -> String = { s ->
                s.replace(Regex("([{,\\[])\\s*'([^'\\n]{1,80}?)'\\s*([:,\\]}])"), "$1\"$2\"$3")
            }
            val results = ArrayList<String>()
            for (f in listOf(noTrailingComma, smartQuotes, ctrlChars, singleQuotes)) {
                val r = f(base)
                if (r != base) results.add(r)
            }
            for (combo in listOf(
                noTrailingComma(smartQuotes(base)),
                noTrailingComma(singleQuotes(base)),
            )) {
                if (combo != base && !results.contains(combo)) results.add(combo)
            }
            return results
        }

        /**
         * 截断补全：对"从 { 开始但未闭合"的文本，在若干断点截断并补齐括号后尝试解析。
         * （模型被 max_tokens 截断时，JSON 尾部缺失；尽量抢救出 overview/points 等已完整字段）
         */
        private fun closeTruncated(s: String?): String? {
            if (s.isNullOrBlank() || !s.trimStart().startsWith("{")) return null
            val text = s.trimStart()
            val breaks = listOf(
                text.length,
                text.lastIndexOf(','),
                text.lastIndexOf('"'),
                text.lastIndexOf('}'),
                text.lastIndexOf(']'),
                text.lastIndexOf(':')
            ).filter { it > 1 }.distinct().sortedDescending()
            for (b in breaks) {
                var prefix = text.substring(0, b).trimEnd()
                while (prefix.endsWith(",")) prefix = prefix.dropLast(1).trimEnd()
                val closers = closersFor(prefix) ?: continue
                if (closers.isEmpty()) continue
                val candidate = prefix + closers
                if (tryParseJsonObject(candidate) != null) return candidate
            }
            return null
        }

        /** 计算补全所需闭合符（字符串感知）；文本停在未闭合字符串中时返回 null */
        private fun closersFor(prefix: String): String? {
            val stack = ArrayDeque<Char>()
            var inStr = false
            var esc = false
            for (c in prefix) {
                if (inStr) {
                    when {
                        esc -> esc = false
                        c == '\\' -> esc = true
                        c == '"' -> inStr = false
                    }
                    continue
                }
                when (c) {
                    '"' -> inStr = true
                    '{' -> stack.addLast('}')
                    '[' -> stack.addLast(']')
                    '}' -> if (stack.lastOrNull() == '}') stack.removeLast() else return null
                    ']' -> if (stack.lastOrNull() == ']') stack.removeLast() else return null
                }
            }
            if (inStr || esc) return null
            return stack.reversed().joinToString("")
        }

        /**
         * 严格解析：要求整段文本恰好是一个 JSON 对象（org.json 默认会忽略对象后的垃圾内容——
         * 这曾导致"前缀示例对象"被误当结果。用 JSONTokener 手动检查是否消费完整串）。
         */
        private fun tryParseJsonObject(s: String): JSONObject? = try {
            val tk = JSONTokener(s)
            val v = tk.nextValue()
            val rest = tk.nextClean()
            if (v is JSONObject && rest.code == 0) v else null
        } catch (e: Exception) {
            null
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
