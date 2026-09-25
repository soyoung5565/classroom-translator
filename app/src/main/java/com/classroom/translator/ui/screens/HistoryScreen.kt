package com.classroom.translator.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.classroom.translator.data.SessionManager
import com.classroom.translator.data.TranslationSession

/**
 * History (sessions list) screen + session detail screen.
 * 2026-09-25 B-direction redesign: pure white/black backgrounds, gradient
 * accents on primary actions, subject chip on session cards, dual-theme safe.
 * All colors / typography / shapes from MaterialTheme tokens.
 * Interactions preserved verbatim: SessionCard click -> detail, share/delete
 * buttons, whole-session entry, MediaPlayer play/pause/stop/seek, recording export.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onNavigateToRecording: () -> Unit,
    onNavigateToSession: (Long) -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToWholeSession: () -> Unit = {},
) {
    val context = LocalContext.current
    var sessions by remember { mutableStateOf<List<TranslationSession>>(emptyList()) }
    val sessionManager = remember { SessionManager(context) }

    LaunchedEffect(Unit) {
        sessions = sessionManager.loadSessions()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "历史记录",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                actions = {
                    TextButton(onClick = onNavigateToWholeSession) {
                        Text(
                            "整课转录",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                        )
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
        floatingActionButton = {
            // Gradient leaf FAB matching MainScreen idle state
            val cs = MaterialTheme.colorScheme
            val gradient = Brush.linearGradient(listOf(cs.primary, cs.tertiary))
            val fabShape = androidx.compose.foundation.shape.RoundedCornerShape(
                topStart = 28.dp, topEnd = 12.dp,
                bottomEnd = 28.dp, bottomStart = 12.dp,
            )
            FloatingActionButton(
                onClick = onNavigateToRecording,
                modifier = Modifier.background(gradient, fabShape),
                shape = fabShape,
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                contentColor = cs.onPrimary,
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "开始翻译",
                    modifier = Modifier.size(28.dp),
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
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
                            imageVector = Icons.Default.History,
                            contentDescription = "暂无记录",
                            modifier = Modifier.size(44.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "还没有翻译记录，点击下方按钮开始",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionCard(
                        session = session,
                        onClick = { onNavigateToSession(session.id) },
                        onDelete = {
                            sessionManager.deleteSession(session.id)
                            sessions = sessionManager.loadSessions()
                        },
                        onExport = { shareSessionDocx(context, sessionManager, session) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: TranslationSession,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = cs.surfaceContainer,
            contentColor = cs.onSurface,
        ),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = session.date,
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onSurface,
                )
                // Subject chip (class tag)
                Box(
                    modifier = Modifier
                        .background(cs.primary.copy(alpha = 0.14f), MaterialTheme.shapes.small)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        "英语课",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.primary,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = "${session.startTime} - ${session.endTime} · ${session.entryCount} 段翻译",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )

            if (session.summary.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = session.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onExport) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = cs.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("分享", color = cs.primary)
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    sessionId: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val sessionManager = remember { SessionManager(context) }
    var session by remember { mutableStateOf<TranslationSession?>(null) }

    LaunchedEffect(sessionId) {
        session = sessionManager.loadSessions().find { it.id == sessionId }
    }

    val s = session
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "会话详情",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (s != null) {
                            Text(
                                s.date,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (s != null) {
                        TextButton(onClick = { shareSessionDocx(context, sessionManager, s) }) {
                            Text("导出", color = MaterialTheme.colorScheme.primary)
                        }
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
        if (s != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                if (s.audioPath.isNotBlank()) {
                    val audioFile = java.io.File(s.audioPath)
                    if (audioFile.exists()) {
                        var isPlaying by remember { mutableStateOf(false) }
                        var player by remember { mutableStateOf<MediaPlayer?>(null) }
                        var currentPosition by remember { mutableStateOf(0) }
                        var totalDuration by remember { mutableStateOf(0) }
                        var isSeeking by remember { mutableStateOf(false) }
                        val sizeKB = audioFile.length() / 1024
                        LaunchedEffect(isPlaying) {
                            while (isPlaying) {
                                kotlinx.coroutines.delay(200)
                                if (!isSeeking && player != null) {
                                    try { currentPosition = player!!.currentPosition } catch (_: Exception) {}
                                }
                            }
                        }
                        val cs = MaterialTheme.colorScheme
                        // Player card: gradient accent on icon, tonal surface
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = cs.surfaceContainer,
                                contentColor = cs.onSurface,
                            ),
                            shape = MaterialTheme.shapes.medium,
                            border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.35f)),
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(
                                                Brush.linearGradient(listOf(cs.primary, cs.tertiary))
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Default.GraphicEq,
                                            contentDescription = null,
                                            tint = cs.onPrimary,
                                            modifier = Modifier.size(22.dp),
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            "课堂录音",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = cs.onSurface,
                                        )
                                        Text(
                                            "%d:%02d".format(totalDuration / 1000 / 60, totalDuration / 1000 % 60),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = cs.onSurfaceVariant,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Slider(
                                    value = currentPosition.toFloat(),
                                    onValueChange = { isSeeking = true; currentPosition = it.toInt() },
                                    onValueChangeFinished = { player?.seekTo(currentPosition); isSeeking = false },
                                    valueRange = 0f..totalDuration.toFloat().coerceAtLeast(1f),
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = SliderDefaults.colors(
                                        thumbColor = cs.primary,
                                        activeTrackColor = cs.primary,
                                    ),
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        "%d:%02d".format(currentPosition / 1000 / 60, currentPosition / 1000 % 60),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = cs.onSurfaceVariant,
                                    )
                                    Text(
                                        "%d:%02d".format(totalDuration / 1000 / 60, totalDuration / 1000 % 60),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = cs.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    IconButton(onClick = {
                                        if (isPlaying) { player?.pause(); isPlaying = false }
                                        else {
                                            if (player == null) {
                                                try {
                                                    player = MediaPlayer().apply {
                                                        setDataSource(s.audioPath); prepare(); totalDuration = duration; start()
                                                        setOnCompletionListener { isPlaying = false; currentPosition = 0 }
                                                    }; isPlaying = true
                                                } catch (e: Exception) { android.util.Log.e("History", "Play failed", e) }
                                            } else { player?.start(); isPlaying = true }
                                        }
                                    }) {
                                        Icon(
                                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = if (isPlaying) "暂停" else "播放",
                                            tint = cs.primary,
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    IconButton(onClick = {
                                        player?.stop(); player?.release(); player = null; isPlaying = false; currentPosition = 0
                                    }) {
                                        Icon(
                                            Icons.Default.Stop,
                                            contentDescription = "停止",
                                            tint = cs.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    TextButton(onClick = {
                                        try {
                                            val exportDir = java.io.File(context.getExternalFilesDir(null), "exports"); exportDir.mkdirs()
                                            val exportFile = java.io.File(exportDir, "recording_${s.date}.wav")
                                            audioFile.copyTo(exportFile, overwrite = true)
                                            val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", exportFile)
                                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                type = "audio/wav"; putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(android.content.Intent.createChooser(intent, "Export Recording"))
                                        } catch (e: Exception) { android.util.Log.e("History", "Export failed", e) }
                                    }) { Text("导出录音", color = cs.onSurfaceVariant) }
                                }
                            }
                        }
                    } else {
                        Text(
                            "录音文件不存在",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    "${s.date} · ${s.startTime} - ${s.endTime} · ${s.entries.size} 段",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                for (entry in s.entries) {
                    val cs = MaterialTheme.colorScheme
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = cs.surfaceContainer,
                            contentColor = cs.onSurface,
                        ),
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.3f)),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                entry.timestamp,
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.primary,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                entry.english,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = cs.onSurface,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                entry.chinese,
                                style = MaterialTheme.typography.bodyMedium,
                                color = cs.onSurfaceVariant,
                            )
                        }
                    }
                }


            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载中...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun shareSessionDocx(
    context: Context,
    sessionManager: SessionManager,
    session: TranslationSession,
) {
    try {
        val filePath = sessionManager.exportSessionToWord(session)
        val file = java.io.File(filePath)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "课堂翻译记录 - ${session.date}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "导出Word文档"))
    } catch (e: Exception) {
        android.util.Log.e("History", "Export failed", e)
    }
}
