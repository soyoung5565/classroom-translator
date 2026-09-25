package com.classroom.translator.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.classroom.translator.ui.theme.AppTheme
import com.classroom.translator.ui.theme.AppTokens
import com.classroom.translator.ui.TranslatorViewModel

/**
 * 设置页 —— 设计稿（screen-settings.html）视觉语言重建：
 *   appbar compact「设置」+ 三组设置卡（语音识别 ASR / 云端翻译 CLOUD / 大模型翻译 LLM）
 *   分组头 = 图标 + 组名 + 等宽 code；条目 = 名称 + 描述 + 控件（分段 / 输入框）
 *   两套主题走同一套 token，无主题分支。
 *
 * 与原设计的差异（已单独向用户说明）：
 *   - 「课堂 / 讲座 / 在线会议」快速预设排：应要求移除；
 *   - 演示用开关（实时转写 / 云端机器翻译 / 课后整课润色）无对应真实功能，未实现；
 *   - 控件全部映射到真实配置项（ASR 语种 / 各服务地址与密钥 / 翻译模型档位）。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: TranslatorViewModel,
) {
    val state by viewModel.state.collectAsState()
    val settings = state.settings
    val cs = MaterialTheme.colorScheme
    val tk = AppTheme.tokens

    Scaffold(containerColor = cs.surface) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(Modifier.matchParentSize().ambientGlow(tk))
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                // appbar compact（设计稿无返回键，用系统返回手势）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "设置",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onSurface,
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // ── 语音识别 ─────────────────────────────
                    GroupHeader(icon = Icons.Rounded.GraphicEq, title = "语音识别", code = "ASR")
                    ItemCard(name = "识别语种", desc = "自动检测源语言，可选课堂常见语种") {
                        SegRow(
                            options = LangOptions,
                            selected = settings.asrLanguage,
                            onSelect = { code -> viewModel.updateSettings { s -> s.copy(asrLanguage = code) } },
                        )
                    }
                    ItemCard(name = "接口地址", desc = "本地推理引擎 Qwen3-ASR-0.6B（OpenCL/GPU）") {
                        FieldBox(
                            value = settings.asrBaseUrl,
                            onChange = { v -> viewModel.updateSettings { s -> s.copy(asrBaseUrl = v) } },
                        )
                    }
                    ItemCard(name = "密钥", desc = "本地引擎通常无需填写") {
                        FieldBox(
                            value = settings.asrApiKey,
                            onChange = { v -> viewModel.updateSettings { s -> s.copy(asrApiKey = v) } },
                            password = true,
                        )
                    }

                    Spacer(Modifier.height(0.dp))
                    // ── 云端翻译 ─────────────────────────────
                    GroupHeader(icon = Icons.Rounded.CloudDone, title = "云端翻译", code = "CLOUD")
                    ItemCard(name = "接口地址", desc = "默认值由 BuildConfig 注入，无需填写即可使用") {
                        FieldBox(
                            value = settings.cloudBaseUrl,
                            onChange = { v -> viewModel.updateSettings { s -> s.copy(cloudBaseUrl = v) } },
                        )
                    }
                    ItemCard(name = "模型", desc = "云端机器翻译模型名") {
                        FieldBox(
                            value = settings.cloudModel,
                            onChange = { v -> viewModel.updateSettings { s -> s.copy(cloudModel = v) } },
                        )
                    }
                    ItemCard(name = "密钥", desc = "云端服务凭证") {
                        FieldBox(
                            value = settings.cloudApiKey,
                            onChange = { v -> viewModel.updateSettings { s -> s.copy(cloudApiKey = v) } },
                            password = true,
                        )
                    }

                    Spacer(Modifier.height(0.dp))
                    // ── 大模型翻译 ───────────────────────────
                    GroupHeader(icon = Icons.Rounded.AutoAwesome, title = "大模型翻译", code = "LLM")
                    ItemCard(name = "模型档位", desc = "本地模型免费 · 云端模型按量计费") {
                        SegWrap(
                            options = ModelOptions,
                            selected = settings.translateModel,
                            onSelect = { id -> viewModel.updateSettings { s -> s.copy(translateModel = id) } },
                            perRow = 3,
                        )
                    }
                    ItemCard(name = "接口地址", desc = "兼容 OpenAI 协议的接口地址") {
                        FieldBox(
                            value = settings.translateBaseUrl,
                            onChange = { v -> viewModel.updateSettings { s -> s.copy(translateBaseUrl = v) } },
                        )
                    }
                    ItemCard(name = "密钥", desc = "大模型服务凭证") {
                        FieldBox(
                            value = settings.translateApiKey,
                            onChange = { v -> viewModel.updateSettings { s -> s.copy(translateApiKey = v) } },
                            password = true,
                        )
                    }
                }
            }
        }
    }
}

private val LangOptions = listOf(
    "en" to "英语",
    "zh" to "中文",
    "ms" to "马来语",
    "auto" to "自动",
)

private val ModelOptions = listOf(
    "qwen3-1.7b" to "本地 Qwen3-1.7B",
    "minimax-m3" to "MiniMax M3",
    "kimi-k3" to "Kimi K3",
    "glm-5.2" to "GLM 5.2",
    "qwen3.8-max" to "Qwen3.8 Max",
)

// ---------------------------------------------------------------------------

private fun Modifier.ambientGlow(tk: AppTokens): Modifier =
    if (!tk.ambient) this else this.drawWithCache {
        val w = size.width
        val h = size.height
        onDrawBehind {
            drawCircle(
                brush = androidx.compose.ui.graphics.Brush.radialGradient(
                    colors = listOf(tk.glowTop, Color.Transparent),
                    center = Offset(w * 0.85f, -0.05f * h),
                    radius = w * 1.2f * 0.62f,
                ),
                radius = w * 1.2f * 0.62f,
                center = Offset(w * 0.85f, -0.05f * h),
            )
            drawCircle(
                brush = androidx.compose.ui.graphics.Brush.radialGradient(
                    colors = listOf(tk.glowBottom, Color.Transparent),
                    center = Offset(-0.10f * w, 1.05f * h),
                    radius = w * 1.1f * 0.60f,
                ),
                radius = w * 1.1f * 0.60f,
                center = Offset(-0.10f * w, 1.05f * h),
            )
        }
    }

@Composable
private fun GroupHeader(icon: ImageVector, title: String, code: String) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp)) {
        Icon(icon, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.28.sp,
            color = cs.onSurface,
        )
        Spacer(Modifier.weight(1f))
        Text(
            code,
            fontSize = 10.sp,
            letterSpacing = 0.4.sp,
            color = cs.onSurfaceVariant.copy(alpha = 0.75f),
        )
    }
}

@Composable
private fun ItemCard(
    name: String,
    desc: String,
    control: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val tk = AppTheme.tokens
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, shape)
            .clip(shape)
            .background(cs.surfaceContainerLow)
            .border(1.dp, tk.hairline, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
        if (desc.isNotBlank()) {
            Text(desc, fontSize = 11.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.height(5.dp))
        control()
    }
}

@Composable
private fun SegRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEach { (id, label) ->
            SegChip(label = label, selected = id == selected, onClick = { onSelect(id) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SegWrap(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    perRow: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { (id, label) ->
                    SegChip(label = label, selected = id == selected, onClick = { onSelect(id) }, modifier = Modifier.weight(1f))
                }
                repeat(perRow - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SegChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val tk = AppTheme.tokens
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .height(32.dp)
            .clip(shape)
            .background(if (selected) cs.primaryContainer else Color.Transparent)
            .border(1.dp, if (selected) Color.Transparent else tk.hairline, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun FieldBox(
    value: String,
    onChange: (String) -> Unit,
    password: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val tk = AppTheme.tokens
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(shape)
            .background(cs.surfaceContainer)
            .border(1.dp, tk.hairline, shape)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 13.sp, color = cs.onSurface),
            cursorBrush = SolidColor(cs.primary),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        if (password) "未设置" else "默认（BuildConfig）",
                        fontSize = 12.sp,
                        color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                inner()
            },
        )
    }
}
