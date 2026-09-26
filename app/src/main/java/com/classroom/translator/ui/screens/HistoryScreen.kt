package com.classroom.translator.ui.screens

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.HistoryEdu
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.classroom.translator.data.SessionManager
import com.classroom.translator.data.TranslationSession
import com.classroom.translator.ui.theme.AppTheme
import com.classroom.translator.ui.theme.AppTokens
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 历史记录页 + 会话详情页 —— 设计稿忠实重建
 * （2026-09-25 双主题定稿，渲染基准 render-history / render-detail）。
 *
 * 结构（对照 screen-history.html / screen-detail.html，逻辑视口 393×852）：
 *   appbar(compact 20px) + 搜索条(46dp 胶囊) + 分组标签/会话卡(44 图标+日期+时长+chips+chevron)
 *   空状态：渐变圆角 orb + 文案 + 「开始第一课」
 *   详情：返回+标题+分享/下载 → 播放条(播放/暂停/停止+进度) → 双语卡片流(ZH 徽标内联)
 */

// ---------------------------------------------------------------------------
// Design helpers（与 MainScreen 同款：135° 渐变 / 渐变填充 / 渐变描边环 / 环境光）
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

private fun Modifier.ambientGlow(tk: AppTokens): Modifier =
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
// 日期 / 时长工具（真实会话数据）
// ---------------------------------------------------------------------------

private val DATE_FMT: SimpleDateFormat get() = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
private const val DEFAULT_SUBJECT = "英语课"

/** "2026-09-25" -> "9月25日"（解析失败时原样返回） */
private fun fmtMonthDay(date: String): String {
    return try {
        val p = date.split("-")
        if (p.size >= 3) "${p[1].toInt()}月${p[2].toInt()}日" else date
    } catch (e: Exception) { date }
}

/** "HH:mm(:ss)" -> 当天秒数 */
private fun parseClockSec(t: String?): Int? {
    if (t.isNullOrBlank()) return null
    return try {
        val p = t.split(":")
        if (p.size >= 2) {
            val h = p[0].trim().toInt(); val m = p[1].trim().toInt()
            val s = if (p.size >= 3) p[2].trim().toInt() else 0
            h * 3600 + m * 60 + s
        } else null
    } catch (e: Exception) { null }
}

/** 会话时长（秒）：优先首尾条目时间戳，退化为 start/end 时刻 */
private fun sessionDurationSec(s: TranslationSession): Int? {
    val ts = s.entries.mapNotNull { parseClockSec(it.timestamp) }
    if (ts.size >= 2) {
        val d = ts.last() - ts.first()
        if (d > 0) return d
    }
    val a = parseClockSec(s.startTime); val b = parseClockSec(s.endTime)
    if (a != null && b != null) {
        var d = b - a
        if (d < 0) d += 86400
        if (d > 0) return d
    }
    return null
}

private fun fmtDur(totalSec: Int): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun todayStr(): String = DATE_FMT.format(Date())
private fun weekAgoStr(): String {
    val c = Calendar.getInstance(); c.add(Calendar.DAY_OF_YEAR, -6)
    return DATE_FMT.format(c.time)
}

// ---------------------------------------------------------------------------
// HistoryScreen —— 列表态 + 空状态
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    onNavigateToRecording: () -> Unit,
    onNavigateToSession: (Long) -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToWholeSession: () -> Unit = {},
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val tk = AppTheme.tokens
    var sessions by remember { mutableStateOf<List<TranslationSession>>(emptyList()) }
    val sessionManager = remember { SessionManager(context) }
    var query by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<TranslationSession?>(null) }
    val searchFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        sessions = sessionManager.loadSessions()
    }

    val today = remember { todayStr() }
    val weekAgo = remember { weekAgoStr() }
    val filtered = remember(sessions, query) {
        sessions.filter { s ->
            query.isBlank() ||
                fmtMonthDay(s.date).contains(query) ||
                s.date.contains(query) ||
                DEFAULT_SUBJECT.contains(query) ||
                "已转写".contains(query)
        }
    }

    Scaffold(containerColor = cs.surface) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(Modifier.matchParentSize().ambientGlow(tk))

            Column(Modifier.fillMaxSize()) {
                HistoryAppBar(
                    onSearch = { runCatching { searchFocus.requestFocus() } },
                    onNavigateToWholeSession = onNavigateToWholeSession,
                    onSettings = onNavigateToSettings,
                )
                SearchBarView(
                    query = query,
                    onQueryChange = { query = it },
                    focusRequester = searchFocus,
                )
                if (sessions.isEmpty()) {
                    EmptyHistory(onNavigateToRecording = onNavigateToRecording)
                } else {
                    HistoryList(
                        sessions = filtered,
                        today = today,
                        weekAgo = weekAgo,
                        onOpen = onNavigateToSession,
                        onLongPress = { pendingDelete = it },
                    )
                }
            }

            // 录音 FAB（记录入口；设计稿未画，为保持旧版功能入口与可达性保留）
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
                    .size(64.dp)
                    .shadow(
                        8.dp,
                        RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomEnd = 32.dp, bottomStart = 14.dp),
                        ambientColor = tk.gradA.copy(alpha = 0.42f),
                        spotColor = tk.gradA.copy(alpha = 0.42f),
                    )
                    .gradientFill(
                        RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomEnd = 32.dp, bottomStart = 14.dp),
                        listOf(tk.gradA, tk.gradB),
                    )
                    .clickable(onClick = onNavigateToRecording),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Mic,
                    contentDescription = "开始录音",
                    tint = cs.onPrimary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条会话记录？") },
            text = {
                Text("${fmtMonthDay(target.date)} · ${DEFAULT_SUBJECT} · ${target.entries.size} 句\n删除后无法恢复。")
            },
            confirmButton = {
                TextButton(onClick = {
                    sessionManager.deleteSession(target.id)
                    sessions = sessionManager.loadSessions()
                    pendingDelete = null
                }) { Text("删除", color = cs.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun HistoryAppBar(
    onSearch: () -> Unit,
    onNavigateToWholeSession: () -> Unit,
    onSettings: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "历史记录",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = cs.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNavigateToWholeSession) {
            Icon(
                Icons.Rounded.AudioFile,
                contentDescription = "整课转录",
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(onClick = onSearch) {
            Icon(
                Icons.Rounded.Search,
                contentDescription = "搜索",
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(onClick = onSettings) {
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
private fun SearchBarView(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
) {
    val cs = MaterialTheme.colorScheme
    val tk = AppTheme.tokens
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(shape)
                .background(cs.surfaceContainerHigh)
                .border(1.dp, tk.hairline, shape)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            "搜索科目或日期",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    inner()
                },
            )
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "清除",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryList(
    sessions: List<TranslationSession>,
    today: String,
    weekAgo: String,
    onOpen: (Long) -> Unit,
    onLongPress: (TranslationSession) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val groups = remember(sessions, today, weekAgo) {
        val g0 = sessions.filter { it.date == today }
        val g1 = sessions.filter { it.date != today && it.date >= weekAgo }
        val g2 = sessions.filter { it.date < weekAgo }
        listOf("今天" to g0, "本周" to g1, "更早" to g2).filter { it.second.isNotEmpty() }
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 2.dp, bottom = 96.dp),
    ) {
        groups.forEach { (label, list) ->
            item(key = "g-$label") {
                Text(
                    label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.1.sp,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 0.dp, start = 2.dp, end = 2.dp),
                )
            }
            items(list, key = { it.id }) { s ->
                HistoryCard(
                    session = s,
                    onClick = { onOpen(s.id) },
                    onLongClick = { onLongPress(s) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryCard(
    session: TranslationSession,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val tk = AppTheme.tokens
    val shape = RoundedCornerShape(12.dp)
    val durSec = remember(session.id) { sessionDurationSec(session) }
    val hsub = remember(durSec, session.entries.size) {
        val n = "${session.entries.size} 句"
        if (durSec != null) "${fmtDur(durSec)} · $n" else n
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, shape)
            .clip(shape)
            .background(cs.surfaceContainerLow)
            .border(1.dp, tk.hairline, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .then(Modifier.gradientFill(RoundedCornerShape(12.dp), listOf(tk.gradSoftA, tk.gradSoftB)))
                .border(1.dp, cs.primary.copy(alpha = 0.22f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.MenuBook,
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${fmtMonthDay(session.date)} · $DEFAULT_SUBJECT",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    hsub,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        color = cs.onSurfaceVariant,
                        letterSpacing = 0.33.sp,
                        fontFeatureSettings = "tnum",
                    ),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SubjectChip(DEFAULT_SUBJECT, bg = cs.tertiaryContainer, fg = cs.onTertiaryContainer)
                SubjectChip("已转写", bg = cs.primaryContainer, fg = cs.onPrimaryContainer)
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SubjectChip(label: String, bg: Color, fg: Color) {
    Box(
        modifier = Modifier
            .height(22.dp)
            .clip(RoundedCornerShape(50))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.22.sp,
            color = fg,
            modifier = Modifier.padding(horizontal = 9.dp),
        )
    }
}

@Composable
private fun EmptyHistory(onNavigateToRecording: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tk = AppTheme.tokens
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .gradientFill(RoundedCornerShape(32.dp), listOf(tk.gradSoftA, tk.gradSoftB))
                    .border(1.dp, cs.primary.copy(alpha = 0.24f), RoundedCornerShape(32.dp))
                    .gradientRing(RoundedCornerShape(32.dp), 1.5.dp, listOf(tk.gradA, tk.gradB)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.HistoryEdu,
                    contentDescription = null,
                    tint = cs.primary,
                    modifier = Modifier.size(38.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "还没有会话记录",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "回到主界面开始第一次课堂翻译,\n结束后会自动归档到这里。",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(50))
                    .background(cs.secondaryContainer)
                    .border(1.dp, cs.primary.copy(alpha = 0.40f), RoundedCornerShape(50))
                    .clickable(onClick = onNavigateToRecording)
                    .padding(horizontal = 20.dp),
            ) {
                Icon(
                    Icons.Outlined.Mic,
                    contentDescription = null,
                    tint = cs.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "开始第一课",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = cs.onSecondaryContainer,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// SessionDetailScreen —— 返回 + 标题 + 分享/下载 + 播放条 + 双语卡流
// ---------------------------------------------------------------------------

@Composable
fun SessionDetailScreen(
    sessionId: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val tk = AppTheme.tokens
    val sessionManager = remember { SessionManager(context) }
    var session by remember { mutableStateOf<TranslationSession?>(null) }

    LaunchedEffect(sessionId) {
        session = sessionManager.loadSessions().find { it.id == sessionId }
    }

    val s = session
    Scaffold(containerColor = cs.surface) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(Modifier.matchParentSize().ambientGlow(tk))
            if (s == null) {
                Text(
                    "会话不存在",
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                return@Box
            }
            Column(Modifier.fillMaxSize()) {
                DetailAppBar(
                    title = "${fmtMonthDay(s.date)} $DEFAULT_SUBJECT",
                    onBack = onBack,
                    onShare = { shareSessionDocx(context, sessionManager, s) },
                    onDownload = if (s.audioPath.isNotBlank()) {
                        { exportRecording(context, s) }
                    } else null,
                )
                if (s.audioPath.isNotBlank()) {
                    PlayerRow(audioPath = s.audioPath)
                }
                PairList(session = s)
            }
        }
    }
}

@Composable
private fun DetailAppBar(
    title: String,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onDownload: (() -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回",
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            title,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = cs.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
        IconButton(onClick = onShare) {
            Icon(
                Icons.Outlined.IosShare,
                contentDescription = "分享",
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        if (onDownload != null) {
            IconButton(onClick = onDownload) {
                Icon(
                    Icons.Outlined.Download,
                    contentDescription = "下载",
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun PlayerRow(audioPath: String) {
    val cs = MaterialTheme.colorScheme
    val tk = AppTheme.tokens
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = player?.currentPosition?.toLong() ?: 0L
            durationMs = (player?.duration ?: 0).toLong()
            delay(500L)
        }
    }
    // 预加载时长：未播放时播放条也显示总时长（与设计稿静态态一致）
    LaunchedEffect(Unit) {
        try {
            val mp = MediaPlayer().apply {
                setDataSource(audioPath)
                prepare()
                seekTo(0)
            }
            mp.setOnCompletionListener {
                isPlaying = false
                positionMs = 0L
                mp.seekTo(0)
            }
            player = mp
            durationMs = mp.duration.toLong()
        } catch (e: Exception) {
            android.util.Log.e("Detail", "prepare failed", e)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            player?.release()
            player = null
        }
    }

    fun start() {
        val p = player ?: return
        try {
            if (p.currentPosition >= p.duration - 50) p.seekTo(0)
            p.start()
            isPlaying = true
        } catch (e: Exception) {
            android.util.Log.e("Detail", "start failed", e)
        }
    }


    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .shadow(2.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(cs.surfaceContainerLow)
                .border(1.dp, tk.hairline, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            // 播放（渐变主按钮）
            val playShape = CircleShape
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .shadow(4.dp, playShape, ambientColor = tk.gradA.copy(alpha = 0.30f), spotColor = tk.gradA.copy(alpha = 0.30f))
                    .gradientFill(playShape, listOf(tk.gradA, tk.gradB))
                    .clickable { start() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = "播放",
                    tint = cs.onPrimary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { player?.pause(); isPlaying = false }) {
                Icon(
                    Icons.Outlined.Pause,
                    contentDescription = "暂停",
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
            IconButton(onClick = {
                player?.stop(); player?.release(); player = null
                isPlaying = false; positionMs = 0L
            }) {
                Icon(
                    Icons.Outlined.Stop,
                    contentDescription = "停止",
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .pointerInput(Unit) {
                            detectTapGestures { o ->
                                val f = (o.x / size.width).coerceIn(0f, 1f)
                                val d = player?.duration ?: 0
                                if (d > 0) { player?.seekTo((f * d).roundToInt()); positionMs = (f * d).toLong() }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(cs.primary.copy(alpha = 0.18f))
                            .drawWithContent {
                                drawContent()
                                val cx = size.width * fraction
                                val cy = size.height / 2f
                                drawCircle(cs.primary.copy(alpha = 0.22f), radius = 7.5f.dp.toPx(), center = Offset(cx, cy), style = Stroke(3.dp.toPx()))
                                drawCircle(cs.primary, radius = 6.dp.toPx(), center = Offset(cx, cy))
                            },
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction)
                                .gradientFill(RoundedCornerShape(2.dp), listOf(tk.gradA, tk.gradB)),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        fmtMs(positionMs),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = cs.onSurface,
                            fontFeatureSettings = "tnum",
                        ),
                    )
                    Text(
                        fmtMs(durationMs),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            color = cs.onSurfaceVariant,
                            fontFeatureSettings = "tnum",
                        ),
                    )
                }
            }
        }
    }
}

private fun fmtMs(ms: Long): String {
    val total = (ms / 1000).toInt()
    return fmtDur(total)
}

@Composable
private fun PairList(session: TranslationSession) {
    val cs = MaterialTheme.colorScheme
    val tk = AppTheme.tokens
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
    ) {
        items(session.entries, key = { it.timestamp + it.chinese.hashCode() }) { e ->
            PairCard(
                ts = e.timestamp.take(5),
                zh = e.chinese,
                en = e.english,
                modifier = Modifier,
            )
        }
    }
}

@Composable
private fun PairCard(
    ts: String,
    zh: String,
    en: String,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val tk = AppTheme.tokens
    val shape = RoundedCornerShape(12.dp)

    val tagContent = InlineTextContent(
        Placeholder(
            width = 31.sp,
            height = 18.sp,
            placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
        ),
    ) {
        Box(
            modifier = Modifier
                .height(18.dp)
                .clip(RoundedCornerShape(50))
                .gradientFill(RoundedCornerShape(50), listOf(tk.gradSoftA, tk.gradSoftB))
                .border(1.dp, cs.primary.copy(alpha = 0.25f), RoundedCornerShape(50))
                .padding(horizontal = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "ZH",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
                color = cs.primary,
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(2.dp, shape)
            .clip(shape)
            .background(cs.surfaceContainerLow)
            .border(1.dp, tk.hairline, shape)
            .padding(horizontal = 13.dp, vertical = 11.dp),
    ) {
        Column {
            Text(
                ts,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.55.sp,
                    color = tk.ts,
                    fontFeatureSettings = "tnum",
                ),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = buildAnnotatedString {
                    append(zh)
                    append("  ")
                    appendInlineContent("zh-tag", "ZH")
                },
                inlineContent = mapOf("zh-tag" to tagContent),
                fontSize = 16.sp,
                lineHeight = 23.sp,
                fontWeight = FontWeight.Medium,
                color = cs.onSurface,
            )
            Spacer(Modifier.height(3.dp))
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(
                    Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(cs.primary.copy(alpha = 0.38f)),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    en,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 分享 / 导出（沿用既有实现）
// ---------------------------------------------------------------------------

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

private fun exportRecording(context: Context, session: TranslationSession) {
    try {
        val audioFile = java.io.File(session.audioPath)
        if (!audioFile.exists()) return
        val exportDir = java.io.File(context.getExternalFilesDir(null), "exports"); exportDir.mkdirs()
        val exportFile = java.io.File(exportDir, "recording_${session.date}.wav")
        audioFile.copyTo(exportFile, overwrite = true)
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", exportFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"; putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "导出录音"))
    } catch (e: Exception) { android.util.Log.e("History", "Export recording failed", e) }
}
