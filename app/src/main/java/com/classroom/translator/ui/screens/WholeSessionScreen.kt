package com.classroom.translator.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.classroom.translator.ui.TranslatorViewModel
import com.classroom.translator.ui.theme.Blue500
import com.classroom.translator.ui.theme.DarkOnSurfaceVariant
import com.classroom.translator.ui.theme.DarkSurface
import com.classroom.translator.ui.theme.DarkSurfaceVariant

/**
 * 整课录音转录页面：选择整节课录音 → 上传到本地 VibeVoice 服务 → 双语字幕。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WholeSessionScreen(
    onBack: () -> Unit,
    viewModel: TranslatorViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var serverUrl by remember { mutableStateOf("http://192.168.1.249:8001") }
    var selectedName by remember { mutableStateOf<String?>(null) }

    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
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
                        Text("整课转录", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("录音 → 本地ASR → 双语字幕", style = MaterialTheme.typography.labelSmall, color = DarkOnSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = DarkSurface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))

            // 服务地址输入
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("转录服务地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(8.dp))

            // 选择音频
            Button(onClick = {
                filePicker.launch(arrayOf("audio/wav", "audio/mpeg", "audio/mp3", "audio/x-m4a", "audio/aac", "audio/*"))
            }) {
                Icon(Icons.Default.FileOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("选择整课录音")
            }

            if (state.isProcessing) {
                Spacer(Modifier.height(24.dp))
                CircularProgressIndicator(color = Blue500)
                Spacer(Modifier.height(12.dp))
                Text(state.status, color = DarkOnSurfaceVariant)
            } else if (selectedName != null && state.entries.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("「$selectedName」转写完成 ${state.entries.size} 段", color = DarkOnSurfaceVariant)
            }

            Spacer(Modifier.height(12.dp))

            // 双语字幕列表
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.entries) { entry ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                entry.timestamp,
                                fontSize = 11.sp,
                                color = Blue500,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                entry.english,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                entry.chinese,
                                style = MaterialTheme.typography.bodyMedium,
                                color = DarkOnSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
