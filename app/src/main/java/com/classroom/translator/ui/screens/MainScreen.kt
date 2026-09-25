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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.classroom.translator.ui.TranslationEntry
import com.classroom.translator.ui.TranslatorUiState
import com.classroom.translator.ui.TranslatorViewModel
import kotlinx.coroutines.delay

/**
 * Main recording screen - v1.2 faithful rebuild of the B-direction design.
 * Structure now matches the design mock:
 *   top row  = [译 badge] 课堂翻译 ......... [clear][settings]
 *   chip row = status pill (gradient while recording)
 *   content  = transcript cards (timestamp purple anchor / zh primary / en grey),
 *              newest card carries the gradient border
 *   bottom   = stats line + [✓ 完成 pill] + gradient circular FAB
 * Debug info (model names) removed from UI. All interactions preserved:
 * long-press delete dialog, mic pulse (audioLevel*0.3), finish->history.
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
    val cs = MaterialTheme.colorScheme

    LaunchedEffect(state.entries.size) {
        if (state.entries.isNotEmpty()) {
            listState.animateScrollToItem(state.entries.size - 1)
        }
    }

    Scaffold(containerColor = cs.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ---- Top bar row ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(cs.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("译", color = cs.onPrimary, style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.width(10.dp))
                Text("课堂翻译", style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
                Spacer(Modifier.weight(1f))
                if (state.entries.isNotEmpty()) {
                    IconButton(onClick = { viewModel.clearEntries() }) {
                        Icon(Icons.Default.Delete, contentDescription = "清空", tint = cs.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onNavigateToSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "设置", tint = cs.onSurfaceVariant)
                }
            }

            // ---- Status chip row ----
            StatusBar(state)

            // ---- Transcript stream ----
            Box(modifier = Modifier.weight(1f)) {
                if (state.entries.isEmpty()) {
                    EmptyState()
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
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
                        color = cs.primary,
                        trackColor = cs.surfaceContainerHighest,
                    )
                }
            }

            state.error?.let { error ->
                Snackbar(
                    modifier = Modifier.padding(12.dp),
                    containerColor = cs.errorContainer,
                    contentColor = cs.onErrorContainer,
                    action = {
                        TextButton(onClick = { viewModel.dismissError() }) {
                            Text("OK", color = cs.onErrorContainer)
                        }
                    },
                ) { Text(error) }
                LaunchedEffect(error) {
                    delay(5000)
                    viewModel.dismissError()
                }
            }

            // ---- Bottom dock: stats + 完成 + FAB ----
            if (state.entries.isNotEmpty()) {
                Text(
                    "已翻译 ${state.entries.size} 段",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 6.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.entries.isNotEmpty()) {
                    Button(
                        onClick = {
                            viewModel.finishSession()
                            onNavigateToHistory()
                        },
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = cs.primaryContainer,
                            contentColor = cs.onPrimaryContainer,
                        ),
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("完成", style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.width(20.dp))
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

/** Status pill: violet gradient while recording, tonal surface otherwise. */
@Composable
private fun StatusBar(state: TranslatorUiState) {
    val cs = MaterialTheme.colorScheme
    val pillShape = RoundedCornerShape(50)
    val chipModifier = if (state.isRecording) {
        Modifier.background(Brush.linearGradient(listOf(cs.primary, cs.tertiary)), pillShape)
    } else {
        Modifier.background(cs.surfaceContainerHigh, pillShape)
    }
    val chipText = if (state.isRecording) cs.onPrimary else cs.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = chipModifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            val dotColor by animateColorAsState(
                if (state.isRecording) cs.onPrimary else cs.primary,
                label = "dot",
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(Modifier.width(8.dp))
            Text(state.status, style = MaterialTheme.typography.bodyMedium, color = chipText)
        }
        Spacer(Modifier.weight(1f))
        if (state.entries.isNotEmpty()) {
            Text(
                "${state.entries.size} 段",
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
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "开始录音",
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "点击麦克风开始",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "英语语音 → 中文翻译",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

/** Transcript card: newest = gradient border + primary tint; older = hairline. */
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
                    Text("删除", color = cs.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
        )
    }

    val cardColors = if (isLatest) {
        CardDefaults.cardColors(containerColor = cs.primary.copy(alpha = 0.12f), contentColor = cs.onSurface)
    } else {
        CardDefaults.cardColors(containerColor = cs.surfaceContainer, contentColor = cs.onSurface)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = { showDeleteDialog = true }),
        colors = cardColors,
        shape = MaterialTheme.shapes.medium,
        border = if (isLatest) {
            BorderStroke(1.5.dp, Brush.linearGradient(listOf(cs.primary, cs.tertiary)))
        } else {
            BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.35f))
        },
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // timestamp rail: purple, top anchor
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.timestamp, style = MaterialTheme.typography.labelSmall, color = cs.primary)
                if (entry.isCorrected) {
                    Spacer(Modifier.width(6.dp))
                    Text("已修正", style = MaterialTheme.typography.labelSmall, color = cs.tertiary)
                }
            }
            Spacer(Modifier.height(6.dp))
            // zh = primary layer
            Text(entry.chinese, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            Spacer(Modifier.height(4.dp))
            // en = secondary layer, single line
            Text(
                entry.english,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${entry.latencyMs}毫秒",
                style = MaterialTheme.typography.labelSmall,
                color = if (entry.latencyMs < 3000) cs.primary else cs.tertiary,
            )
        }
    }
}

/** Circular gradient FAB (user feedback: leaf shape read as broken on device). */
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
    val bgModifier = if (isRecording) {
        Modifier.background(cs.error, CircleShape)
    } else {
        Modifier.background(Brush.linearGradient(listOf(cs.primary, cs.tertiary)), CircleShape)
    }

    FloatingActionButton(
        onClick = onClick,
        modifier = modifier
            .size((64 * animatedScale).dp)
            .then(bgModifier),
        shape = CircleShape,
        containerColor = Color.Transparent,
        contentColor = Color.White,
    ) {
        if (isProcessing && !isRecording) {
            CircularProgressIndicator(modifier = Modifier.size(26.dp), color = Color.White, strokeWidth = 2.5.dp)
        } else {
            Icon(
                imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = if (isRecording) "停止" else "开始",
                modifier = Modifier.size(28.dp),
                tint = Color.White,
            )
        }
    }
}
