package com.classroom.translator.ui.screens

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.classroom.translator.data.SessionManager
import com.classroom.translator.data.TranslationSession

/**
 * History (sessions list) screen + session detail screen.
 * All colors / typography / shapes from MaterialTheme tokens.
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
                        "课堂翻译",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                actions = {
                    TextButton(onClick = onNavigateToWholeSession) {
                        Text(
                            "整课转录",
                            color = MaterialTheme.colorScheme.onSurface,
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
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToRecording,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
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
                    Text(
                        text = "\uD83D\uDCDD",
                        style = MaterialTheme.typography.displayMedium,
                    )
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = session.date,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "${session.startTime} - ${session.endTime}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = "${session.entryCount} 段翻译",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )

            if (session.summary.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = session.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onExport) {
                    Text("分享", color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            "课堂翻译",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
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
                    TextButton(onClick = onBack) {
                        Text(
                            "返回",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelLarge,
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
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
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
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "Recording ${sizeKB}KB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(4.dp))
                                Slider(
                                    value = currentPosition.toFloat(),
                                    onValueChange = { isSeeking = true; currentPosition = it.toInt() },
                                    onValueChangeFinished = { player?.seekTo(currentPosition); isSeeking = false },
                                    valueRange = 0f..totalDuration.toFloat().coerceAtLeast(1f),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        "%d:%02d".format(currentPosition / 1000 / 60, currentPosition / 1000 % 60),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        "%d:%02d".format(totalDuration / 1000 / 60, totalDuration / 1000 % 60),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    TextButton(onClick = {
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
                                    }) { Text(if (isPlaying) "Pause" else "Play", color = MaterialTheme.colorScheme.primary) }
                                    Spacer(Modifier.width(16.dp))
                                    TextButton(onClick = {
                                        player?.stop(); player?.release(); player = null; isPlaying = false; currentPosition = 0
                                    }) { Text("Stop", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    Spacer(Modifier.width(16.dp))
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
                                    }) { Text("Export Audio", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                        }
                    } else {
                        Text(
                            "Recording not found",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    "${s.startTime} - ${s.endTime}  共${s.entries.size}段",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                Text(
                    "English",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    s.entries.joinToString(" ") { it.english },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(20.dp))

                Text(
                    "中文翻译",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    s.entries.joinToString("") { it.chinese },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(Modifier.height(24.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))

                Text(
                    "详细条目",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                for (entry in s.entries) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(entry.english, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(entry.chinese, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
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
