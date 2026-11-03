package com.classroom.translator.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.classroom.translator.ui.TranslationEntry
import com.classroom.translator.ui.TranslatorUiState
import com.classroom.translator.ui.TranslatorViewModel
import com.classroom.translator.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Main recording screen — faithful rebuild of the B-direction design
 * (2026-09-25 dual-theme final, rendered mock: render-main-dark/light.png).
 *
 * Structure (mirrors the design source screen-main.html, 405x864 viewport):
 *   appbar   = [译 gradient mark 32] 课堂翻译 (titleLarge) ... [settings 44]
 *   chiprow  = status pill h30 (brand gradient while recording, pulsing dot)
 *   stream   = transcript cards (ts / zh primary / en secondary);
 *              latest card: gradient ring + glow, 2-line gradient EN + live chip
 *   dock     = surface-fade overlay: [leaf FAB 64] [完成 pill] / stats pill
 *   dark only = ambient radial glows (top-right primary / bottom-left tertiary)
 *
 * Interactions preserved: long-press delete dialog, mic toggle (audioLevel
 * no longer scales the FAB — the design uses the pulse ring instead),
 * finish -> history, error snackbar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToHistory: () -> Unit = {},
    viewModel: TranslatorViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val cs = MaterialTheme.colorScheme
    val tk = AppTheme.tokens
    val listState = rememberLazyListState()

    // Session timer (display-only; VM keeps no duration field).
    var elapsedSec by remember { mutableStateOf(0L) }

    /**
     * 【2026-11-03】引擎轮换状态（给用户看）。
     * 用户要求「定时重启 / 双引擎并发」—— 必须让他看见确实在轮换，
     * 否则用户只会觉得"App 偶尔卡一下"。
     */
    var engineInfo by remember { mutableStateOf("") }
    // ⚠️ LocalContext.current 只能在 @Composable 上下文取 —— 必须提到 LaunchedEffect 外面
    val appContext = LocalContext.current.applicationContext as? android.app.Application
    LaunchedEffect(state.isRecording, appContext) {
        if (!state.isRecording) { engineInfo = ""; return@LaunchedEffect }
        while (state.isRecording) {
            engineInfo = if (appContext != null) {
                com.classroom.translator.local.LocalLlmServerManager.get(appContext).rotationInfo()
            } else ""
            delay(3000)
        }
    }
    LaunchedEffect(state.isRecording) {
        if (state.isRecording) {
            val base = System.currentTimeMillis() - elapsedSec * 1000L
            while (true) {
                elapsedSec = (System.currentTimeMillis() - base) / 1000L
                delay(1000L)
            }
        }
    }
    LaunchedEffect(state.entries.isEmpty(), state.isRecording) {
        if (!state.isRecording && state.entries.isEmpty()) elapsedSec = 0L
    }
    // 仅当最新条目超出视口（长列表）时才自动滚动到底；短列表保持顶部显示，与设计稿静态态一致。
    LaunchedEffect(state.entries.size) {
        val lastIndex = state.entries.lastIndex
        if (lastIndex < 0) return@LaunchedEffect
        val info = listState.layoutInfo
        if (info.totalItemsCount == 0) return@LaunchedEffect
        val lastItem = info.visibleItemsInfo.lastOrNull()
        val fullyVisible = lastItem != null && lastItem.index == lastIndex &&
            lastItem.offset + lastItem.size <= info.viewportEndOffset
        if (!fullyVisible) listState.animateScrollToItem(lastIndex)
    }

    Scaffold(containerColor = cs.surface) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Ambient glow (dark mode only) — CSS radial gradients.
            Box(Modifier.matchParentSize().ambientGlow(tk))

            Column(Modifier.fillMaxSize()) {
                MainAppBar(onNavigateToSettings = onNavigateToSettings)
                ChipRow(state)

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    if (state.entries.isEmpty()) {
                        MainEmptyState()
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(top = 2.dp, bottom = 138.dp),
                        ) {
                            itemsIndexed(state.entries, key = { _, entry -> entry.id }) { index, entry ->
                                TranslationCard(
                                    entry = entry,
                                    isLatest = index == state.entries.lastIndex,
                                    isRecording = state.isRecording,
                                    onDelete = { viewModel.deleteEntry(entry.id) },
                                )
                            }
                        }
                    }
                    if (state.isProcessing) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .align(Alignment.TopCenter),
                            color = cs.primary,
                            trackColor = cs.surfaceContainerHighest,
                        )
                    }
                }
            }

            state.error?.let { error ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 140.dp),
                    containerColor = cs.errorContainer,
                    contentColor = cs.onErrorContainer,
                    action = {
                        TextButton(onClick = { viewModel.dismissError() }) {
                            Text("OK", color = cs.onErrorContainer)
                        }
                    },
                ) { Text(error) }
                LaunchedEffect(error) {
                    delay(5000)
                    viewModel.dismissError()
                }
            }

            DockOverlay(
                modifier = Modifier.align(Alignment.BottomCenter),
                isRecording = state.isRecording,
                isProcessing = state.isProcessing,
                count = state.entries.size,
                elapsedSec = elapsedSec,
                engineInfo = engineInfo,
                onToggle = { viewModel.toggleRecording() },
                onFinish = {
                    viewModel.finishSession()
                    onNavigateToHistory()
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Design helpers: CSS 135deg gradient brush / gradient fill / gradient ring /
// ambient glow — all computed exactly like the design's CSS would render.
// ---------------------------------------------------------------------------

private fun brush135(colors: List<Color>, w: Float, h: Float): Brush {
    val d = 0.70710678f
    val len = abs(w * d) + abs(h * d)
    val cx = w / 2f
    val cy = h / 2f
    return Brush.linearGradient(
        colors = colors,
        start = Offset(cx - d * len / 2f, cy - d * len / 2f),
        end = Offset(cx + d * len / 2f, cy + d * len / 2f),
    )
}

private fun Modifier.gradientFill(shape: Shape, colors: List<Color>): Modifier =
    this.clip(shape).drawWithCache {
        val brush = brush135(colors, size.width, size.height)
        onDrawBehind { drawRect(brush) }
    }

private fun Modifier.gradientRing(shape: Shape, width: Dp, colors: List<Color>): Modifier =
    this.drawWithContent {
        drawContent()
        val stroke = width.toPx()
        val inner = Size(size.width - stroke, size.height - stroke)
        if (inner.width > 0f && inner.height > 0f) {
            val brush = brush135(colors, size.width, size.height)
            val outline = shape.createOutline(inner, layoutDirection, this)
            translate(stroke / 2f, stroke / 2f) {
                val path = Path()
                when (outline) {
                    is Outline.Rounded -> path.addRoundRect(outline.roundRect)
                    is Outline.Rectangle -> path.addRect(outline.rect)
                    is Outline.Generic -> path.addPath(outline.path)
                }
                drawPath(path, brush = brush, style = Stroke(stroke))
            }
        }
    }

private fun Modifier.ambientGlow(tk: com.classroom.translator.ui.theme.AppTokens): Modifier =
    if (!tk.ambient) this else this.drawWithCache {
        val w = size.width
        val h = size.height
        onDrawBehind {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(tk.glowTop, Color.Transparent),
                    center = Offset(w * 0.85f, -0.05f * h),
                    radius = w * 1.2f * 0.62f,
                ),
                radius = w * 1.2f * 0.62f,
                center = Offset(w * 0.85f, -0.05f * h),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(tk.glowBottom, Color.Transparent),
                    center = Offset(-0.10f * w, 1.05f * h),
                    radius = w * 1.1f * 0.60f,
                ),
                radius = w * 1.1f * 0.60f,
                center = Offset(-0.10f * w, 1.05f * h),
            )
        }
    }

// ---------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------

@Composable
private fun MainAppBar(onNavigateToSettings: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tk = AppTheme.tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .shadow(2.dp, RoundedCornerShape(10.dp), ambientColor = tk.gradA.copy(alpha = 0.30f), spotColor = tk.gradA.copy(alpha = 0.30f))
                .gradientFill(RoundedCornerShape(10.dp), listOf(tk.gradA, tk.gradB)),
            contentAlignment = Alignment.Center,
        ) {
            Text("译", color = cs.onPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            "课堂翻译",
            style = MaterialTheme.typography.titleLarge,
            color = cs.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNavigateToSettings) {
            Icon(
                Icons.Outlined.Settings,
                contentDescription = "设置",
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun ChipRow(state: TranslatorUiState) {
    val cs = MaterialTheme.colorScheme
    val tk = AppTheme.tokens
    val active = state.isRecording
    val chipShape = RoundedCornerShape(50)

    val infinite = rememberInfiniteTransition(label = "chip")
    val dotScale by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.9f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "dotScale",
    )
    val dotAlpha by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "dotAlpha",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp),
    ) {
        val chipModifier =
            (if (active) Modifier.gradientFill(chipShape, listOf(tk.gradA, tk.gradB))
             else Modifier.background(cs.surfaceContainerHigh, chipShape))
                .height(30.dp)
                .padding(start = 10.dp, end = 13.dp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = chipModifier,
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(14.dp)) {
                if (active) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .graphicsLayer {
                                scaleX = dotScale
                                scaleY = dotScale
                                alpha = dotAlpha
                            }
                            .background(cs.onPrimary, CircleShape),
                    )
                }
                Box(Modifier.size(8.dp).background(if (active) cs.onPrimary else cs.primary, CircleShape))
            }
            Spacer(Modifier.width(7.dp))
            Text(
                if (active) "录音中 · 已连接服务器" else state.status,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.24.sp,
                ),
                color = if (active) cs.onPrimary else cs.onSurface,
            )
        }
    }
}

@Composable
private fun MainEmptyState() {
    val cs = MaterialTheme.colorScheme
    val tk = AppTheme.tokens
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .gradientFill(RoundedCornerShape(32.dp), listOf(tk.gradSoftA, tk.gradSoftB))
                    .border(1.dp, cs.primary.copy(alpha = 0.24f), RoundedCornerShape(32.dp))
                    .gradientRing(RoundedCornerShape(32.dp), 1.5.dp, listOf(tk.gradA, tk.gradB)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.GraphicEq,
                    contentDescription = null,
                    modifier = Modifier.size(38.dp),
                    tint = cs.primary,
                )
            }
            Spacer(Modifier.height(18.dp))
            Text("点击麦克风开始", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            Spacer(Modifier.height(8.dp))
            Text(
                "英语语音 → 中文翻译",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TranslationCard(
    entry: TranslationEntry,
    isLatest: Boolean,
    isRecording: Boolean,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val tk = AppTheme.tokens
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除此条目？") },
            // 【2026-11-03】完整显示，不再截断
            text = { Text(entry.chinese) },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete() }) {
                    Text("删除", color = cs.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
        )
    }

    val shape = MaterialTheme.shapes.medium
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isLatest) Modifier.shadow(
                    elevation = 6.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = tk.gradA.copy(alpha = 0.30f),
                    spotColor = tk.gradA.copy(alpha = 0.30f),
                ) else Modifier
            )
            .clip(shape)
            .background(cs.surfaceContainerLow)
            .border(1.dp, tk.hairline, shape)
            .then(if (isLatest) Modifier.gradientRing(shape, 1.5.dp, listOf(tk.gradA, tk.gradB)) else Modifier)
            .combinedClickable(onClick = {}, onLongClick = { showDeleteDialog = true })
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Column {
            Text(
                entry.timestamp.take(5),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.55.sp),
                color = tk.ts,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                entry.chinese,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, lineHeight = 24.sp),
                color = cs.onSurface,
            )
            Spacer(Modifier.height(5.dp))
            /**
             * 【2026-11-03 修复：英文完整显示，不再截断】
             *
             * 用户反馈「识别结果没有显示完整」：卡片上英文只显示一行 / 两行，
             * 后面的内容变成 "…" 被吃掉。课堂翻译的英文是**老师说的话**，
             * 是用户上课时唯一能对照的原文 —— 截断等于把课听丢了。
             *
             * 现在英文不限行数、完整显示（中文本来就不限行）。
             * 视觉上多占空间是应该的代价：内容完整 > 卡片紧凑。
             */
            if (isLatest) {
                Text(
                    entry.english,
                    style = TextStyle(
                        brush = Brush.horizontalGradient(listOf(tk.latestEnA, cs.onSurfaceVariant)),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    ),
                )
                if (isRecording) {
                    Spacer(Modifier.height(8.dp))
                    LiveChip()
                }
            } else {
                Text(
                    entry.english,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LiveChip() {
    val cs = MaterialTheme.colorScheme
    val tk = AppTheme.tokens
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .gradientFill(RoundedCornerShape(8.dp), listOf(tk.gradSoftA, tk.gradSoftB))
            .border(1.dp, cs.primary.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        EqBars()
        Spacer(Modifier.width(7.dp))
        Text(
            "正在转写",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.88.sp),
            color = cs.onPrimaryContainer,
        )
    }
}

@Composable
private fun EqBars() {
    val cs = MaterialTheme.colorScheme
    val infinite = rememberInfiniteTransition(label = "eq")
    val heights = listOf(7, 13, 9, 12)
    val factors = heights.mapIndexed { i, _ ->
        infinite.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, delayMillis = i * 160, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "eq$i",
        )
    }
    Row(
        modifier = Modifier.height(14.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        heights.forEachIndexed { i, hgt ->
            val f by factors[i]
            Box(
                Modifier
                    .width(3.dp)
                    .height(hgt.dp)
                    .graphicsLayer {
                        scaleY = f
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .background(cs.primary, RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun DockOverlay(
    modifier: Modifier = Modifier,
    isRecording: Boolean,
    isProcessing: Boolean,
    count: Int,
    elapsedSec: Long,
    engineInfo: String = "",
    onToggle: () -> Unit,
    onFinish: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.42f to cs.surface,
                    1f to cs.surface,
                )
            )
            .padding(start = 16.dp, end = 16.dp, top = 30.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        ) {
            RecordFab(isRecording = isRecording, isProcessing = isProcessing, onClick = onToggle)
            // 【2026-10-20 修复 3】「完成」按钮不再要求识别到内容。
            // 原先 `if (count > 0)` —— 零识别时按钮根本不出现，用户无法主动保存，
            // 只能被迫走"停止录音"（而停止路径也不写 session 文件 → 录音白录）。
            // 现在：只要在录音中，或已经有内容，按钮就可见可点。
            if (count > 0 || isRecording) {
                FinishPill(onClick = onFinish)
            }
        }
        if (count > 0 || isRecording) {
            StatsBar(count = count, elapsedSec = elapsedSec, engineInfo = engineInfo)
        }
    }
}

@Composable
private fun RecordFab(isRecording: Boolean, isProcessing: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tk = AppTheme.tokens
    val fabShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomEnd = 32.dp, bottomStart = 14.dp)
    val ringShape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp, bottomEnd = 36.dp, bottomStart = 18.dp)

    val infinite = rememberInfiniteTransition(label = "fab")
    val pulseScale by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.28f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "fabScale",
    )
    val pulseAlpha by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "fabAlpha",
    )

    Box(Modifier.size(72.dp), contentAlignment = Alignment.BottomCenter) {
        if (isRecording) {
            Box(
                Modifier
                    .size(64.dp)
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                        alpha = pulseAlpha
                    }
                    .border(2.dp, tk.gradA.copy(alpha = 0.45f), RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomEnd = 32.dp, bottomStart = 14.dp)),
            )
        }
        Box(
            modifier = Modifier
                .size(64.dp)
                .shadow(
                    elevation = 8.dp,
                    shape = fabShape,
                    clip = false,
                    ambientColor = tk.gradA.copy(alpha = 0.42f),
                    spotColor = tk.gradA.copy(alpha = 0.42f),
                )
                .gradientFill(fabShape, listOf(tk.gradA, tk.gradB))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isProcessing && !isRecording -> CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    color = cs.onPrimary,
                    strokeWidth = 2.5.dp,
                )
                isRecording -> Icon(Icons.Rounded.Stop, contentDescription = "停止", modifier = Modifier.size(28.dp), tint = cs.onPrimary)
                else -> Icon(Icons.Rounded.Mic, contentDescription = "开始录音", modifier = Modifier.size(30.dp), tint = cs.onPrimary)
            }
        }
    }
}

@Composable
private fun FinishPill(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(50))
            .background(cs.secondaryContainer)
            .border(1.dp, cs.primary.copy(alpha = 0.35f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
    ) {
        Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(17.dp), tint = cs.primary)
        Spacer(Modifier.width(6.dp))
        Text(
            "完成",
            style = MaterialTheme.typography.labelLarge,
            color = cs.onSecondaryContainer,
        )
    }
}

@Composable
private fun StatsBar(count: Int, elapsedSec: Long, engineInfo: String = "") {
    val cs = MaterialTheme.colorScheme
    val tk = AppTheme.tokens
    val st = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum", letterSpacing = 0.36.sp)
    val shape = RoundedCornerShape(50)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .clip(shape)
                .background(cs.surfaceContainer.copy(alpha = 0.88f))
                .border(1.dp, tk.hairline, shape),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("已翻译", style = st, color = cs.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text("$count", style = st.copy(fontWeight = FontWeight.SemiBold), color = cs.onSurface)
            Spacer(Modifier.width(4.dp))
            Text("句 ·", style = st, color = cs.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text(fmtDuration(elapsedSec), style = st.copy(fontWeight = FontWeight.SemiBold), color = cs.onSurface)
        }
        // 【2026-11-03】引擎轮换指示：让用户看见"引擎在按计划换新"，
        // 而不是只看到一个卡顿的 App。
        if (engineInfo.isNotBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(
                engineInfo,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    letterSpacing = 0.2.sp,
                ),
                color = cs.onSurfaceVariant.copy(alpha = 0.72f),
            )
        }
    }
}

private fun fmtDuration(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
