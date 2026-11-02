package com.classroom.translator.repair

import android.content.Context
import android.util.Log
import com.classroom.translator.data.SettingsRepository
import com.classroom.translator.local.LocalLlmServerManager
import com.classroom.translator.local.ModelStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自修复工具箱 —— AI 可以**真正动手**修复的地方。
 *
 * 【为什么要有这一层】
 * 之前版本的「AI 诊断」只会给建议，用户还得自己照着做。
 * 用户明确要求：「我要的不是可以给建议的 AI，我又没有工具可以调用，
 * 就不能让 AI 调用工具自修复吗」。
 *
 * 所以这里是**真实的工具**：每个工具都对应一段可执行的代码，
 * AI 通过 Function Calling 决定调用哪个、传什么参数，
 * 工具执行完把真实结果回喂给 AI，AI 据此决定下一步。
 *
 * 安全边界（重要）：
 *  - 工具只暴露**白名单**里的动作，AI 不能凭空造动作；
 *  - 每个工具都是幂等的（重复调用不会把 App 搞坏）；
 *  - 危险动作（删模型、清课堂记录）**不在** AI 的工具清单里，
 *    只能由用户在界面上明确勾选才执行 ——
 *    AI 有可能在误判时调用删除类工具，不该给它这个权限。
 */
class RepairToolbox(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
) {

    companion object {
        private const val TAG = "RepairToolbox"
    }

    /** 一次工具调用的执行结果 */
    data class ToolResult(
        val name: String,
        val success: Boolean,
        val message: String,
    ) {
        /** 回喂给 LLM 的文本 */
        fun toFeedBack(): String = buildString {
            append("工具 ").append(name).append(if (success) " 执行成功" else " 执行失败")
            append("：").append(message)
        }
    }

    private val modelStore = ModelStore(context)
    private val serverMgr = LocalLlmServerManager.get(context.applicationContext as android.app.Application)

    /**
     * 工具的 JSON Schema 定义（给 LLM 看）。
     * 顺序即 LLM 的偏好顺序 —— 把最可能用到的排前面。
     */
    fun toolSchemas(): List<JSONObject> = listOf(
        tool(
            name = "diagnose_app",
            desc = "对 App 做一次完整体检，返回所有检查项的当前状态（服务是否存活、模型是否齐全、配置是否正确、存储空间是否充足）。" +
                "当你不清楚当前状态时，先调用这个。",
            props = JSONObject(),
        ),
        tool(
            name = "restart_local_service",
            desc = "重启本地推理服务（语音识别 + 本地翻译）。当体检显示服务 DOWN 或未就绪时调用。" +
                "返回重启后的服务状态。",
            props = JSONObject()
                .put("reason", strProp("调用原因，说明依据体检结果的哪一项")),
        ),
        tool(
            name = "repair_configuration",
            desc = "纠正被改坏的配置：把识别引擎强制切回本地（local_vllm），把指向云端 ASR 的接口地址改回本地，" +
                "把空的本地翻译地址补上默认值。" +
                "当体检显示「语音识别引擎」异常、或接口地址指向 dashscope/xiaomimimo/aliyuncs 时调用。",
            props = JSONObject()
                .put("reason", strProp("调用原因")),
        ),
        tool(
            name = "download_missing_models",
            desc = "重新下载缺失或损坏的本地模型（约 1.4GB，支持断点续传）。" +
                "当体检显示「本地模型」缺失或大小异常时调用。下载需要几分钟，请耐心等待。",
            props = JSONObject()
                .put("reason", strProp("调用原因")),
        ),
        tool(
            name = "migrate_models_to_safe_storage",
            desc = "把本地模型从「App 专属目录」迁移到「共享存储目录」——" +
                "App 专属目录会被系统「清除应用数据」删除，共享存储不会。" +
                "当体检显示模型目录不安全时调用。迁移不需要重新下载。",
            props = JSONObject()
                .put("reason", strProp("调用原因")),
        ),
        tool(
            name = "verify_local_endpoints",
            desc = "直接向本地识别(8000)和本地翻译(8002)端点发一次请求，验证它们是否真的能工作。" +
                "当你想确认服务不只是「进程活着」而是真的能响应时调用。",
            props = JSONObject()
                .put("reason", strProp("调用原因")),
        ),
    )

    private fun strProp(desc: String): JSONObject = JSONObject()
        .put("type", "string")
        .put("description", desc)

    private fun tool(name: String, desc: String, props: JSONObject): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function", JSONObject()
                .put("name", name)
                .put("description", desc)
                .put(
                    "parameters", JSONObject()
                        .put("type", "object")
                        .put("properties", props)
                        .put("required", requiredList(props))
                )
        )

    /**
     * 从 properties 里取出参数名列表。
     *
     * ⚠️ 不能用 `JSONArray(props.keys())` —— JSONObject.keys() 返回的是 **Iterator**，
     * 不是 Collection，JSONArray 的构造器不收它（实测抛
     * "Not a primitive array: class java.util.LinkedHashMap$LinkedKeyIterator"）。
     * 必须手动遍历。
     */
    private fun requiredList(props: JSONObject): JSONArray {
        val arr = JSONArray()
        val it = props.keys()
        while (it.hasNext()) {
            arr.put(it.next())
        }
        return arr
    }

    /** 工具名白名单 —— AI 不能调用清单外的动作 */
    private val allowed = setOf(
        "diagnose_app",
        "restart_local_service",
        "repair_configuration",
        "download_missing_models",
        "migrate_models_to_safe_storage",
        "verify_local_endpoints",
    )

    /**
     * 执行一次工具调用。
     * @param name 工具名
     * @param arguments JSON 参数
     * @return 执行结果（失败也返回，不抛异常 —— 失败信息要回喂给 AI）
     */
    suspend fun execute(name: String, arguments: JSONObject): ToolResult {
        if (name !in allowed) {
            Log.w(TAG, "拒绝执行未知工具：$name")
            return ToolResult(name, false, "未知工具，已拒绝执行")
        }
        Log.i(TAG, "执行工具：$name args=$arguments")
        return try {
            when (name) {
                "diagnose_app" -> toolDiagnose()
                "restart_local_service" -> toolRestartService()
                "repair_configuration" -> toolRepairConfig()
                "download_missing_models" -> toolDownloadModels()
                "migrate_models_to_safe_storage" -> toolMigrateModels()
                "verify_local_endpoints" -> toolVerifyEndpoints()
                else -> ToolResult(name, false, "未实现的工具")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "工具 $name 执行异常", t)
            ToolResult(name, false, "执行异常：${t.message}")
        }
    }

    // ── 各工具实现 ───────────────────────────────────────────────

    private suspend fun toolDiagnose(): ToolResult {
        val settings = settingsRepo.settings.first()
        val report = Diagnostics(context).runFullCheck(settings)
        val lines = report.findings.joinToString("\n") { "- ${it.title}：${it.detail}" }
        return ToolResult(
            "diagnose_app", true,
            "健康分 ${report.healthScore}/100（严重 ${report.failCount}，警告 ${report.warnCount}）\n$lines",
        )
    }

    private suspend fun toolRestartService(): ToolResult {
        val ok = serverMgr.restartServices()
        val status = serverMgr.statusText()
        return ToolResult(
            "restart_local_service", ok,
            if (ok) "服务已就绪：$status" else "重启后仍未就绪：$status",
        )
    }

    private suspend fun toolRepairConfig(): ToolResult {
        val before = settingsRepo.settings.first()
        var changed = false
        settingsRepo.update { s ->
            var out = s
            if (out.asrMode != "local_vllm" ||
                out.asrBaseUrl.contains("dashscope") ||
                out.asrBaseUrl.contains("xiaomimimo") ||
                out.asrBaseUrl.contains("aliyuncs") ||
                out.asrBaseUrl.isBlank()
            ) {
                out = out.copy(
                    asrMode = "local_vllm",
                    asrBaseUrl = "http://127.0.0.1:8000/v1",
                    asrModel = if (out.asrModel.startsWith("Qwen3")) out.asrModel else "Qwen3-ASR",
                )
                changed = true
            }
            if (out.translateBaseUrl.isBlank()) {
                out = out.copy(
                    translateBaseUrl = "http://127.0.0.1:8002/v1",
                    translateModel = if (out.translateModel.isBlank()) "qwen3-1.7b" else out.translateModel,
                )
                changed = true
            }
            out
        }
        val after = settingsRepo.settings.first()
        val diff = buildString {
            if (before.asrBaseUrl != after.asrBaseUrl) {
                append("识别地址 ${before.asrBaseUrl} → ${after.asrBaseUrl}；")
            }
            if (before.asrMode != after.asrMode) {
                append("识别模式 ${before.asrMode} → ${after.asrMode}；")
            }
            if (before.translateBaseUrl != after.translateBaseUrl) {
                append("翻译地址 ${before.translateBaseUrl} → ${after.translateBaseUrl}；")
            }
        }
        return ToolResult(
            "repair_configuration", true,
            if (changed) "已纠正：$diff" else "配置本来就正确，无需改动",
        )
    }

    private suspend fun toolDownloadModels(): ToolResult {
        val broken = modelStore.brokenModels()
        if (broken.isEmpty()) {
            return ToolResult("download_missing_models", true, "所有模型都在，无需下载")
        }
        val need = broken.joinToString("、") { it.spec.label }
        var allOk = true
        val detail = mutableListOf<String>()
        for (spec in modelStore.specs) {
            if (modelStore.stateOf(spec).ok) continue
            val ok = modelStore.download(spec)
            if (!ok) allOk = false
            detail.add("${spec.label}${if (ok) "✓" else "✗"}")
        }
        return ToolResult(
            "download_missing_models", allOk,
            "需要下载：$need ｜ 结果：${detail.joinToString(" ")}",
        )
    }

    private suspend fun toolMigrateModels(): ToolResult {
        if (modelStore.isDurable()) {
            return ToolResult(
                "migrate_models_to_safe_storage", true,
                "模型已在共享存储目录（抗清除数据），无需迁移",
            )
        }
        val moved = modelStore.migrateFromLegacy()
        return ToolResult(
            "migrate_models_to_safe_storage", moved > 0 || modelStore.isDurable(),
            if (moved > 0) "已迁移 ${modelStore.human(moved)} 到共享存储"
            else "未能迁移（可能未授予「所有文件访问权限」）",
        )
    }

    private suspend fun toolVerifyEndpoints(): ToolResult {
        val settings = settingsRepo.settings.first()
        val asrOk = serverMgr.isAsrHealthy()
        val transOk = serverMgr.isTransHealthy()
        return ToolResult(
            "verify_local_endpoints", asrOk && transOk,
            "识别端点 ${settings.asrBaseUrl}：${if (asrOk) "正常" else "无响应"}；" +
                "翻译端点 ${settings.translateBaseUrl}：${if (transOk) "正常" else "无响应"}",
        )
    }
}
