package com.classroom.translator.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「录音必保存」回归测试（2026-10-20）。
 *
 * 锁死用户反馈的规则：**只要点了开始就要录，结束就要保存** ——
 * 即使一个��都没识别出来，录音也必须归档，不能丢。
 */
class SessionArchivePolicyTest {

    /** 零识别但有录音文件 → 必须归档（核心回归） */
    @Test
    fun zeroEntriesButWithAudioIsArchived() {
        assertTrue(
            "零识别但有录音时必须保存（用户原话：结束就要保存）",
            SessionArchivePolicy.shouldArchive(
                entryCount = 0,
                hasAudioFile = true,
                alreadyPersisted = false
            )
        )
    }

    /** 有识别内容 → 归档 */
    @Test
    fun withEntriesIsArchived() {
        assertTrue(SessionArchivePolicy.shouldArchive(3, false, false))
        assertTrue(SessionArchivePolicy.shouldArchive(3, true, false))
    }

    /** 什么都没有 → 不归档（历史里不留空记录） */
    @Test
    fun nothingAtAllIsNotArchived() {
        assertFalse(
            SessionArchivePolicy.shouldArchive(
                entryCount = 0,
                hasAudioFile = false,
                alreadyPersisted = false
            )
        )
    }

    /** 同一段录音只归档一次（防止"停止 + 点完成"把好数据用空数据覆盖） */
    @Test
    fun alreadyPersistedIsNotArchivedAgain() {
        assertFalse(
            "已归档过的录音不能重复归档",
            SessionArchivePolicy.shouldArchive(
                entryCount = 0,
                hasAudioFile = true,
                alreadyPersisted = true
            )
        )
        assertFalse(SessionArchivePolicy.shouldArchive(5, true, true))
    }

    /** 摘要文案：零识别时要说清楚"录了但没识别出来" */
    @Test
    fun summaryTellsUserWhatHappened() {
        val zero = SessionArchivePolicy.summaryFor(0, "")
        assertTrue("零识别摘要应说明未识别到内容，实际：$zero", zero.contains("未识别"))

        val normal = SessionArchivePolicy.summaryFor(2, "Hello world")
        assertEquals("Hello world", normal)

        val long = SessionArchivePolicy.summaryFor(1, "x".repeat(80))
        assertTrue("长摘要应截断，实际长度 ${long.length}", long.length <= 53)
        assertTrue(long.endsWith("..."))
    }
}
