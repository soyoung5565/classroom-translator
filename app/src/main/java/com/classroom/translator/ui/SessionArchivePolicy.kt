package com.classroom.translator.ui

/**
 * 会话归档决策（纯逻辑，无 Android 依赖 → 可 JVM 单元测试）。
 *
 * 【2026-10-20 起因】用户反馈："未识别到内容他就不保存录音了，
 * 应该是只要我点了开始就要录音，结束就要保存。"
 *
 * 根因（旧逻辑）：
 *  - stopRecording() 只停设备，**不落盘**；
 *  - 落盘完全依赖 finishSession()，而 finishSession() 在 entries 为空时**直接 return**；
 *  - 「完成」按钮又只在 count > 0 时才显示。
 *  ⇒ "点了开始但一个��都没识别出来"这一路，录音文件躺在磁盘上，
 *    却没有���何 session 指向它 → 历史列表里根本看不到 = 白录。
 *    （真机证据：2026-10-20 的两段录音 recording_20261020_205014.wav /
 *     205230.wav 存在，但 sessions/ 目录里没有对应的 session_*.json）
 *
 * 新规则：
 *  1. 停止录音 → **立刻**归档（哪怕零识别、哪怕零条目）；
 *  2. 同一段录音只归档一次（避免"停止 + 点完成"两次写，把好数据用空数据覆盖）；
 *  3. 什么��都没有（既无条目又无录音文件）→ 不归档（避免历史里出现空记录）。
 */
object SessionArchivePolicy {

    /**
     * 是否应当归档。
     * @param entryCount 已识别的翻译条目数
     * @param hasAudioFile 是否存在录音文件（哪怕零识别也有）
     * @param alreadyPersisted 本段录音是否已归档过
     */
    fun shouldArchive(
        entryCount: Int,
        hasAudioFile: Boolean,
        alreadyPersisted: Boolean
    ): Boolean {
        if (alreadyPersisted) return false       // 同一段只归档一次
        if (entryCount <= 0 && !hasAudioFile) return false   // 什么都没有 → 不留空记录
        return true
    }

    /**
     * 零识别时的会话摘要（历史列表会显示它，让用户知道"录了但没识别出来"）。
     */
    fun summaryFor(entryCount: Int, firstEnglish: String): String = when {
        entryCount > 0 && firstEnglish.isNotBlank() ->
            if (firstEnglish.length > 50) firstEnglish.take(50) + "..." else firstEnglish
        entryCount > 0 -> "录音已保存（$entryCount 条）"
        else -> "录音已保存（未识别到语音内容）"
    }
}
