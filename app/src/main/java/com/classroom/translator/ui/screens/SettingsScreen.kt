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
import kotlinx.coroutines.launch
import com.classroom.translator.audio.DevTestRunner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult

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

                    // ASR 引擎下拉: 三个最优引擎 (2026-09-07 大马口音混说评测)
                    var asrExpanded by remember { mutableStateOf(false) }
                    // 每项 (model_id, display_name, base_url, needs_force_language)
                    // base_url 默认 8000/8001, 用户也可手动改覆盖
                    val asrEngines = listOf(
                        Triple("Qwen3-ASR", "Qwen3-ASR-1.7B (本地 8000, 纯英语/马来单语)", "http://127.0.0.1:8000/v1"),
                        Triple("polyglot-lion-1.7b", "Polyglot-Lion-1.7B (本地 8001, 新马4语混说)", "http://127.0.0.1:8001/v1"),
                        Triple("fun-asr-realtime-2026-02-28", "FunASR Realtime (云端百炼, 仅中英)", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
                        Triple("ondevice-whisper", "本机离线 whisper-small (断网可用)", ""),
                    )
                    ExposedDropdownMenuBox(
                        expanded = asrExpanded,
                        onExpandedChange = { asrExpanded = it }
                    ) {
                        val currentLabel = asrEngines.find { it.first == settings.asrModel }?.second
                            ?: "自定义: ${settings.asrModel}"
                        OutlinedTextField(
                            value = currentLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("ASR 引擎") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(asrExpanded) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                            colors = textFieldColors()
                        )
                        ExposedDropdownMenu(expanded = asrExpanded, onDismissRequest = { asrExpanded = false }) {
                            asrEngines.forEach { (modelId, label, defaultUrl) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        // 切引擎时同步更新 baseUrl (用户可在下方手动覆盖)
                                        viewModel.updateSettings { s ->
                                            s.copy(
                                                asrModel = modelId,
                                                asrBaseUrl = if (s.asrBaseUrl.contains("127.0.0.1") || s.asrBaseUrl.contains("dashscope")) defaultUrl else s.asrBaseUrl,
                                                asrMode = if (modelId == "ondevice-whisper") "ondevice" else if (modelId.contains("fun-asr")) "dashscope" else "local_vllm"
                                            )
                                        }
                                        asrExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // ASR Mode selector
                    var modeExpanded by remember { mutableStateOf(false) }
                    val modes = listOf(
                        "dashscope" to "DashScope 流式 (云端)",
                        "local_vllm" to "本地 vLLM (Qwen3-ASR)",
                        "mimo" to "MIMO 分段",
                        "ondevice" to "本机离线 (断网可用)",
                    )
                    ExposedDropdownMenuBox(
                        expanded = modeExpanded,
                        onExpandedChange = { modeExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = modes.find { it.first == settings.asrMode }?.second ?: settings.asrMode,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("ASR模式") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modeExpanded) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                            colors = textFieldColors()
                        )
                        ExposedDropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
                            modes.forEach { (code, name) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = { viewModel.updateSettings { s -> s.copy(asrMode = code) }; modeExpanded = false }
                                )
                            }
                        }
                    }

                    // On-device batch test (P2 harness)
                    var testStatus by remember { mutableStateOf("") }
                    val testScope = rememberCoroutineScope()
                    val testCtx = LocalContext.current
                    val permLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { granted ->
                        if (granted) testScope.launch {
                            DevTestRunner.runAll(testCtx) { s -> testStatus = s }
                        } else testStatus = "need audio permission"
                    }
                    Button(
                        onClick = {
                            permLauncher.launch(android.Manifest.permission.READ_MEDIA_AUDIO)
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) { Text("机上30条实测（离线）") }
                    if (testStatus.isNotEmpty()) Text(testStatus)

                    // ===== 流式内录测试模式（真实素材端到端）=====
                    var testPath by remember { mutableStateOf("/sdcard/Download/BV14hPxepEHY_16k.wav") }
                    var testMsg by remember { mutableStateOf("") }
                    // 在 Composable 作用域拿 context/activity/VM（onClick 里不能调 LocalContext）
                    val appCtx = LocalContext.current
                    val activity = appCtx as? android.app.Activity
                    val testVm = (appCtx.applicationContext as com.classroom.translator.TranslatorApp).viewModel
                    OutlinedTextField(
                        value = testPath,
                        onValueChange = { testPath = it },
                        label = { Text("测试素材路径") },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                        colors = textFieldColors()
                    )
                    Button(
                        onClick = {
                            if (testVm == null) { testMsg = "VM 未初始化"; return@Button }
                            val act = activity as? com.classroom.translator.MainActivity
                            if (act == null) { testMsg = "需要 MainActivity 上下文"; return@Button }
                            // 记录待测素材；授权回调拿到 MediaProjection 后自动启动测试
                            testVm.requestTestWithProjection(testPath)
                            act.requestProjectionPermission()
                            testMsg = "授权后自动开始测试（请允许屏幕录制）"
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) { Text("▶ 流式内录测试（播放素材→内录→ASR→翻译）") }
                    if (testMsg.isNotEmpty()) Text(testMsg, style = MaterialTheme.typography.bodySmall)
                    if (testVm != null && testVm.testLatencyMs > 0) {
                        Text(
                            "最近延迟: ${testVm.testLatencyMs}ms",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

// Language dropdown
                    var langExpanded by remember { mutableStateOf(false) }
                    val languages = listOf("en" to "英语（English）", "zh" to "中文", "ms" to "马来语（Malay）", "auto" to "自动检测")
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

            // 云端翻译 (流式后覆盖 / 双端矫正) - 默认值编译进 BuildConfig,用户可改
            Spacer(Modifier.height(16.dp))
            SectionHeader("云端翻译 (M3 默认, 覆盖本地翻译)")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingTextField(
                        label = "云端密钥",
                        value = settings.cloudApiKey,
                        isPassword = true,
                        onChange = { viewModel.updateSettings { s -> s.copy(cloudApiKey = it) } }
                    )
                    SettingTextField(
                        label = "云端接口地址",
                        value = settings.cloudBaseUrl,
                        onChange = { viewModel.updateSettings { s -> s.copy(cloudBaseUrl = it) } }
                    )
                    SettingTextField(
                        label = "云端模型",
                        value = settings.cloudModel,
                        onChange = { viewModel.updateSettings { s -> s.copy(cloudModel = it) } }
                    )
                    Text(
                        "默认值由 BuildConfig 注入,无需填即可使用 M3 直连 minimaxi.com",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                        "qwen3.5-2b" to "本地 Qwen3-1.7B (当前, 0.66s)",
                        "minimax-m3" to "MiniMax M3",
                        "kimi-k3" to "Kimi K3",
                        "glm-5.2" to "GLM 5.2",
                        "qwen3.8-max" to "Qwen3.8 Max"
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

            Spacer(Modifier.height(24.dp))

            // Presets
            SectionHeader("快速预设")
            Spacer(Modifier.height(8.dp))

            // 预设 1: Polyglot-Lion (混说最佳, 默认推荐)
            PresetButton("Polyglot-Lion 混说 (本地 8001, 推荐)") {
                viewModel.updateSettings { s -> s.copy(
                    asrBaseUrl = "http://127.0.0.1:8001/v1",
                    asrModel = "polyglot-lion-1.7b",
                    asrLanguage = "auto",
                    asrMode = "local_vllm",
                    translateBaseUrl = "http://127.0.0.1:8002/v1",
                    translateModel = "qwen3.5-2b"
                )}
            }
            // 预设 2: Qwen3-ASR (纯英语最强, 备选)
            PresetButton("Qwen3-ASR 纯英语 (本地 8000)") {
                viewModel.updateSettings { s -> s.copy(
                    asrBaseUrl = "http://127.0.0.1:8000/v1",
                    asrModel = "Qwen3-ASR",
                    asrLanguage = "auto",
                    asrMode = "local_vllm",
                    translateBaseUrl = "http://127.0.0.1:8002/v1",
                    translateModel = "qwen3.5-2b"
                )}
            }
            PresetButton("DashScope + MiniMax-M3") {
                viewModel.updateSettings { s -> s.copy(
                    asrBaseUrl = "https://token-plan-cn.xiaomimimo.com/v1",
                    asrModel = "mimo-v2.5",
                    translateBaseUrl = "https://api.deepseek.com/v1",
                    translateModel = "deepseek-chat"
                )}
            }
            PresetButton("DashScope + MiniMax Highspeed") {
                viewModel.updateSettings { s -> s.copy(
                    asrBaseUrl = "https://token-plan-cn.xiaomimimo.com/v1",
                    asrModel = "mimo-v2.5",
                    translateBaseUrl = "https://token-plan-cn.xiaomimimo.com/v1",
                    translateModel = "mimo-v2.5-pro"
                )}
            }
            PresetButton("DashScope + DeepSeek") {
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
