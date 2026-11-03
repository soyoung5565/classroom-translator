package com.classroom.translator.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LocalVllmAsrStream.cleanAsrOutput 单元测试（2026-11-03）
 *
 * 修复的真实 bug：原第 3 步用 `^(english|chinese|malay)\b\s*` 无条件剥掉开头的语言名。
 * 老师上课第一句就可能是 "English is easy to learn." → 被剥成 "is easy to learn."
 * → 界面上凭空少一个词 = 用户说的「识别结果没有显示完整」。
 */
class CleanAsrOutputTest {

    private fun clean(raw: String): String = LocalVllmAsrStream.cleanAsrOutput(raw)

    @Test
    fun `keeps sentence that starts with English`() {
        assertEquals("English is easy to learn.", clean("English is easy to learn."))
    }

    @Test
    fun `keeps sentence that starts with Chinese`() {
        assertEquals("Chinese characters are hard.", clean("Chinese characters are hard."))
    }

    @Test
    fun `strips explicit language label with colon`() {
        assertEquals("hello world", clean("English: hello world"))
        assertEquals("hello world", clean("English：hello world"))
    }

    @Test
    fun `strips language prefix`() {
        assertEquals("hello world", clean("language English hello world"))
        assertEquals("hello world", clean("lang English hello world"))
    }

    @Test
    fun `strips asr_text marker`() {
        assertEquals("hello world", clean("<asr_text>hello world"))
        assertEquals("hello world", clean("<|asr_text|>hello world"))
    }

    @Test
    fun `empty when output is only a language name`() {
        assertEquals("", clean("English"))
        assertEquals("", clean("Chinese"))
    }

    @Test
    fun `real classroom case is preserved in full`() {
        val raw = "English is not that difficult to learn, right?"
        val out = clean(raw)
        assertTrue("must not eat the first word: $out", out.startsWith("English"))
        assertEquals(raw, out)
    }
}
