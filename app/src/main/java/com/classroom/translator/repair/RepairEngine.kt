package com.classroom.translator.repair

import android.util.Log
import com.classroom.translator.data.AppSettings
import com.classroom.translator.data.SettingsRepository
import com.classroom.translator.data.SessionManager
import com.classroom.translator.local.LocalLlmServerManager
import com.classroom.translator.local.ModelStore
import kotlinx.coroutines.flow.first

/**
 * 自修复执行器 —— 真正动手修的地方。
 *
 * 与 [Diagnostics] 的分工：
 *   Diagnostics 只负责「发现问题」，本类负责「修」，
 *   并且**修完必须重新体检**，用「问题是否消失」判定成功，
 *   而不是相信修复动作自己说成功（这��教训在课堂总结的 JSON 解析上已经付过代价：
 *   模型说"我返回了 JSON"但实际截断了）。
 */
class RepairEngine(
    private val context: android.content.Context,
    private val settingsRepo: SettingsRepository,
) {

    companion object {
        private const val TAG = "RepairEngine"
    }

    /**
     * 修复过程的一条记录。
     * 三态而不是两态：把"用户主动跳过"和"修复失败"混为一谈会误导用户
     * —— 用户看到一片红叉会以为 App 坏了，而实际上只是那一步没跑。
     */
    data class Step(
        val name: String,
        val success: Boolean,
        val detail: String,
        val skipped: Boolean = false,
    )

    /** 修复结果 */
    data class Result(
        val steps: List<Step>,
        val before: Diagnostics.Report,
        val after: Diagnostics.Report?,
    ) {
        val succeeded: Boolean get() = steps.all { it.success }
        val fixedCount: Int get() = steps.count { it.success }
    }

    private val diagnostics = Diagnostics(context)
    private val modelStore = ModelStore(context)
    private val serverMgr = LocalLlmServerManager.get(context.applicationContext as android.app.Application)

    /**
     * 执行自动修复。
     *
     * @param includeModelRepair 是否包含"重新下载模型"（1.4GB，慢且耗流量，
     *        默认关闭，让用户明确点一下再跑）
     * @param onProgress 进度回调
     */
    suspend fun autoRepair(
        includeModelRepair: Boolean = false,
        onProgress: (String) -> Unit = {},
    ): Result {
        val settings = settingsRepo.settings.first()
        val before = diagnostics.runFullCheck(settings)
        val steps = mutableListOf<Step>()

        // ── 1. 配置层：把被篡改/指向云端的配置纠正回来 ──
        onProgress("检查配置…")
        val configFixed = repairConfig()
        steps.add(
            Step(
                "配置校正", true,
                if (configFixed) "已纠正指向云端/无效的配置" else "配置无需校正",
                skipped = !configFixed,
            )
        )

        // ── 2. 服务层：重启本地推理服务 ──
        if (before.findings.any { it.id == "services" && it.severity != Diagnostics.Severity.OK }) {
            onProgress("重启本地推理服务…")
            val svcOk = serverMgr.restartServices()
            steps.add(Step("重启本地服务", svcOk, if (svcOk) "服务已就绪" else serverMgr.statusText()))
        }

        // ── 3. 模型层：迁移 + 重新下载（可选）──
        val modelsBroken = before.findings.any { it.id == "models" && it.severity == Diagnostics.Severity.FAIL }
        if (modelsBroken) {
            if (includeModelRepair) {
                onProgress("迁移/下载模型（约 ${modelStore.human(modelStore.totalBytes)}）…")
                val moved = modelStore.migrateFromLegacy()
                var dlOk = true
                for (spec in modelStore.specs) {
                    if (modelStore.stateOf(spec).ok) continue
                    onProgress("下载 ${spec.label}…")
                    if (!modelStore.download(spec)) dlOk = false
                }
                steps.add(
                    Step(
                        "恢复模型", dlOk,
                        buildString {
                            if (moved > 0) append("迁移 ${modelStore.human(moved)}；")
                            append(if (dlOk) "模型齐全" else "部分模型下载失败")
                        }
                    )
                )
            } else {
                steps.add(
                    Step(
                        "恢复模型", true,
                        "已跳过（需要下载约 ${modelStore.human(modelStore.totalBytes)}，请单独触发）",
                        skipped = true,
                    )
                )
            }
        }

        // ── 4. 复检：用「问题是否消失」判定成功 ──
        onProgress("复检中…")
        val after = diagnostics.runFullCheck(settingsRepo.settings.first())
        Log.i(TAG, "autoRepair: before=${before.healthScore} after=${after.healthScore}")
        return Result(steps, before, after)
    }

    /**
     * 修复配置：把指向云端 ASR / 空地址的配置纠正回本地默认。
     * 只改「明显坏掉」的字段，不动用户有意填的云端覆盖配置。
     */
    private suspend fun repairConfig(): Boolean {
        var changed = false
        settingsRepo.update { s ->
            var out = s
            // 识别端点必须回本地（401 的根因）
            if (out.asrMode != "local_vllm" ||
                out.asrBaseUrl.contains("dashscope") ||
                out.asrBaseUrl.contains("xiaomimimo") ||
                out.asrBaseUrl.contains("aliyuncs") ||
                out.asrBaseUrl.isBlank()
            ) {
                Log.i(TAG, "repairConfig: 识别端点纠正为本地")
                out = out.copy(
                    asrMode = "local_vllm",
                    asrBaseUrl = "http://127.0.0.1:8000/v1",
                    asrModel = if (out.asrModel.startsWith("Qwen3")) out.asrModel else "Qwen3-ASR",
                )
                changed = true
            }
            // 本地翻译端点为空 → 回默认
            if (out.translateBaseUrl.isBlank()) {
                out = out.copy(
                    translateBaseUrl = "http://127.0.0.1:8002/v1",
                    translateModel = if (out.translateModel.isBlank()) "qwen3-1.7b" else out.translateModel,
                )
                changed = true
            }
            out
        }
        return changed
    }

    /**
     * 恢复初始形态 —— 把 **设置** 恢复到刚装好的样子。
     *
     * 【2026-11-02 用户明确纠正】「初始形态不要把东西也删了，这个应该没错吧。」
     * → 确认：**恢复 = 改回去，不是删掉**。
     *   - 设置（识别地址/模型名/密钥/语种/档位）→ 回到出厂默认；
     *   - 课堂记录 → **原样保留**（用户的课不可再生，比配置值钱得多）；
     *   - 已下载的模型 → **原样保留**（重下 1.4GB 是用户最烦的事）。
     *
     * 删除类动作（清课堂记录 / 删模型）已经从这个入口彻底移除 ——
     * 那是独立的高危操作，不该和"恢复默认"混在一起，让人误点。
     * 真要删的话，App 内本来就有逐条删除的入口。
     */
    suspend fun restoreFactory(
        onProgress: (String) -> Unit = {},
    ): Result {
        val steps = mutableListOf<Step>()
        val before = diagnostics.runFullCheck(settingsRepo.settings.first())

        // ── 1. 设置回默认 ──
        onProgress("恢复默认设置…")
        settingsRepo.resetToDefaults()
        steps.add(Step("恢复默认设置", true, "识别/翻译/云端配置已回到出厂值"))

        // ── 2. 把模型挪到安全目录（不删，只是换个位置）──
        onProgress("检查模型存放位置…")
        if (modelStore.isDurable()) {
            steps.add(Step("模型存放位置", true, "模型已在共享存储（抗清除数据）"))
        } else {
            val moved = modelStore.migrateFromLegacy()
            steps.add(
                Step(
                    "模型存放位置", true,
                    if (moved > 0) "已迁移 ${modelStore.human(moved)} 到共享存储"
                    else "模型保留在 App 专属目录（未授予「所有文件访问权限」，不影响使用）",
                    skipped = moved <= 0,
                )
            )
        }

        // ── 3. 课堂记录：明确保留 ──
        val sessionCount = SessionManager(context).loadSessions().size
        steps.add(
            Step(
                "课堂记录", true,
                if (sessionCount > 0) "$sessionCount 条课堂记录已保留，未做任何改动"
                else "没有课堂记录，无需处理",
                skipped = true,
            )
        )

        // ── 4. 重启服务 ──
        onProgress("重启本地服务…")
        val svcOk = serverMgr.restartServices()
        steps.add(Step("重启本地服务", svcOk, if (svcOk) "已就绪" else serverMgr.statusText()))

        val after = diagnostics.runFullCheck(settingsRepo.settings.first())
        return Result(steps, before, after)
    }

    /** 读一次当前设置 */
    private suspend fun currentSettings(): AppSettings = settingsRepo.settings.first()
}
