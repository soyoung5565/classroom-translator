package com.classroom.translator.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/**
 * 润色前后对比 —— 轻量词级 diff（英文按词、中文按字）。
 *
 * 详情页在润色后给每个变动过的条目显示「（原：…）」：
 *  - 新文本中"新增/改动过"的词 → 高亮（primary 色）；
 *  - 原文本中"被去掉"的词 → 删除线。
 * 纯逻辑无 Android 依赖，可直接在 JVM 单测里跑。
 */
object PolishDiff {

    data class DiffResult(
        /** 新文本中变动字符的区间（闭区间，含端点） */
        val newRanges: List<IntRange>,
        /** 原文本中被删除字符的区间 */
        val oldRanges: List<IntRange>,
    ) {
        val hasNew get() = newRanges.isNotEmpty()
        val hasOld get() = oldRanges.isNotEmpty()
    }

    private data class Tok(val text: String, val start: Int, val end: Int)

    private val PUNCT: Set<Char> = (
        ".,!?;:\"'()[]{}<>，。！？；：、·—…“”‘’（）【】《》-"
        ).toSet()

    private fun isCjk(c: Char): Boolean =
        c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF || c.code in 0xF900..0xFAFF

    /** 分词：CJK 单字成词；其余按空白切分、标点随前词 */
    private fun tokenize(s: String): List<Tok> {
        val out = ArrayList<Tok>()
        val buf = StringBuilder()
        var bufStart = -1
        fun flush(end: Int) {
            if (buf.isNotEmpty()) {
                out.add(Tok(buf.toString(), bufStart, end))
                buf.clear()
                bufStart = -1
            }
        }
        for (i in s.indices) {
            val c = s[i]
            when {
                c.isWhitespace() -> flush(i)
                isCjk(c) -> {
                    flush(i)
                    out.add(Tok(c.toString(), i, i + 1))
                }
                else -> {
                    if (buf.isEmpty()) bufStart = i
                    buf.append(c)
                }
            }
        }
        flush(s.length)
        return out
    }

    private fun norm(t: String): String {
        val lower = t.lowercase()
        return lower.trim { it in PUNCT || it == ' ' }
    }

    /** 计算 diff；返回字符区间集合供 UI 高亮/删除线 */
    fun diff(newText: String, oldText: String): DiffResult {
        val a = tokenize(newText)
        val b = tokenize(oldText)
        if (a.isEmpty() && b.isEmpty()) return DiffResult(emptyList(), emptyList())
        if (a.isEmpty()) return DiffResult(emptyList(), listOf(0..oldText.length - 1))
        if (b.isEmpty()) return DiffResult(listOf(0..newText.length - 1), emptyList())
        // 上限保护（超长文本不做高亮，只显示原文对比）
        if (a.size.toLong() * b.size > 250_000L) return DiffResult(emptyList(), emptyList())

        val na = a.map { norm(it.text) }
        val nb = b.map { norm(it.text) }
        val m = a.size
        val n = b.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in m - 1 downTo 0) {
            for (j in n - 1 downTo 0) {
                dp[i][j] = if (na[i] == nb[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val newChanged = BooleanArray(m)
        val oldRemoved = BooleanArray(n)
        var i = 0
        var j = 0
        while (i < m && j < n) {
            when {
                na[i] == nb[j] -> { i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { newChanged[i] = true; i++ }
                else -> { oldRemoved[j] = true; j++ }
            }
        }
        while (i < m) { newChanged[i] = true; i++ }
        while (j < n) { oldRemoved[j] = true; j++ }

        fun ranges(toks: List<Tok>, flags: BooleanArray): List<IntRange> {
            val out = ArrayList<IntRange>()
            var curStart = -1
            var curEnd = -1
            toks.forEachIndexed { k, t ->
                if (flags[k]) {
                    if (curStart < 0) {
                        curStart = t.start; curEnd = t.end
                    } else if (t.start <= curEnd) {
                        curEnd = maxOf(curEnd, t.end)
                    } else {
                        out.add(curStart until curEnd)
                        curStart = t.start; curEnd = t.end
                    }
                }
            }
            if (curStart >= 0) out.add(curStart until curEnd)
            return out
        }
        return DiffResult(ranges(a, newChanged), ranges(b, oldRemoved))
    }

    /** 给文本叠加区间样式（越界安全） */
    fun annotate(text: String, ranges: List<IntRange>, style: SpanStyle): AnnotatedString =
        buildAnnotatedString {
            append(text)
            ranges.forEach { r ->
                if (r.first >= 0 && r.last < text.length) addStyle(style, r.first, r.last + 1)
            }
        }
}
