package com.classroom.translator.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.classroom.translator.ui.TranslatorViewModel

/**
 * Settings screen - 2026-09-25 B-direction redesign.
 * Three grouped cards (ASR / cloud translation / LLM translation) + quick
 * preset segmented row (classroom / lecture / online meeting). Pure white/black
 * backgrounds; all tokens from MaterialTheme. ViewModel wiring preserved verbatim.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: TranslatorViewModel,
) {
    val state by viewModel.state.collectAsState()
    val settings = state.settings
    val cs = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "设置",
                        style = MaterialTheme.typography.headlineMedium,
                        color = cs.onSurface,
                    )
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
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {


            SectionHeader("语音识别（ASR）")
            Spacer(Modifier.height(8.dp))

            SettingCard {
                SettingTextField(
                    label = "密钥",
                    value = settings.asrApiKey,
                    isPassword = true,
                    onChange = { viewModel.updateSettings { s -> s.copy(asrApiKey = it) } },
                )
                Text(
                    "本地推理引擎：Qwen3-ASR-0.6B（OpenCL/GPU，端口见上方接口地址）",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.primary,
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

            Spacer(Modifier.height(16.dp))
            SectionHeader("云端翻译 (M3 默认, 覆盖本地翻译)")
            SettingCard {
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
                    color = cs.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))

            SectionHeader("翻译（大模型）")
            Spacer(Modifier.height(8.dp))

            SettingCard {
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
    }
}

@Composable
private fun SettingCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(
            containerColor = cs.surfaceContainer,
            contentColor = cs.onSurface,
        ),
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
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
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
)


