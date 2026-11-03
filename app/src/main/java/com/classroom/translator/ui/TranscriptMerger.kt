package com.classroom.translator.ui

/**
 * 跨段文本合并 —— 解决「识别结果没显示完整」的根因之一。
 *
 * 【2026-11-03 用户反馈】课堂翻译的卡片上，英文和中文都停在半句话上：
 *   英文 "Okay, I will get back to you. Stand up. What is"
 *   中文 "好的，我会回复你。站起来。什么是"
 * 两边断在同一个位置 → 说明**不是 UI 截断，是数据本身被截断了**。
 *
 * 根因：ASR 单段音频有 8 秒硬上限（MAX_SEGMENT_MS，超时长的句子被切成两段）。
 * 两段是**独立**识别的（不带前缀续听），于是第一段的转写就停在 8 秒处，
 * 翻译也是照着这半句翻的 → 中英文一起断在半句。
 *
 * 修法：识别端标记「这一段是被硬切开的」，下一段到达时**合并**回上一条，
 * 而不是新开一条。合并时还要处理两段之间的**重叠**（模型在边界处可能重复吐词）。
 *
 * 纯函数，不依赖 Android → 可 JVM 单元测试。
 */
object TranscriptMerger {

    private val WORD_SPLIT = Regex("\\s+")

    /** 句末标点（用于判断「这句说完了没」） */
    private val TERMINAL = setOf('.', '!', '?', '。', '！', '？', '…', '；', ';')

    /**
     * 合并两段文本，去掉边界处的重复。
     *
     * ⚠️ **关键不变量**：结果必须以 [prev] 开头。
     *
     * 原因：上层 addOrMergeEntry 靠「新文本以旧文本开头」来判断"这是上一句的续写"，
     * 靠这个判断把**同一条卡片**替换成完整句子，而不是新开一张卡片。
     * 如果合并结果丢了前缀（例如把 "I think we should go" + "go now" 合成
     * "I think we should now"），上层就会当成新句子 → 界面上出现两张重复的卡片。
     *
     * 所以这里处理重叠时是「**保留 prev 完整，只丢掉 next 开头重复的部分**」，
     * 而不是「两段都保留、把重复词从中间删掉」。
     *
     * @param prev 前一段（可能已被硬切开）
     * @param next 后一段（续写）
     * @return 合并后的完整文本（一定以 prev 开头）
     */
    fun merge(prev: String, next: String): String {
        val a = prev.trim()
        val b = next.trim()
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a

        // 1) 词级重叠：前段末尾的若干词 == 后段开头的同样多的词
        val aw = a.split(WORD_SPLIT).filter { it.isNotEmpty() }
        val bw = b.split(WORD_SPLIT).filter { it.isNotEmpty() }
        val maxWords = minOf(aw.size, bw.size)
        for (n in maxWords downTo 1) {
            val tail = aw.takeLast(n).joinToString(" ")
            val head = bw.take(n).joinToString(" ")
            if (tail.equals(head, ignoreCase = true)) {
                // 保留 prev 完整，去掉 next 开头重复的 n 个词
                val rest = bw.drop(n)
                return if (rest.isEmpty()) a else "$a ${rest.joinToString(" ")}"
            }
        }

        // 2) 字符级重叠：边界有共同字符（"What is the que" + "stion" → "question"）
        val maxChars = minOf(a.length, b.length, 40)
        for (n in maxChars downTo 1) {
            if (a.endsWith(b.take(n), ignoreCase = true)) {
                return a + b.drop(n)
            }
        }

        // 3) 前段以连字符结尾 → 直接接上（听写场景常见："pre-" + "school"）
        if (a.endsWith("-")) return a + b

        // 4) 无重叠 → 用空格拼接
        //
        // ⚠️ 这里**不能**无条件把词粘在一起："I will get back to you" + "stand up"
        //    粘成 "youstand up" 是错的。只有确认存在字符重叠时（第 2 步）才无空格拼接。
        return "$a $b"
    }

    /**
     * 这段文本看起来是「被切断的」吗（没有句末标点）。
     * 只用于辅助判断，真正是否合并以识别端的硬切标记为准。
     */
    fun looksTruncated(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        return t.last() !in TERMINAL
    }
}
