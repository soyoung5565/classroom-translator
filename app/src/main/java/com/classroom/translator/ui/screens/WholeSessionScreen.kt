package com.classroom.translator.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.classroom.translator.ui.TranslatorViewModel

/**
 * Whole-session transcription screen - 2026-09-25 B-direction redesign.
 * Server address input + file picker + transcription status + bilingual
 * subtitle list on pure white/black backgrounds. Interactions preserved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WholeSessionScreen(
    onBack: () -> Unit,
    viewModel: TranslatorViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var serverUrl by remember { mutableStateOf("http://192.168.1.249:8001") }
    var selectedName by remember { mutableStateOf<String?>(null) }
    val cs = MaterialTheme.colorScheme

    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            try {
                val bytes = context.contentResolver.openInputStream(it)?.readBytes()
                val name = it.lastPathSegment ?: "audio.wav"
                if (bytes != null) {
                    selectedName = name
                    viewModel.transcribeWholeSession(bytes, name, serverUrl)
                }
            } catch (e: Exception) {
                viewModel.dismissError()
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "整课转录",
                            style = MaterialTheme.typography.headlineMedium,
                            color = cs.onSurface,
                        )
                        Text(
                            "录音 -> 本地ASR -> 双语字幕",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = cs.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = cs.background,
                    titleContentColor = cs.onSurface,
                ),
            )
        },
        containerColor = cs.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("转录服务地址") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    filePicker.launch(arrayOf("audio/wav", "audio/mpeg", "audio/mp3", "audio/x-m4a", "audio/aac", "audio/*"))
                },
                modifier = Modifier.padding(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = cs.primary,
                    contentColor = cs.onPrimary,
                ),
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("选择整课录音")
            }

            if (state.isProcessing) {
                Spacer(Modifier.height(24.dp))
                CircularProgressIndicator(color = cs.primary)
                Spacer(Modifier.height(12.dp))
                Text(state.status, color = cs.onSurfaceVariant)
            } else if (selectedName != null && state.entries.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "\u300C$selectedName\u300D转写完成 ${state.entries.size} 段",
                    color = cs.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.entries) { entry ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = cs.surfaceContainer,
                            contentColor = cs.onSurface,
                        ),
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.35f)),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                entry.timestamp,
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.primary,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                entry.english,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = cs.onSurface,
                            )
                            Spacer(Modifier.height(4.dp))
                            /**
                             * 【2026-11-03】完整显示，不截断。
                             * 整课视图是用户课后回看课堂内容的地方 —— 截断等于内容丢失。
                             */
                            Text(
                                entry.chinese,
                                style = MaterialTheme.typography.bodyMedium,
                                color = cs.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
