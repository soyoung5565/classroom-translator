package com.classroom.translator.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 课堂总结解析加固回归测试（2026-09-29）。
 * 覆盖此前线上出过的"总结解析失败（模型输出非 JSON）"各类形态：
 * 前后夹带说明文字 / markdown 围栏 / think 块 / 截断（max_tokens 用尽）/
 * 尾逗号 / 智能引号 / 单引号——解析都必须成功（或对垃圾输出返回 null 交给重试）。
 */
class ClassSummaryParseTest {

    private val entries: List<TranslationEntryData> = (0 until 10).map { i ->
        TranslationEntryData(
            timestamp = "14:%02d".format(i * 3),
            english = "segment $i english",
            chinese = "第 $i 段中文",
            startSec = i * 20,
            endSec = i * 20 + 18,
        )
    }

    private fun parse(raw: String) =
        ClassSummary.parseModelOutput(raw, entries, "14:00", "deepseek-v4-1-flash")

    private val good = """
        {
          "overview": "本节课回顾了上节课的重点并展开第42页阅读。",
          "points": [
            {"title": "elaborate 的深层含义", "detail": "强调要有支撑细节。", "at": 1},
            {"title": "时态选择", "detail": "已发生讨论用过去时。", "at": 3}
          ],
          "explainers": [
            {"term": "elaborate", "kind": "vocab", "exp": "不只是详细说明,还暗示要有支撑证据", "at": 1}
          ],
          "homework": ["用三个连接词改写第二段"],
          "questions": [
            {"q": "被动语态何时使用?", "a": "焦点选择。", "at": 8}
          ],
          "keywords": ["elaborate", "transitional phrases"]
        }
    """.trimIndent()

    @Test
    fun cleanJsonParses() {
        val s = parse(good)
        assertNotNull(s)
        assertEquals("本节课回顾了上节课的重点并展开第42页阅读。", s!!.overview)
        assertEquals(2, s.points.size)
        assertEquals("elaborate 的深层含义", s.points[0].title)
        // at:1 → entries[1].startSec = 20
        assertEquals(20, s.points[0].startSec)
        assertEquals("用三个连接词改写第二段", s.homework[0])
        assertEquals(1, s.questions.size)
        assertEquals(160, s.questions[0].startSec)
        assertEquals(2, s.keywords.size)
        assertEquals("deepseek-v4-1-flash", s.model)
    }

    @Test
    fun fencePlusPrefaceSuffix() {
        val s = parse("好的，以下是课堂总结：\n```json\n$good\n```\n希望对你有帮助！")
        assertNotNull(s)
        assertEquals("本节课回顾了上节课的重点并展开第42页阅读。", s!!.overview)
    }

    @Test
    fun thinkingBlockWithBracesIsStripped() {
        val s = parse("<think>先想一下 {\"overview\":\"假的\"} 这样写行不行</think>\n$good")
        assertNotNull(s)
        assertEquals("本节课回顾了上节课的重点并展开第42页阅读。", s!!.overview)
    }

    @Test
    fun decoyObjectBeforeRealOne() {
        val raw = "示例格式 {\"overview\":\"示例\"} 以下是正式结果：\n$good"
        val s = parse(raw)
        assertNotNull(s)
        assertEquals("本节课回顾了上节课的重点并展开第42页阅读。", s!!.overview)
    }

    @Test
    fun truncatedMidStructureIsRepaired() {
        // 模拟 finish_reason=length：JSON 在 questions 段前被截断
        val cut = good.substring(0, good.indexOf("\"questions\"") - 1)
        val s = parse(cut)
        assertNotNull(s)
        assertEquals("本节课回顾了上节课的重点并展开第42页阅读。", s!!.overview)
        assertEquals(2, s.points.size)
    }

    @Test
    fun truncatedMidStringIsRepaired() {
        // 截断点落在字符串中间（最恶劣的形态）——应回退到更早的断点抢救出前面字段
        val cut = good.substring(0, good.indexOf("不只是详细说明") + 3)
        val s = parse(cut)
        assertNotNull(s)
        assertEquals("本节课回顾了上节课的重点并展开第42页阅读。", s!!.overview)
    }

    @Test
    fun trailingCommasTolerated() {
        val raw = """
            {
              "overview": "概要",
              "points": [
                {"title": "T1", "detail": "D1", "at": 1,},
              ],
              "homework": ["h1",],
            }
        """.trimIndent()
        val s = parse(raw)
        assertNotNull(s)
        assertEquals("概要", s!!.overview)
        assertEquals(1, s.points.size)
    }

    @Test
    fun singleQuotesAndTrailingCommaTolerated() {
        val raw = "{'overview':'概要单引号','points':[{'title':'T','detail':'D','at':1,},],}"
        val s = parse(raw)
        assertNotNull(s)
        assertEquals("概要单引号", s!!.overview)
    }

    @Test
    fun smartQuoteDelimitersTolerated() {
        val raw = "{“overview”:“智能引号概要”,“points”:[{“title”:“T”,“detail”:“D”,“at”:2,},],}"
        val s = parse(raw)
        assertNotNull(s)
        assertEquals("智能引号概要", s!!.overview)
    }

    @Test
    fun garbageReturnsNull() {
        assertNull(parse("抱歉，我无法生成 JSON 总结。"))
        assertNull(parse(""))
        assertNull(parse("{\"unrelated\": true, \"note\": \"不是总结\"}"))
    }

    @Test
    fun duplicatedObjectStillParses() {
        val s = parse("$good\n\n$good")
        assertNotNull(s)
    }
}
