package com.classroom.translator.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TranscriptMerger 单元测试（2026-11-03）
 *
 * 背景：用户反馈「识别结果没有显示完整」—— 卡片上英文和中文都停在半句话，
 * 例如 英文 "Okay, I will get back to you. Stand up. What is"
 *       中文 "好的，我会回复你。站起来。什么是"
 * 两边断在同一位置 → 不是 UI 截断，是 ASR 的 8 秒硬上限把句子切开了。
 *
 * ⚠️ **最重要的不变量**：merge 的结果必须以 prev 开头。
 * 上层 addOrMergeEntry 靠这个判断"这是上一句的续写" → 把同一张卡片替换成完整句子。
 * 结果丢了前缀 → 被当成新句子 → 界面上多出一张重复卡片。
 */
class TranscriptMergerTest {

    @Test
    fun `merge simple continuation`() {
        assertEquals(
            "Okay, I will get back to you. Stand up. What is this",
            TranscriptMerger.merge("Okay, I will get back to you. Stand up.", "What is this")
        )
    }

    @Test
    fun `merge removes duplicated boundary words but keeps prev prefix intact`() {
        // 模型在边界处重复吐了词 —— 结果必须仍是 "I think we should go now"
        // （旧实现返回 "I think we should now"，丢了 "go" → 上层会当成新句子 → 重复卡片）
        assertEquals(
            "I think we should go now",
            TranscriptMerger.merge("I think we should go", "go now")
        )
    }

    @Test
    fun `merge removes multi-word duplicated boundary and keeps prefix`() {
        assertEquals(
            "The answer is quite simple",
            TranscriptMerger.merge("The answer is quite", "quite simple")
        )
    }

    @Test
    fun `merge handles duplicated boundary characters`() {
        assertEquals(
            "What is the question is",
            TranscriptMerger.merge("What is the que", "question is")
        )
    }

    @Test
    fun `merge with empty prev returns next`() {
        assertEquals("Hello world", TranscriptMerger.merge("", "Hello world"))
    }

    @Test
    fun `merge with empty next returns prev`() {
        assertEquals("Hello world", TranscriptMerger.merge("Hello world", ""))
    }

    @Test
    fun `merge without overlap concatenates with space`() {
        assertEquals(
            "First sentence. Second sentence.",
            TranscriptMerger.merge("First sentence.", "Second sentence.")
        )
    }

    @Test
    fun `merge is case insensitive on overlap`() {
        assertEquals(
            "Let us begin the lesson now",
            TranscriptMerger.merge("Let us begin", "begin the lesson now")
        )
    }

    @Test
    fun `merge does not glue unrelated words together`() {
        // 没有重叠的两个片段必须用空格隔开 —— 粘成 "youstand" 是错的
        assertEquals(
            "I will get back to you stand up",
            TranscriptMerger.merge("I will get back to you", "stand up")
        )
    }

    @Test
    fun `merge handles trailing hyphen`() {
        assertEquals(
            "pre-school lesson",
            TranscriptMerger.merge("pre-", "school lesson")
        )
    }

    @Test
    fun `merge never loses any text`() {
        /**
         * 硬切段合并的**底线要求**：一个字都不能丢。
         *
         * ⚠️ 已知限制（诚实记录）：若切点正好落在词中间、且两半**没有任何字符重叠**
         * （如 "ques" + "tion"），纯文本层面无法判定这是"一个词被切开"还是"两个词"。
         * 此时会保守地留一个空格（"ques tion"）——宁可多一个空格，也不丢内容、
         * 也不把两个无关的词粘成一个（"you"+"stand" → "youstand" 是更糟的错误）。
         */
        val merged = TranscriptMerger.merge("What is the ques", "tion of this one")
        assertTrue("must not lose 'ques': $merged", merged.contains("ques"))
        assertTrue("must not lose 'tion': $merged", merged.contains("tion"))
        assertTrue("must not lose the tail: $merged", merged.contains("of this one"))
    }

    @Test
    fun `merge always starts with prev - the anti duplicate card invariant`() {
        val cases = listOf(
            "I think we should go" to "go now",
            "What is the que" to "question is",
            "First sentence." to "Second sentence.",
            "Let us begin" to "begin the lesson now",
            "Hello world" to "Hello world"
        )
        for ((prev, next) in cases) {
            val merged = TranscriptMerger.merge(prev, next)
            assertTrue(
                "result must start with prev: prev='$prev' merged='$merged'",
                merged.startsWith(prev)
            )
        }
    }

    @Test
    fun `looksTruncated detects missing terminal punctuation`() {
        assertTrue(TranscriptMerger.looksTruncated("Stand up. What is"))
        assertTrue(!TranscriptMerger.looksTruncated("Stand up. What is this?"))
    }

    @Test
    fun `looksTruncated handles empty text`() {
        assertTrue(TranscriptMerger.looksTruncated(""))
    }

    @Test
    fun `merge preserves the full real world case`() {
        // 用户截图里的真实句子
        val a = "Okay, I will get back to you. Stand up."
        val b = "What is the meaning of this one?"
        val merged = TranscriptMerger.merge(a, b)
        assertTrue("merged should contain both halves: $merged", merged.contains("get back to you"))
        assertTrue("merged should contain tail: $merged", merged.contains("meaning of this one"))
        assertTrue("merged should start with prev: $merged", merged.startsWith(a))
    }
}
