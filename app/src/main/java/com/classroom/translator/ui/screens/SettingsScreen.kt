package com.classroom.translator.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.classroom.translator.ui.TranslatorViewModel

/**
 * Settings screen. All colors / typography / shapes from MaterialTheme tokens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: TranslatorViewModel,
) {
    val state by viewModel.state.collectAsState()
    val settings = state.settings

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "设置",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
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
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            SectionHeader("语音识别（ASR）")
            Spacer(Modifier.height(8.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingTextField(
                        label = "密钥",
                        value = settings.asrApiKey,
                        isPassword = true,
                        onChange = { viewModel.updateSettings { s -> s.copy(asrApiKey = it) } },
                    )
                    Text(
                        "本地推理引擎：Qwen3-ASR-0.6B（OpenCL/GPU，端口见上方接口地址）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    SettingTextField(
                        label = "接口地址",
                        value = settings.asrBaseUrl,
                        onChange = { viewModel.updateSettings { s -> s.copy(asrBaseUrl = it) } },
                    )

                    var langExpanded by remember { mutableStateOf(false) }
                    val languages = listOf(
                        "en" to "英语（English）",
                        "zh" to "中文",
                        "ms" to "马来语（Malay）",
                        "auto" to "自动检测",
                    )
                    ExposedDropdownMenuBox(
                        expanded = langExpanded,
                        onExpandedChange = { langExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = languages.find { it.first == settings.asrLanguage }?.second ?: settings.asrLanguage,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("识别语种") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(langExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            colors = textFieldColors(),
                        )
                        ExposedDropdownMenu(
                            expanded = langExpanded,
                            onDismissRequest = { langExpanded = false },
                        ) {
                            languages.forEach { (code, name) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        viewModel.updateSettings { s -> s.copy(asrLanguage = code) }
                                        langExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionHeader("云端翻译 (M3 默认, 覆盖本地翻译)")
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingTextField(
                        label = "云端密钥",
                        value = settings.cloudApiKey,
                        isPassword = true,
                        onChange = { viewModel.updateSettings { s -> s.copy(cloudApiKey = it) } },
                    )
                    SettingTextField(
                        label = "云端接口地址",
                        value = settings.cloudBaseUrl,
                        onChange = { viewModel.updateSettings { s -> s.copy(cloudBaseUrl = it) } },
                    )
                    SettingTextField(
                        label = "云端模型",
                        value = settings.cloudModel,
                        onChange = { viewModel.updateSettings { s -> s.copy(cloudModel = it) } },
                    )
                    Text(
                        "默认值由 BuildConfig 注入,无需填即可使用 M3 直连 minimaxi.com",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            SectionHeader("翻译（大模型）")
            Spacer(Modifier.height(8.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingTextField(
                        label = "密钥",
                        value = settings.translateApiKey,
                        isPassword = true,
                        onChange = { viewModel.updateSettings { s -> s.copy(translateApiKey = it) } },
                    )
                    SettingTextField(
                        label = "接口地址",
                        value = settings.translateBaseUrl,
                        onChange = { viewModel.updateSettings { s -> s.copy(translateBaseUrl = it) } },
                    )

                    var transExpanded by remember { mutableStateOf(false) }
                    val transModels = listOf(
                        "qwen3-1.7b" to "本地 Qwen3-1.7B (推荐, /no_think)",
                        "minimax-m3" to "MiniMax M3",
                        "kimi-k3" to "Kimi K3",
                        "glm-5.2" to "GLM 5.2",
                        "qwen3.8-max" to "Qwen3.8 Max",
                    )
                    ExposedDropdownMenuBox(
                        expanded = transExpanded,
                        onExpandedChange = { transExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = settings.translateModel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("模型") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(transExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                            colors = textFieldColors(),
                        )
                        ExposedDropdownMenu(
                            expanded = transExpanded,
                            onDismissRequest = { transExpanded = false },
                        ) {
                            transModels.forEach { (id, name) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        viewModel.updateSettings { s -> s.copy(translateModel = id) }
                                        transExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            SectionHeader("快速预设")
            Spacer(Modifier.height(8.dp))
            PresetButton("本地 GPU 全本地（当前方案，推荐）") {
                viewModel.updateSettings { s -> s.copy(
                    asrBaseUrl = "http://127.0.0.1:8000/v1",
                    asrModel = "Qwen3-ASR",
                    asrLanguage = "auto",
                    asrMode = "local_vllm",
                    translateBaseUrl = "http://127.0.0.1:8002/v1",
                    translateModel = "qwen3-0.6b",
                ) }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun SettingTextField(
    label: String,
    value: String,
    isPassword: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (isPassword) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
        singleLine = true,
        colors = textFieldColors(),
    )
}

@Composable
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
)

@Composable
private fun PresetButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
