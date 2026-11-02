package com.classroom.translator.repair

import android.content.Context
import android.util.Log
import com.classroom.translator.data.AppSettings
import com.classroom.translator.local.LocalLlmServerManager
import com.classroom.translator.local.ModelStore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 自修复工具 —— 诊断引擎。
 *
 * 设计原则：**能自动修的就别问用户**。
 * 每条诊断都带一个「修复动作」，由 [RepairAction] 描述；修复器按顺序执行，
 * 修完再体检一次，用「问题消失」作为成功判据，而不是相信动作本身。
 *
 * 为什么要单独做这个：用户在这上面已经踩过两个坑
 *  （1）切翻译引擎报 401，界面只给一句"连接错误"；
 *   （2）清除应用数据后模型没了，界面只给"本地模型或可执行文件缺失"。
 * 两次都是"App 知道自己坏了，但不知道自己坏在哪、不知道怎么自己好"。
 */
class Diagnostics(private val context: Context) {

    companion object {
        private const val TAG = "Diagnostics"
    }

    /** 体检项的状态 */
    enum class Severity { OK, WARN, FAIL }

    /** 一条诊断结论 */
    data class Finding(
        val id: String,
        val title: String,
        val detail: String,
        val severity: Severity,
        /** 自动修复动作；null = 需要人工介入 */
        val autoFixable: Boolean = false,
        /** 修复动作（在 App 进程内执行） */
        val fix: (suspend () -> Boolean)? = null,
    )

    /** 体检报告 */
    data class Report(
        val findings: List<Finding>,
        val healthScore: Int,
        val checkedAt: Long = System.currentTimeMillis(),
    ) {
        val hasFail: Boolean get() = findings.any { it.severity == Severity.FAIL }
        val hasWarn: Boolean get() = findings.any { it.severity == Severity.WARN }
        val failCount: Int get() = findings.count { it.severity == Severity.FAIL }
        val warnCount: Int get() = findings.count { it.severity == Severity.WARN }
        /** 给 LLM 看的紧凑上下文 */
        fun toPromptContext(): String = findings.joinToString("\n") {
            "[${it.severity}] ${it.title}: ${it.detail}"
        }
    }

    private val modelStore = ModelStore(context)
    private val serverMgr = LocalLlmServerManager.get(context.applicationContext as android.app.Application)

    /**
     * 跑一次完整体检。
     * @param settings 当前设置（用于判断配置是否指向可用端点）
     */
    suspend fun runFullCheck(settings: AppSettings): Report {
        val findings = mutableListOf<Finding>()

        // ── 1. 本地推理可执行文件 ──
        findings += checkBinary()
        // ── 2. 模型完整性 ──
        findings += checkModels()
        // ── 3. 本地服务存活 ──
        findings += checkLocalServices()
        // ── 4. 云端配置 ──
        findings += checkCloudConfig(settings)
        // ── 5. 本地翻译端点配置 ──
        findings += checkLocalTranslateConfig(settings)
        // ── 6. 识别端点配置 ──
        findings += checkAsrConfig(settings)
        // ── 7. 存储健康 ──
        findings += checkStorage()

        val score = score(findings)
        return Report(findings, score)
    }

    /**
     * 「这一项可以由修复器自动纠正」的占位动作。
     *
     * 真正的重置在 [RepairEngine.repairConfig] 里做（它要同时改多个字段）。
     * 这里返回一个常量，避免每个分支各写一份 lambda 造成类型推断歧义。
     */
    private suspend fun resetDefault(): Boolean = true

    // ------------------------------------------------------------------
    // 各体检项
    // ------------------------------------------------------------------

    private fun checkBinary(): Finding {
        val bin = File(context.applicationInfo.nativeLibraryDir, "libllamasrv.so")
        return if (bin.exists()) {
            Finding("binary", "本地推理引擎", "已就绪（${bin.length() / 1024 / 1024} MB）", Severity.OK)
        } else {
            Finding(
                "binary", "本地推理引擎",
                "缺少可执行文件 libllamasrv.so —— 需要重新安装 App（该文件随 APK 分发，无法在线补）",
                Severity.FAIL, autoFixable = false
            )
        }
    }

    private fun checkModels(): Finding {
        val states = modelStore.allStates()
        val broken = states.filter { !it.ok }
        val dir = modelStore.modelDir()
        if (broken.isEmpty()) {
            return Finding(
                "models", "本地模型",
                "3 个模型齐全（${modelStore.human(modelStore.totalBytes)}），目录 ${dir.absolutePath}",
                Severity.OK
            )
        }
        val detail = broken.joinToString("；") { it.problem() ?: "" }
        val durable = modelStore.isDurable()
        val where = if (durable) "共享存储（抗清除数据）" else "App 专属目录（会被清除数据删掉）"
        return Finding(
            "models", "本地模型",
            "$detail ｜ 当前目录：$where ｜ 可自动修复：重新下载（${modelStore.human(modelStore.totalBytes)}）",
            Severity.FAIL, autoFixable = true,
            fix = { fixModels() }
        )
    }

    /** 修复动作：迁移 + 重新下载缺失模型 */
    private suspend fun fixModels(): Boolean {
        modelStore.migrateFromLegacy()
        var ok = true
        for (spec in modelStore.specs) {
            if (modelStore.stateOf(spec).ok) continue
            if (!modelStore.download(spec)) ok = false
        }
        return ok && modelStore.allReady()
    }

    private fun checkLocalServices(): Finding {
        val text = serverMgr.statusText()
        val asrOk = serverMgr.isAsrHealthy()
        val transOk = serverMgr.isTransHealthy()
        return when {
            asrOk && transOk -> Finding("services", "本地推理服务", "运行中（$text）", Severity.OK)
            serverMgr.isRunning() -> Finding(
                "services", "本地推理服务",
                "正在启动但未就绪（$text）—— 模型加载需要 10~30 秒，App 会自动重试",
                Severity.WARN, autoFixable = true,
                fix = { serverMgr.restartServices() }
            )
            else -> Finding(
                "services", "本地推理服务",
                "未运行（$text）—— 点「开始录音」时会自动拉起，也可手动重启",
                Severity.WARN, autoFixable = true,
                fix = { serverMgr.restartServices() }
            )
        }
    }

    private fun checkCloudConfig(settings: AppSettings): Finding {
        val key = settings.cloudApiKey
        val url = settings.cloudBaseUrl
        val model = settings.cloudModel
        return when {
            key.isBlank() -> Finding(
                "cloud", "云端覆盖引擎",
                "未配置密钥 —— 本地翻译照常工作，但云端高质量覆盖与课堂总结会不可用",
                Severity.WARN, autoFixable = false
            )
            url.isBlank() || model.isBlank() -> Finding(
                "cloud", "云端覆盖引擎", "接口地址或模型名为空，无法调用", Severity.FAIL, autoFixable = false
            )
            else -> Finding(
                "cloud", "云端覆盖引擎", "已配置（$model @ $url）", Severity.OK
            )
        }
    }

    private fun checkLocalTranslateConfig(settings: AppSettings): Finding {
        val url = settings.translateBaseUrl
        return when {
            url.isBlank() -> Finding(
                "translate", "本地翻译引擎", "接口地址为空", Severity.FAIL, autoFixable = true,
                fix = { resetDefault() }   // 修复器会重置为默认
            )
            !url.contains("127.0.0.1") && !url.contains("localhost") && !url.contains("192.168.") && !url.contains("10.") ->
                Finding(
                    "translate", "本地翻译引擎",
                    "指向了外部地址 $url —— 若非有意，会把课堂音频/文本发到外部服务",
                    Severity.WARN, autoFixable = true, fix = { resetDefault() }
                )
            else -> Finding("translate", "本地翻译引擎", "本地端点（$url）", Severity.OK)
        }
    }

    private fun checkAsrConfig(settings: AppSettings): Finding {
        val url = settings.asrBaseUrl
        val mode = settings.asrMode
        // 【2026-11-02 修复】本地推理是唯一路径；若配置仍指向云端 ASR，明确纠正。
        if (mode != "local_vllm") {
            return Finding(
                "asr", "语音识别引擎",
                "识别模式被改成了 $mode —— 本 App 只支持本地推理识别，请恢复",
                Severity.FAIL, autoFixable = true, fix = { resetDefault() }
            )
        }
        if (url.contains("dashscope") || url.contains("xiaomimimo") || url.contains("aliyuncs")) {
            return Finding(
                "asr", "语音识别引擎",
                "接口地址仍指向云端 ASR（$url）—— 这正是 401 的来源，请改回本地",
                Severity.FAIL, autoFixable = true, fix = { resetDefault() }
            )
        }
        return Finding("asr", "语音识别引擎", "本地 Qwen3-ASR（$url）", Severity.OK)
    }

    private fun checkStorage(): Finding {
        val free = modelStore.freeSpaceBytes()
        val need = modelStore.totalBytes
        return when {
            free < 0 -> Finding("storage", "存储空间", "无法读取剩余空间", Severity.WARN)
            free < need -> Finding(
                "storage", "存储空间",
                "剩余 ${modelStore.human(free)}，模型需要 ${modelStore.human(need)} —— 空间不足会导致下载失败",
                Severity.FAIL, autoFixable = false
            )
            free < need * 3 -> Finding(
                "storage", "存储空间",
                "剩余 ${modelStore.human(free)}，下载 ${modelStore.human(need)} 后余量不多",
                Severity.WARN, autoFixable = false
            )
            else -> Finding("storage", "存储空间", "剩余 ${modelStore.human(free)}，充足", Severity.OK)
        }
    }

    /** 健康分：OK=100/项，WARN=60/项，FAIL=0/项，取平均 */
    private fun score(findings: List<Finding>): Int {
        if (findings.isEmpty()) return 100
        // 用 fold 而不是 sumOf —— sumOf 有 Int/Long/Double 多个重载，
        // 这里 when 的分支会被推断成歧义类型（Kotlin 2.0 报错）。
        val total: Int = findings.fold(0) { acc, f ->
            acc + when (f.severity) {
                Severity.OK -> 100
                Severity.WARN -> 60
                Severity.FAIL -> 0
            }
        }
        return total / findings.size
    }
}
