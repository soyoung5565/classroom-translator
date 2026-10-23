package com.classroom.translator.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.classroom.translator.ui.screens.HistoryScreen
import com.classroom.translator.ui.screens.MainScreen
import com.classroom.translator.ui.screens.SessionDetailScreen
import com.classroom.translator.ui.screens.SettingsScreen
import com.classroom.translator.ui.screens.WholeSessionScreen

@Composable
fun MainNavHost() {
    val navController = rememberNavController()
    val sharedViewModel: TranslatorViewModel = viewModel()
    // 供 MainActivity 传递 MediaProjection（测试模式内录）
    (navController.context.applicationContext as com.classroom.translator.TranslatorApp).viewModel = sharedViewModel

    // [debug-only] 设计稿验证种子：`adb shell am start --ez seed_demo true` 时注入 5 条示例条目，
    // 用于模拟器截图与设计稿逐屏比对；release 构建不含此路径，不影响正常交互。
    if (com.classroom.translator.BuildConfig.DEBUG) {
        val ctx = LocalContext.current
        LaunchedEffect(Unit) {
            val intent = (ctx as? android.app.Activity)?.intent
            // [debug-only] 录音必保存自检：`--ez selftest_record true`
            // 走完整链路：开始录音 → 停止 → 归档 → 落盘。
            // 用于在真机上验证「零识别也必须保存录音」这条规则（用户 2026-10-20 反馈）。
            //
            // 【2026-10-24】时长可调：`--ei selftest_record_ms 30000`（毫秒，默认 3000）。
            // 短时长用来快速验证"零识别也保存"，长时长用来验证 AGC 增益链
            // （需要跑完整段真实录音才能看到小声段被抬起来）。
            if (intent?.getBooleanExtra("selftest_record", false) == true) {
                val recordMs = intent.getIntExtra("selftest_record_ms", 3000).coerceIn(500, 120_000)
                android.util.Log.i("SelfTest", "=== 录音保存自检开始 (${recordMs}ms) ===")
                sharedViewModel.startNewSession()
                delay(500)
                sharedViewModel.startRecordingForTest()
                delay(recordMs.toLong())
                sharedViewModel.stopRecordingForTest()
                delay(1500)
                android.util.Log.i("SelfTest", "=== 录音保存自检结束 ===")
            }
            if (intent?.getBooleanExtra("seed_demo", false) == true &&
                sharedViewModel.state.value.entries.isEmpty()
            ) {
                sharedViewModel.startNewSession()
                delay(300)
                val demo = listOf(
                    Triple("Open your readers to page 42 — let's quickly review the three key points from last class.", "大家先把阅读材料翻到第 42 页,我们快速回顾一下上节课的三个重点。", "14:18"),
                    Triple("Note that 'elaborate' means more than 'explain in detail' — it implies supporting evidence.", "注意 \"elaborate\" 这个词,不只是「详细说明」,它还暗示要有支撑细节。", "14:22"),
                    Triple("Who'd like to make a sentence with this pattern? Describe one of your recent group discussions.", "下面谁来用这个句型造个句子?可以描述你最近一次小组讨论。", "14:26"),
                    Triple("Nice. Watch the tense — for a discussion that already happened, use past simple or present perfect.", "很好。注意时态——讲已经发生的讨论,要用一般过去时或现在完成时。", "14:29"),
                    Triple("One more round — in pairs this time: one asks, one answers, and mind your intonation.", "我们再练一轮,两人一组,一人提问一人回答,注意语音语调。", "14:32"),
                )
                demo.forEach { (en, zh, ts) ->
                    sharedViewModel.addOrMergeEntry(en, zh, ts)
                    delay(600)
                }
                sharedViewModel.seedRecordingUi()
                navController.navigate("recording")
            }
            // [debug-only] 历史页样例数据：`--ez seed_history true` 注入 4 条会话（其一含音频，
            // 覆盖列表态 + 详情页（含播放条）验证）；release 构建不含此路径。
            if (intent?.getBooleanExtra("seed_history", false) == true) {
                val sm = com.classroom.translator.data.SessionManager(ctx)
                val detailSeeds = listOf(
                    Pair("Let's look at the first paragraph — notice the transition word the author uses here.", "我们先看第一段,注意作者在这里用的转折词。"),
                    Pair("When 'however' shows up, the real argument usually follows.", "\"However\" 出现的时候,后面往往才是真正的论点。"),
                    Pair("So the central idea of this paragraph is simply: conclusion first.", "所以这段的中心思想,其实就是「结论前置」。"),
                    Pair("Next, in pairs, retell the previous paragraph using this structure.", "接下来两人一组,用这个结构复述上一段。"),
                )
                fun mkEntries(n: Int, startSec: Int, spanSec: Int): List<com.classroom.translator.data.TranslationEntryData> {
                    val out = ArrayList<com.classroom.translator.data.TranslationEntryData>()
                    for (i in 0 until n) {
                        val tt = startSec + if (n > 1) spanSec * i / (n - 1) else 0
                        val off = if (n > 1) spanSec * i / (n - 1) else 0
                        val offEnd = if (n > 1) spanSec * (i + 1) / (n - 1) else spanSec
                        val ts = "%02d:%02d:%02d".format(tt / 3600, (tt % 3600) / 60, tt % 60)
                        val seed = detailSeeds[i % detailSeeds.size]
                        out.add(com.classroom.translator.data.TranslationEntryData(timestamp = ts, english = seed.first, chinese = seed.second, startSec = off, endSec = offEnd))
                    }
                    return out
                }
                val df = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                val cal = java.util.Calendar.getInstance()
                val today = df.format(cal.time)
                cal.add(java.util.Calendar.DAY_OF_YEAR, -2); val d2 = df.format(cal.time)
                cal.add(java.util.Calendar.DAY_OF_YEAR, -1); val d3 = df.format(cal.time)
                val audioFile = java.io.File(ctx.getExternalFilesDir(null), "recordings/demo_lesson.wav")
                if (!audioFile.exists()) writeDemoWav(audioFile, 2600)
                val audio = audioFile.absolutePath
                sm.saveSession(com.classroom.translator.data.TranslationSession(id = 9001L, date = today, startTime = "14:05", endTime = "14:47", entries = mkEntries(128, 12 * 3600 + 18 * 60, 42 * 60 + 17), audioPath = audio))
                sm.saveSession(com.classroom.translator.data.TranslationSession(id = 9002L, date = today, startTime = "12:18", endTime = "12:56", entries = mkEntries(96, 14 * 3600 + 5 * 60, 38 * 60 + 5)))
                sm.saveSession(com.classroom.translator.data.TranslationSession(id = 9003L, date = d2, startTime = "09:12", endTime = "09:58", entries = mkEntries(141, 9 * 3600 + 12 * 60, 45 * 60 + 32)))
                sm.saveSession(com.classroom.translator.data.TranslationSession(id = 9004L, date = d3, startTime = "15:40", endTime = "16:20", entries = mkEntries(89, 15 * 3600 + 40 * 60, 40 * 60 + 11)))
            }
        }
    }

    NavHost(navController = navController, startDestination = "history") {
        composable("history") {
            HistoryScreen(
                onNavigateToRecording = {
                    sharedViewModel.startNewSession()
                    navController.navigate("recording")
                },
                onNavigateToSession = { sessionId ->
                    navController.navigate("session_detail/$sessionId")
                },
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToWholeSession = { navController.navigate("whole_session") }
            )
        }
        composable("recording") {
            MainScreen(
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToHistory = { navController.navigate("history") },
                viewModel = sharedViewModel
            )
        }
        composable(
            "session_detail/{sessionId}",
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType })
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: 0L
            SessionDetailScreen(
                sessionId = sessionId,
                onBack = { navController.popBackStack() },
                viewModel = sharedViewModel,
            )
        }
        composable("whole_session") {
            WholeSessionScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                viewModel = sharedViewModel
            )
        }
    }
}

// [debug-only] 生成演示 WAV：8kHz/16bit/单声道，每 30 秒一个短提示音。
// 用于验证「课堂总结 → 时间区间 → 跳转回放」链路（--ez seed_history true 时调用）。
private fun writeDemoWav(file: java.io.File, seconds: Int) {
    try {
        if (file.exists() && file.length() > 44) return
        file.parentFile?.mkdirs()
        val sampleRate = 8000
        val total = sampleRate * seconds
        val out = java.io.BufferedOutputStream(file.outputStream(), 1 shl 16)
        fun le32(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(), ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte())
        fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())
        val dataLen = total * 2
        out.write("RIFF".toByteArray())
        out.write(le32(36 + dataLen))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(le32(16))
        out.write(le16(1))
        out.write(le16(1))
        out.write(le32(sampleRate))
        out.write(le32(sampleRate * 2))
        out.write(le16(2))
        out.write(le16(16))
        out.write("data".toByteArray())
        out.write(le32(dataLen))
        val buf = ByteArray(total * 2)
        var idx = 0
        for (t in 0 until total) {
            val posInStep = t % (30 * sampleRate)
            val v = if (posInStep < sampleRate / 3) {
                (Math.sin(2.0 * Math.PI * 880.0 * t / sampleRate) * 8000).toInt()
            } else 0
            buf[idx++] = (v and 0xff).toByte()
            buf[idx++] = ((v shr 8) and 0xff).toByte()
        }
        out.write(buf)
        out.close()
        android.util.Log.i("Seed", "demo wav written: ${file.length()} bytes, ${seconds}s")
    } catch (e: Exception) {
        android.util.Log.e("Seed", "demo wav failed", e)
    }
}
