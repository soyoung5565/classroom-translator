package com.classroom.translator.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "B 方向 · 活力渐变" theme entry point — dual-theme final (2026-09-25).
 *
 * Layer 1 — ColorScheme: hand-tuned from the design tokens in Color.kt
 * (converted from the design source oklch values, verified against renders).
 * Dynamic color stays OFF so the brand violet survives on every device.
 *
 * Layer 2 — AppTokens (CompositionLocal): everything M3's ColorScheme cannot
 * express — brand gradients, soft-gradient alpha pairs, hairlines and the
 * oklab-mixed timestamp color. Screens read it via `AppTheme.tokens`.
 *
 * Layer 3 — Typography / Shapes: the design's type scale equals the M3
 * default scale (11/12/14/16/22/24), so we keep M3 defaults; labelSmall is
 * non-monospace with tabular figures (design uses `font-feature-settings: tnum`).
 */
data class AppTokens(
    val gradA: Color,
    val gradB: Color,
    val gradSoftA: Color,
    val gradSoftB: Color,
    val hairline: Color,
    val ts: Color,
    val latestEnA: Color,
    val glowTop: Color,
    val glowBottom: Color,
    val ambient: Boolean,
)

internal val LightAppTokens = AppTokens(
    gradA = LightGradA,
    gradB = LightGradB,
    gradSoftA = LightGradA.copy(alpha = 0.10f),
    gradSoftB = LightGradB.copy(alpha = 0.10f),
    hairline = LightOnSurface.copy(alpha = 0.10f),
    ts = LightTs,
    latestEnA = Color(0xFF7731BD),
    glowTop = Color.Transparent,
    glowBottom = Color.Transparent,
    ambient = false,
)

internal val DarkAppTokens = AppTokens(
    gradA = DarkGradA,
    gradB = DarkGradB,
    gradSoftA = DarkGradA.copy(alpha = 0.22f),
    gradSoftB = DarkGradB.copy(alpha = 0.22f),
    hairline = Color.White.copy(alpha = 0.08f),
    ts = DarkTs,
    latestEnA = Color(0xFFC99BFB),
    glowTop = DarkGradA.copy(alpha = 0.20f),
    glowBottom = DarkGradB.copy(alpha = 0.12f),
    ambient = true,
)

val LocalAppTokens = staticCompositionLocalOf { LightAppTokens }

/** Convenience accessor: `AppTheme.tokens` inside composables. */
object AppTheme {
    val tokens: AppTokens
        @Composable get() = LocalAppTokens.current
}

@Composable
fun TranslatorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color intentionally disabled: Material You would otherwise
    // hijack the design's violet palette with wallpaper tones.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme(
            primary = DarkPrimary,
            onPrimary = DarkOnPrimary,
            primaryContainer = DarkPrimaryContainer,
            onPrimaryContainer = DarkOnPrimaryContainer,
            secondary = DarkSecondary,
            onSecondary = DarkOnSecondary,
            secondaryContainer = DarkSecondaryContainer,
            onSecondaryContainer = DarkOnSecondaryContainer,
            tertiary = DarkTertiary,
            onTertiary = DarkOnTertiary,
            tertiaryContainer = DarkTertiaryContainer,
            onTertiaryContainer = DarkOnTertiaryContainer,
            error = DarkError,
            onError = DarkOnError,
            errorContainer = DarkErrorContainer,
            onErrorContainer = DarkOnErrorContainer,
            background = DarkBackground,
            onBackground = DarkOnBackground,
            surface = DarkSurface,
            onSurface = DarkOnSurface,
            onSurfaceVariant = DarkOnSurfaceVariant,
            surfaceContainerLowest = DarkSurfaceContainerLowest,
            surfaceContainerLow = DarkSurfaceContainerLow,
            surfaceContainer = DarkSurfaceContainer,
            surfaceContainerHigh = DarkSurfaceContainerHigh,
            surfaceContainerHighest = DarkSurfaceContainerHighest,
            outline = DarkOutline,
            outlineVariant = DarkOutlineVariant,
            inverseSurface = DarkInverseSurface,
            inverseOnSurface = DarkInverseOnSurface,
            inversePrimary = DarkInversePrimary,
        )
        else -> lightColorScheme(
            primary = LightPrimary,
            onPrimary = LightOnPrimary,
            primaryContainer = LightPrimaryContainer,
            onPrimaryContainer = LightOnPrimaryContainer,
            secondary = LightSecondary,
            onSecondary = LightOnSecondary,
            secondaryContainer = LightSecondaryContainer,
            onSecondaryContainer = LightOnSecondaryContainer,
            tertiary = LightTertiary,
            onTertiary = LightOnTertiary,
            tertiaryContainer = LightTertiaryContainer,
            onTertiaryContainer = LightOnTertiaryContainer,
            error = LightError,
            onError = LightOnError,
            errorContainer = LightErrorContainer,
            onErrorContainer = LightOnErrorContainer,
            background = LightBackground,
            onBackground = LightOnBackground,
            surface = LightSurface,
            onSurface = LightOnSurface,
            onSurfaceVariant = LightOnSurfaceVariant,
            surfaceContainerLowest = LightSurfaceContainerLowest,
            surfaceContainerLow = LightSurfaceContainerLow,
            surfaceContainer = LightSurfaceContainer,
            surfaceContainerHigh = LightSurfaceContainerHigh,
            surfaceContainerHighest = LightSurfaceContainerHighest,
            outline = LightOutline,
            outlineVariant = LightOutlineVariant,
            inverseSurface = LightInverseSurface,
            inverseOnSurface = LightInverseOnSurface,
            inversePrimary = LightInversePrimary,
        )
    }

    CompositionLocalProvider(
        LocalAppTokens provides (if (darkTheme) DarkAppTokens else LightAppTokens),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

// Full Material 3 type scale. The design's own scale (11/12/14/16/22/24sp)
// equals the M3 defaults, so values stay stock; labelSmall is Default font
// with tabular figures (design timestamps use font-feature-settings: tnum).
private val AppTypography = Typography(
    displayLarge   = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,     fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.25).sp),
    displayMedium  = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,     fontSize = 45.sp, lineHeight = 52.sp, letterSpacing = 0.sp),
    displaySmall   = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,     fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = 0.sp),

    headlineLarge  = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,     fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = 0.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,     fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = 0.sp),
    headlineSmall  = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,     fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.sp),

    titleLarge     = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,   fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp),
    titleMedium    = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,   fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    titleSmall     = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,     fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),

    bodyLarge      = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,     fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.25.sp),
    bodyMedium     = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,     fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
    bodySmall      = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,     fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),

    labelLarge     = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,     fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium    = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,     fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall     = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,     fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp, fontFeatureSettings = "tnum"),
)

// Material 3 shape token scale (5 corner-radius tiers).
private val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    small      = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium     = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    large      = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)
