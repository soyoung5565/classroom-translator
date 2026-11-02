package com.classroom.translator.repair

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.classroom.translator.api.OpenAiApi
import com.classroom.translator.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 自修复工具的界面状态。
 */
data class RepairUiState(
    /** 是否正在体检 */
    val diagnosing: Boolean = false,
    /** 体检报告 */
    val report: Diagnostics.Report? = null,
    /** AI 诊断建议（只给建议，不动手） */
    val advice: AiRepairAdvisor.Advice? = null,
    /** AI Agent 的执行结果（真的动手改了） */
    val agentResult: RepairAgent.Result? = null,
    /** 是否正在修复 */
    val repairing: Boolean = false,
    /** 修复结果 */
    val result: RepairEngine.Result? = null,
    /** 当前进度文案 */
    val progress: String = "",
    /** 错误 */
    val error: String? = null,
) {
    val busy: Boolean get() = diagnosing || repairing
}

/**
 * 自修复 + 恢复初始形态 —— ViewModel。
 */
class RepairViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "RepairVM"
    }

    private val settingsRepo = SettingsRepository(app)
    private val diagnostics = Diagnostics(app)
    private val advisor = AiRepairAdvisor(OpenAiApi())
    private val engine = RepairEngine(app, settingsRepo)
    private val agent = RepairAgent(app, settingsRepo)

    private val _state = MutableStateFlow(RepairUiState())
    val state: StateFlow<RepairUiState> = _state.asStateFlow()

    /** 跑体检（确定性检查） */
    fun diagnose() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(diagnosing = true, error = null, progress = "体检中…")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsRepo.settings.first()
                val report = diagnostics.runFullCheck(settings)
                _state.value = _state.value.copy(diagnosing = false, report = report, progress = "")
                Log.i(TAG, "diagnose: score=${report.healthScore} fail=${report.failCount} warn=${report.warnCount}")
            } catch (e: Exception) {
                Log.e(TAG, "diagnose failed", e)
                _state.value = _state.value.copy(
                    diagnosing = false, error = "体检失败：${e.message}", progress = ""
                )
            }
        }
    }

    /**
     * 跑 AI 自动修复（Agent 模式）。
     *
     * 【2026-11-02 用户明确要求】「我要的不是可以给建议的 AI，
     * 我又没有工具可以调用，就不能让 AI 调用工具自修复吗」
     * → 这里跑的是完整的 Agent 循环：LLM 决定调哪个工具 → 真实执行 →
     *   结果回喂 → LLM 继续调，直到体检无严重问题。
     */
    fun runAgent() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(
            diagnosing = true, error = null, progress = "AI 正在诊断…", agentResult = null
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = agent.run { msg -> _state.value = _state.value.copy(progress = msg) }
                _state.value = _state.value.copy(
                    diagnosing = false, agentResult = result, progress = "",
                    report = result.finalReport,
                )
                Log.i(
                    TAG,
                    "agent: toolCalls=${result.toolCallCount} succeeded=${result.succeeded} " +
                        "reason=${result.stoppedReason}"
                )
            } catch (e: Exception) {
                Log.e(TAG, "agent failed", e)
                _state.value = _state.value.copy(
                    diagnosing = false, error = "AI 自动修复失败：${e.message}", progress = ""
                )
            }
        }
    }

    /** 自动修复 */
    fun autoRepair(includeModelRepair: Boolean = false) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(repairing = true, error = null, progress = "开始修复…", result = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = engine.autoRepair(includeModelRepair) { msg ->
                    _state.value = _state.value.copy(progress = msg)
                }
                _state.value = _state.value.copy(repairing = false, result = result, progress = "")
                Log.i(TAG, "autoRepair: steps=${result.steps.size} ok=${result.succeeded}")
            } catch (e: Exception) {
                Log.e(TAG, "autoRepair failed", e)
                _state.value = _state.value.copy(
                    repairing = false, error = "修复失败：${e.message}", progress = ""
                )
            }
        }
    }

    /**
     * 恢复初始形态 —— 只把设置改回默认，**不删除**课堂记录与模型。
     */
    fun restoreFactory() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(repairing = true, error = null, progress = "恢复中…", result = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = engine.restoreFactory { msg ->
                    _state.value = _state.value.copy(progress = msg)
                }
                _state.value = _state.value.copy(repairing = false, result = result, progress = "")
                Log.i(TAG, "restoreFactory: steps=${result.steps.size}")
            } catch (e: Exception) {
                Log.e(TAG, "restoreFactory failed", e)
                _state.value = _state.value.copy(
                    repairing = false, error = "恢复失败：${e.message}", progress = ""
                )
            }
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }
}
