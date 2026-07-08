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
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.ui.unit.dp
import com.classroom.translator.ui.TranslatorViewModel
import com.classroom.translator.ui.theme.Blue500
import com.classroom.translator.ui.theme.DarkOnSurfaceVariant
import com.classroom.translator.ui.theme.DarkSurface
import com.classroom.translator.ui.theme.DarkSurfaceVariant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: TranslatorViewModel
) {
    val state by viewModel.state.collectAsState()
    val settings = state.settings

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = DarkSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // ASR Section
            SectionHeader("语音识别（ASR）")
            Spacer(Modifier.height(8.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingTextField(
                        label = "密钥",
                        value = settings.asrApiKey,
                        isPassword = true,
                        onChange = { viewModel.updateSettings { s -> s.copy(asrApiKey = it) } }
                    )
                    SettingTextField(
                        label = "接口地址",
                        value = settings.asrBaseUrl,
                        onChange = { viewModel.updateSettings { s -> s.copy(asrBaseUrl = it) } }
                    )

                    // Model dropdown
                    var asrExpanded by remember { mutableStateOf(false) }
                    val asrModels = listOf("mimo-v2.5", "mimo-v2.5-asr", "mimo-v2-omni")
                    ExposedDropdownMenuBox(
                        expanded = asrExpanded,
                        onExpandedChange = { asrExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = settings.asrModel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("模型") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(asrExpanded) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                            colors = textFieldColors()
                        )
                        ExposedDropdownMenu(expanded = asrExpanded, onDismissRequest = { asrExpanded = false }) {
                            asrModels.forEach { model ->
                                DropdownMenuItem(
                                    text = {
                                        Text(when(model) {
                                            "mimo-v2.5" -> "mimo-v2.5（推荐）"
                                            "mimo-v2.5-asr" -> "mimo-v2.5-asr（快速，压缩）"
                                            "mimo-v2-omni" -> "mimo-v2-omni（最准确）"
                                            else -> model
                                        })
                                    },
                                    onClick = {
                                        viewModel.updateSettings { s -> s.copy(asrModel = model) }
                                        asrExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Language dropdown
                    var langExpanded by remember { mutableStateOf(false) }
                    val languages = listOf("en" to "英语（English）", "zh" to "中文", "auto" to "自动检测")
                    ExposedDropdownMenuBox(
                        expanded = langExpanded,
                        onExpandedChange = { langExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = languages.find { it.first == settings.asrLanguage }?.second ?: settings.asrLanguage,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("识别语种") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(langExpanded) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth().padding(top = 8.dp),
                            colors = textFieldColors()
                        )
                        ExposedDropdownMenu(expanded = langExpanded, onDismissRequest = { langExpanded = false }) {
                            languages.forEach { (code, name) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        viewModel.updateSettings { s -> s.copy(asrLanguage = code) }
                                        langExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Translation Section
            SectionHeader("翻译（大模型）")
            Spacer(Modifier.height(8.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingTextField(
                        label = "密钥",
                        value = settings.translateApiKey,
                        isPassword = true,
                        onChange = { viewModel.updateSettings { s -> s.copy(translateApiKey = it) } }
                    )
                    SettingTextField(
                        label = "接口地址",
                        value = settings.translateBaseUrl,
                        onChange = { viewModel.updateSettings { s -> s.copy(translateBaseUrl = it) } }
                    )

                    var transExpanded by remember { mutableStateOf(false) }
                    val transModels = listOf(
                        "deepseek-chat" to "DeepSeek",
                        "mimo-v2.5" to "MIMO v2.5",
                        "mimo-v2.5-pro" to "MIMO v2.5 Pro",
                        "qwen-plus" to "通义千问 Plus",
                        "qwen-turbo" to "通义千问 Turbo"
                    )
                    ExposedDropdownMenuBox(
                        expanded = transExpanded,
                        onExpandedChange = { transExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = settings.translateModel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("模型") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(transExpanded) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                            colors = textFieldColors()
                        )
                        ExposedDropdownMenu(expanded = transExpanded, onDismissRequest = { transExpanded = false }) {
                            transModels.forEach { (id, name) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        viewModel.updateSettings { s -> s.copy(translateModel = id) }
                                        transExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Recording Section
            SectionHeader("录音")
            Spacer(Modifier.height(8.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "分段间隔：${settings.chunkSeconds}秒",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DarkOnSurfaceVariant
                    )
                    Slider(
                        value = settings.chunkSeconds.toFloat(),
                        onValueChange = { viewModel.updateSettings { s -> s.copy(chunkSeconds = it.toInt()) } },
                        valueRange = 3f..20f,
                        steps = 16,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "越短响应越快，越长识别越准",
                        style = MaterialTheme.typography.labelSmall,
                        color = DarkOnSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Presets
            SectionHeader("快速预设")
            Spacer(Modifier.height(8.dp))

            PresetButton("MIMO 识别 + DeepSeek 翻译") {
                viewModel.updateSettings { s -> s.copy(
                    asrBaseUrl = "https://token-plan-cn.xiaomimimo.com/v1",
                    asrModel = "mimo-v2.5",
                    translateBaseUrl = "https://api.deepseek.com/v1",
                    translateModel = "deepseek-chat"
                )}
            }
            PresetButton("MIMO 识别 + MIMO 翻译") {
                viewModel.updateSettings { s -> s.copy(
                    asrBaseUrl = "https://token-plan-cn.xiaomimimo.com/v1",
                    asrModel = "mimo-v2.5",
                    translateBaseUrl = "https://token-plan-cn.xiaomimimo.com/v1",
                    translateModel = "mimo-v2.5-pro"
                )}
            }
            PresetButton("MIMO 识别 + 通义千问翻译") {
                viewModel.updateSettings { s -> s.copy(
                    asrBaseUrl = "https://token-plan-cn.xiaomimimo.com/v1",
                    asrModel = "mimo-v2.5",
                    translateBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    translateModel = "qwen-plus"
                )}
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
        color = Blue500,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun SettingTextField(
    label: String,
    value: String,
    isPassword: Boolean = false,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = if (isPassword) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
        singleLine = true,
        colors = textFieldColors()
    )
}

@Composable
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Blue500,
    unfocusedBorderColor = DarkOnSurfaceVariant.copy(alpha = 0.3f),
    focusedLabelColor = Blue500,
    cursorColor = Blue500,
    focusedContainerColor = DarkSurface,
    unfocusedContainerColor = DarkSurface,
)

@Composable
private fun PresetButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = DarkSurfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Text(label)
    }
}
