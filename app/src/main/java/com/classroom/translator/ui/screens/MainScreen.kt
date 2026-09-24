package com.classroom.translator.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.classroom.translator.ui.TranslationEntry
import com.classroom.translator.ui.TranslatorUiState
import com.classroom.translator.ui.TranslatorViewModel
import kotlinx.coroutines.delay

/**
 * Main recording screen. All colors / typography / shapes come from
 * MaterialTheme tokens - no hardcoded hex. See Color.kt + Theme.kt for tokens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToHistory: () -> Unit = {},
    viewModel: TranslatorViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(state.entries.size) {
        if (state.entries.isNotEmpty()) {
            listState.animateScrollToItem(state.entries.size - 1)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "课堂翻译",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "语音识别 + 大模型翻译",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (state.entries.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearEntries() }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "清空",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            StatusBar(state)

            Box(modifier = Modifier.weight(1f)) {
                if (state.entries.isEmpty()) {
                    EmptyState()
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(state.entries, key = { it.id }) { entry ->
                            TranslationCard(entry, onDelete = { viewModel.deleteEntry(entry.id) })
                        }
                    }
                }

                if (state.isProcessing) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
            }

            state.error?.let { error ->
                Snackbar(
                    modifier = Modifier.padding(12.dp),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    action = {
                        TextButton(onClick = { viewModel.dismissError() }) {
                            Text("OK", color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    },
                ) { Text(error) }
                LaunchedEffect(error) {
                    delay(5000)
                    viewModel.dismissError()
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 24.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.entries.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            viewModel.finishSession()
                            onNavigateToHistory()
                        },
                    ) {
                        Text(
                            "完成",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                }

                RecordButton(
                    isRecording = state.isRecording,
                    isProcessing = state.isProcessing,
                    audioLevel = state.audioLevel,
                    onClick = { viewModel.toggleRecording() },
                )
            }
        }
    }
}

@Composable
private fun StatusBar(state: TranslatorUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dotColor by animateColorAsState(
                if (state.isRecording) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "dot",
            )
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                state.status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Row {
            if (state.entries.isNotEmpty()) {
                Text(
                    "${state.entries.size} 段",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
            }
            val asrName = state.settings.asrModel.let {
                when {
                    it.contains("omni") -> "MIMO-Omni"
                    it.contains("fun-asr") || it.contains("dashscope") -> "DashScope"
                    it.contains("asr") -> "ASR"
                    else -> "MIMO"
                }
            }
            Text(
                "$asrName + ${state.settings.translateModel}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "\uD83C\uDF99",
                style = MaterialTheme.typography.displayMedium,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "点击麦克风开始",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "英语语音 -> 中文翻译",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TranslationCard(entry: TranslationEntry, onDelete: () -> Unit = {}) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除此条目？") },
            text = { Text(entry.english.take(50) + "...") },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete() }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = { showDeleteDialog = true },
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = entry.english,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = entry.chinese,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.timestamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (entry.isCorrected) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "已修正",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                Text(
                    "${entry.latencyMs}毫秒",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (entry.latencyMs < 3000) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun RecordButton(
    isRecording: Boolean,
    isProcessing: Boolean,
    audioLevel: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val animatedScale by animateFloatAsState(
        if (isRecording) 1f + audioLevel * 0.3f else 1f,
        label = "scale",
    )
    val buttonColor by animateColorAsState(
        if (isRecording) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.primary,
        label = "btnColor",
    )

    // Content color follows buttonColor: on-primary when idle, on-error when recording
    val contentColor = if (isRecording) MaterialTheme.colorScheme.onError
                       else MaterialTheme.colorScheme.onPrimary

    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.size((64 * animatedScale).dp),
        shape = CircleShape,
        containerColor = buttonColor,
        contentColor = contentColor,
    ) {
        if (isProcessing && !isRecording) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = contentColor,
                strokeWidth = 2.5.dp,
            )
        } else {
            Icon(
                imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = if (isRecording) "停止" else "开始",
                modifier = Modifier.size(32.dp),
            )
        }
    }
}
