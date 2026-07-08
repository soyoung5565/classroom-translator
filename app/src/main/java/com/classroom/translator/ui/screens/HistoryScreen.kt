package com.classroom.translator.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.unit.sp
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.classroom.translator.data.SessionManager
import com.classroom.translator.data.TranslationSession
import com.classroom.translator.ui.theme.Blue500
import com.classroom.translator.ui.theme.DarkOnSurfaceVariant
import com.classroom.translator.ui.theme.DarkSurface
import com.classroom.translator.ui.theme.DarkSurfaceVariant
import com.classroom.translator.ui.theme.Green500

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onNavigateToRecording: () -> Unit,
    onNavigateToSession: (Long) -> Unit,
    onNavigateToSettings: () -> Unit = {}
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
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = DarkSurface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToRecording,
                containerColor = Blue500,
                contentColor = Color.White
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "开始翻译",
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (sessions.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📝", fontSize = 48.sp)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "还没有翻译记录，点击下方按钮开始",
                        style = MaterialTheme.typography.bodyLarge,
                        color = DarkOnSurfaceVariant
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
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionCard(
                        session = session,
                        onClick = { onNavigateToSession(session.id) },
                        onDelete = {
                            sessionManager.deleteSession(session.id)
                            sessions = sessionManager.loadSessions()
                        },
                        onExport = { shareSessionDocx(context, sessionManager, session) }
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
    onExport: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Date and time range
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = session.date,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = Blue500
                )
                Text(
                    text = "${session.startTime} - ${session.endTime}",
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Spacer(Modifier.height(4.dp))

            // Entry count
            Text(
                text = "${session.entryCount} 段翻译",
                style = MaterialTheme.typography.bodyMedium,
                color = Green500
            )

            // Summary preview
            if (session.summary.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = session.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = DarkOnSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(8.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onExport) {
                    Text("分享", color = Blue500)
                }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = DarkOnSurfaceVariant
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
    onBack: () -> Unit
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
                        Text("课堂翻译", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (s != null) {
                            Text(s.date, style = MaterialTheme.typography.labelSmall, color = DarkOnSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回", color = Color.White) }
                },
                actions = {
                    if (s != null) {
                        TextButton(onClick = { shareSessionDocx(context, sessionManager, s) }) {
                            Text("导出", color = Blue500)
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = DarkSurface)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (s != null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)
            ) {
                // Audio info
                if (s.audioPath.isNotBlank()) {
                    Text("录音: ${s.audioPath.substringAfterLast("/")}", style = MaterialTheme.typography.labelSmall, color = DarkOnSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                Text("${s.startTime} - ${s.endTime}  共${s.entries.size}段", style = MaterialTheme.typography.bodyMedium, color = DarkOnSurfaceVariant)
                Spacer(Modifier.height(16.dp))

                // English paragraph
                Text("English", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Blue500)
                Spacer(Modifier.height(4.dp))
                Text(s.entries.joinToString(" ") { it.english }, style = MaterialTheme.typography.bodyLarge, lineHeight = 28.sp)
                Spacer(Modifier.height(20.dp))

                // Chinese paragraph
                Text("中文翻译", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Blue500)
                Spacer(Modifier.height(4.dp))
                Text(s.entries.joinToString("") { it.chinese }, style = MaterialTheme.typography.bodyLarge, lineHeight = 28.sp, color = MaterialTheme.colorScheme.onSurface)

                Spacer(Modifier.height(24.dp))
                HorizontalDivider(color = DarkSurfaceVariant)
                Spacer(Modifier.height(12.dp))

                // Detailed entries
                Text("详细条目", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = DarkOnSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                for (entry in s.entries) {
                    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(entry.english, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(entry.chinese, style = MaterialTheme.typography.bodyMedium, color = Blue500)
                        }
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载中...", color = DarkOnSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BilingualCard(label: String, text: String, textColor: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Blue500,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = text.ifBlank { "（无内容）" },
                style = MaterialTheme.typography.bodyLarge.copy(
                    lineHeight = 24.sp
                ),
                color = textColor
            )
        }
    }
}

private fun shareSessionDocx(
    context: Context,
    sessionManager: SessionManager,
    session: TranslationSession
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
