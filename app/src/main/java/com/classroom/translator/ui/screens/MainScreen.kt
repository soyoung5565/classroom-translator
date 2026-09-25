package com.classroom.translator.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.classroom.translator.ui.TranslationEntry
import com.classroom.translator.ui.TranslatorUiState
import com.classroom.translator.ui.TranslatorViewModel
import kotlinx.coroutines.delay

/**
 * Main recording screen - 2026-09-25 B-direction redesign ("huoli jianbian").
 *
 * Visual language: pure white/black scaffold (system-following dual theme),
 * violet gradient accent limited to three focal points (status chip while
 * recording, newest card border, FAB), 12dp card rhythm, tonal elevation.
 * All colors/typography/shapes come from MaterialTheme tokens - no hardcoded hex.
 * All interactions preserved verbatim: long-press delete dialog, mic FAB pulse
 * (audioLevel * 0.3), finish button, status bar.
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
                    Text(
                        "课堂翻译",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
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
                    containerColor = MaterialTheme.colorScheme.background,
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
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                    ) {
                        itemsIndexed(state.entries, key = { _, entry -> entry.id }) { index, entry ->
                            TranslationCard(
                                entry = entry,
                                isLatest = index == state.entries.lastIndex,
                                onDelete = { viewModel.deleteEntry(entry.id) },
                            )
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

            if (state.entries.isNotEmpty()) {
                Text(
                    "已翻译 ${state.entries.size} 段",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 4.dp),
                )
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

/** Status chip: violet gradient while recording, tonal pill otherwise. */
@Composable
private fun StatusBar(state: TranslatorUiState) {
    val cs = MaterialTheme.colorScheme
    val chipShape = MaterialTheme.shapes.small
    val gradient = Brush.linearGradient(listOf(cs.primary, cs.tertiary))
    val chipModifier = if (state.isRecording) {
        Modifier.background(gradient, chipShape)
    } else {
        Modifier.background(cs.secondaryContainer, chipShape)
    }
    val chipContent = if (state.isRecording) cs.onPrimary else cs.onSecondaryContainer
    val chipSub = if (state.isRecording) cs.onPrimary.copy(alpha = 0.8f) else cs.onSecondaryContainer.copy(alpha = 0.8f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = chipModifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            val dotColor by animateColorAsState(
                if (state.isRecording) chipContent else cs.primary,
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
                color = chipContent,
            )
        }

        Row {
            if (state.entries.isNotEmpty()) {
                Text(
                    "${state.entries.size} 段",
                    style = MaterialTheme.typography.labelSmall,
                    color = chipSub,
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
                color = cs.onSurfaceVariant,
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
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "开始录音",
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
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

/**
 * Transcript card. Newest entry carries the 1.5dp violet gradient border +
 * primary tint (B-direction focal point); older cards stay quiet.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TranslationCard(
    entry: TranslationEntry,
    isLatest: Boolean = false,
    onDelete: () -> Unit = {},
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme

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

    val gradientBorder = Brush.linearGradient(listOf(cs.primary, cs.tertiary))
    val cardColors = if (isLatest) {
        CardDefaults.cardColors(
            containerColor = cs.primary.copy(alpha = 0.12f),
            contentColor = cs.onSurface,
        )
    } else {
        CardDefaults.cardColors(
            containerColor = cs.surfaceContainer,
            contentColor = cs.onSurface,
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = { showDeleteDialog = true },
            ),
        colors = cardColors,
        shape = MaterialTheme.shapes.medium,
        border = if (isLatest) {
            BorderStroke(1.5.dp, gradientBorder)
        } else {
            BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.35f))
        },
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Timestamp rail: purple, small, top anchor
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (entry.isCorrected) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "已修正",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // Chinese translation = primary content layer (white, prominent)
            Text(
                text = entry.chinese,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(4.dp))

            // English original = secondary layer (grey, single line)
            Text(
                text = entry.english,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(6.dp))

            Text(
                "${entry.latencyMs}毫秒",
                style = MaterialTheme.typography.labelSmall,
                color = if (entry.latencyMs < 3000) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/**
 * 80dp asymmetric "leaf" FAB (B-direction focal point). Idle: violet
 * primary->tertiary gradient; recording: solid error (kept behavior) with
 * the audioLevel pulse animation preserved.
 */
@Composable
private fun RecordButton(
    isRecording: Boolean,
    isProcessing: Boolean,
    audioLevel: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val animatedScale by animateFloatAsState(
        if (isRecording) 1f + audioLevel * 0.3f else 1f,
        label = "scale",
    )
    val fabShape = RoundedCornerShape(
        topStart = 40.dp, topEnd = 18.dp,
        bottomEnd = 40.dp, bottomStart = 18.dp,
    )
    val gradient = Brush.linearGradient(listOf(cs.primary, cs.tertiary))
    val bgModifier = if (isRecording) {
        Modifier.background(cs.error, fabShape)
    } else {
        Modifier.background(gradient, fabShape)
    }
    val contentColor = if (isRecording) cs.onError else cs.onPrimary

    FloatingActionButton(
        onClick = onClick,
        modifier = modifier
            .size((64 * animatedScale).dp)
            .then(bgModifier),
        shape = fabShape,
        containerColor = Color.Transparent,
        contentColor = contentColor,
    ) {
        if (isProcessing && !isRecording) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = contentColor,
                strokeWidth = 2.5.dp,
            )
        } else {
            Icon(
                imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = if (isRecording) "停止" else "开始",
                modifier = Modifier.size(28.dp),
                tint = androidx.compose.ui.graphics.Color.White,
            )
        }
    }
}
