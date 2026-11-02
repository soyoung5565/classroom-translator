package com.classroom.translator.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.classroom.translator.repair.Diagnostics
import com.classroom.translator.repair.RepairUiState
import com.classroom.translator.repair.RepairViewModel
import com.classroom.translator.ui.theme.AppTheme
import com.classroom.translator.local.ModelStore

/**
 * 自修复 / 恢复初始形态 页面。
 *
 * 用户视角只有三件事：
 *   1. 「一键体检」——告诉我哪里坏了
 *   2. 「一键修复」——把能自动修的修好
 *   3. 「AI 诊断」——让云端大模型解释问题并给建议
 * 外加「恢复初始形态」——回到刚装好的样子。
 *
 * 设计上刻意把「体检」和「修复」分开：先看结论再动手，
 * 免得用户不知道 App 到底改了什么。
 */
@Composable
fun RepairScreen(
    onBack: () -> Unit,
    viewModel: RepairViewModel,
) {
    val state by viewModel.state.collectAsState()
    val cs = MaterialTheme.colorScheme
    val tk = AppTheme.tokens

    var showRestoreDialog by remember { mutableStateOf(false) }
    var showModelRepairDialog by remember { mutableStateOf(false) }

    Scaffold(containerColor = cs.surface) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // appbar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .padding(start = 16.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "自修复工具",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onBack) {
                    Text("返回", color = cs.primary)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // ── 健康总览 ──
                HealthCard(state)

                // ── 操作按钮 ──
                ActionGrid(
                    busy = state.busy,
                    onDiagnose = { viewModel.diagnose() },
                    onAutoRepair = { viewModel.autoRepair(includeModelRepair = false) },
                    onAskAi = { viewModel.askAi() },
                    onRestore = { showRestoreDialog = true },
                    onRepairModels = { showModelRepairDialog = true },
                )

                if (state.busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = cs.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(state.progress.ifBlank { "处理中…" }, fontSize = 12.sp, color = cs.onSurfaceVariant)
                    }
                }

                // ── 存储位置权限 ──
                // 【2026-11-02】没有「所有文件访问权限」，模型只能放在 App 专属目录，
                // 而那个目录会被「清除应用数据」删掉 —— 正是本次事故的根因。
                // 所以这一项要显眼，并给一键跳转授权。
                val ctx = LocalContext.current
                val store = remember(ctx) { ModelStore(ctx.applicationContext) }
                val durable = remember(ctx) { store.isDurable() }
                if (!durable) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(cs.secondaryContainer)
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "模型存放位置不够安全",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = cs.onSecondaryContainer,
                        )
                        Text(
                            "未授予「所有文件访问权限」时，本地模型只能存在 App 专属目录，" +
                                "而该目录会被系统「清除应用数据」一并删除（本次故障的根因）。" +
                                "授予后模型改存到共享存储，清除数据也不会丢。",
                            fontSize = 11.sp,
                            lineHeight = 17.sp,
                            color = cs.onSecondaryContainer,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(cs.primary)
                                .clickable {
                                    store.permissionRequestIntent()?.let {
                                        runCatching { ctx.startActivity(it) }
                                    }
                                },
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "去授予权限",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = cs.onPrimary,
                            )
                        }
                    }
                }

                // ── 体检结果 ──
                state.report?.let { report -> FindingsList(report) }

                // ── AI 建议 ──
                state.advice?.let { advice -> AdviceCard(advice) }

                // ── 修复结果 ──
                state.result?.let { result -> ResultCard(result) }

                state.error?.let { err ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(cs.errorContainer)
                            .padding(14.dp),
                    ) {
                        Text(err, fontSize = 12.sp, color = cs.onErrorContainer)
                    }
                }
            }
        }
    }

    // ── 恢复初始形态 确认框 ──
    if (showRestoreDialog) {
        var clearSessions by remember { mutableStateOf(false) }
        var keepModels by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text("恢复初始形态") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("将把所有设置恢复到出厂默认，并重启本地服务。", fontSize = 13.sp)
                    CheckRow("同时清除课堂记录", clearSessions) { clearSessions = it }
                    CheckRow("保留已下载的模型（推荐）", keepModels) { keepModels = it }
                    if (!keepModels) {
                        Text(
                            "注意：删除模型后需要重新下载约 1.4GB。",
                            fontSize = 11.sp,
                            color = cs.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreDialog = false
                    viewModel.restoreFactory(clearSessions = clearSessions, keepModels = keepModels)
                }) { Text("恢复", color = cs.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) { Text("取消") }
            },
        )
    }

    // ── 重新下载模型 确认框 ──
    if (showModelRepairDialog) {
        AlertDialog(
            onDismissRequest = { showModelRepairDialog = false },
            title = { Text("重新下载本地模型") },
            text = {
                Text(
                    "将从 HuggingFace 下载约 1.4GB 的语音识别与翻译模型。" +
                        "支持断点续传，中断后再次点击会接着下。",
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showModelRepairDialog = false
                    viewModel.autoRepair(includeModelRepair = true)
                }) { Text("下载", color = cs.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showModelRepairDialog = false }) { Text("取消") }
            },
        )
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun HealthCard(state: RepairUiState) {
    val cs = MaterialTheme.colorScheme
    val report = state.report
    val score = report?.healthScore
    val (color, label) = when {
        score == null -> cs.onSurfaceVariant to "未体检"
        score >= 90 -> cs.primary to "状态良好"
        score >= 60 -> cs.secondary to "有小问题"
        else -> cs.error to "需要修复"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cs.surfaceContainerLow)
            .border(1.dp, AppTheme.tokens.hairline, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.HealthAndSafety, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
            Text(
                if (report == null) "点「一键体检」检查 App 当前状态"
                else "健康分 $score/100 ｜ 严重 ${report.failCount} ｜ 警告 ${report.warnCount}",
                fontSize = 11.sp,
                color = cs.onSurfaceVariant,
            )
        }
        if (score != null) {
            Text("$score", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
private fun ActionGrid(
    busy: Boolean,
    onDiagnose: () -> Unit,
    onAutoRepair: () -> Unit,
    onAskAi: () -> Unit,
    onRestore: () -> Unit,
    onRepairModels: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RepairButton(
            icon = Icons.Outlined.HealthAndSafety,
            title = "一键体检",
            desc = "检查本地服务、模型完整性、配置正确性、存储空间",
            enabled = !busy,
            onClick = onDiagnose,
        )
        RepairButton(
            icon = Icons.Outlined.Build,
            title = "一键修复",
            desc = "自动纠正错误配置、重启服务、修好模型指向",
            enabled = !busy,
            onClick = onAutoRepair,
        )
        RepairButton(
            icon = Icons.Outlined.Psychology,
            title = "AI 诊断",
            desc = "让云端大模型解读体检结果，给出原因解释与修复优先级",
            enabled = !busy,
            onClick = onAskAi,
        )
        RepairButton(
            icon = Icons.Outlined.Build,
            title = "重新下载本地模型",
            desc = "模型缺失或损坏时重新拉取（约 1.4GB，断点续传）",
            enabled = !busy,
            onClick = onRepairModels,
        )
        RepairButton(
            icon = Icons.Outlined.CleaningServices,
            title = "恢复初始形态",
            desc = "所有设置回到出厂默认，重启本地服务",
            enabled = !busy,
            onClick = onRestore,
            danger = true,
        )
    }
}

@Composable
private fun RepairButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    enabled: Boolean,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cs.surfaceContainerLow)
            .border(1.dp, AppTheme.tokens.hairline, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, contentDescription = null,
            tint = if (danger) cs.error else cs.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (danger) cs.error else cs.onSurface,
            )
            Text(desc, fontSize = 11.sp, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun FindingsList(report: Diagnostics.Report) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("体检结果", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
        report.findings.forEach { f ->
            val color = when (f.severity) {
                Diagnostics.Severity.OK -> cs.primary
                Diagnostics.Severity.WARN -> cs.secondary
                Diagnostics.Severity.FAIL -> cs.error
            }
            val badge = when (f.severity) {
                Diagnostics.Severity.OK -> "正常"
                Diagnostics.Severity.WARN -> "警告"
                Diagnostics.Severity.FAIL -> "严重"
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(cs.surfaceContainerLow)
                    .border(1.dp, AppTheme.tokens.hairline, RoundedCornerShape(12.dp))
                    .padding(13.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        badge,
                        fontSize = 10.sp,
                        color = color,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(color.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    if (f.autoFixable) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "可自动修复",
                            fontSize = 10.sp,
                            color = cs.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(cs.primary.copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(f.detail, fontSize = 11.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AdviceCard(advice: com.classroom.translator.repair.AiRepairAdvisor.Advice) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cs.surfaceContainerLow)
            .border(1.dp, AppTheme.tokens.hairline, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("AI 诊断", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
        Text(advice.summary, fontSize = 12.sp, lineHeight = 18.sp, color = cs.onSurface)
        if (advice.suggestions.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            advice.suggestions.forEach { s ->
                Column {
                    Text(
                        "P${s.priority} · ${s.title}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = cs.onSurface,
                    )
                    if (s.why.isNotBlank()) {
                        Text(s.why, fontSize = 11.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant)
                    }
                    if (s.action.isNotBlank()) {
                        Text("→ ${s.action}", fontSize = 11.sp, lineHeight = 16.sp, color = cs.primary)
                    }
                }
            }
        }
        if (advice.extraChecks.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text("还可以检查", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            advice.extraChecks.forEach {
                Text("· $it", fontSize = 11.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ResultCard(result: com.classroom.translator.repair.RepairEngine.Result) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cs.surfaceContainerLow)
            .border(1.dp, AppTheme.tokens.hairline, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("执行结果", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
        result.steps.forEach { step ->
            Row(verticalAlignment = Alignment.Top) {
                // 三态：完成 ✓ / 失败 ✗ / 跳过 ○
                val mark = when {
                    step.skipped -> "○"
                    step.success -> "✓"
                    else -> "✗"
                }
                val markColor = when {
                    step.skipped -> cs.onSurfaceVariant
                    step.success -> cs.primary
                    else -> cs.error
                }
                Text(
                    mark,
                    fontSize = 12.sp,
                    color = markColor,
                    modifier = Modifier.width(18.dp),
                )
                Column {
                    Text(step.name, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                    if (step.detail.isNotBlank()) {
                        Text(step.detail, fontSize = 11.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
        result.after?.let { after ->
            Spacer(Modifier.height(4.dp))
            Text(
                "健康分 ${result.before.healthScore} → ${after.healthScore}" +
                    if (after.healthScore > result.before.healthScore) "（已改善）" else "",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (after.healthScore > result.before.healthScore) cs.primary else cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (checked) cs.primary else Color.Transparent)
                .border(1.dp, if (checked) cs.primary else cs.outline, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text("✓", fontSize = 12.sp, color = cs.onPrimary)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 13.sp, color = cs.onSurface)
    }
}
