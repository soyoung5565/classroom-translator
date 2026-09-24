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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Material 3 theme entry point.
 *
 * Design tokens follow the official M3 specification (m3.material.io) and the
 * Material 3 skill (hamen/material-3-skill). Three layers:
 *
 *   1. Color scheme
 *      - Android 12+ (API 31+): dynamic color from system wallpaper (Material You)
 *      - Below API 31 or if dynamicColor=false: hand-tuned tonal palette
 *        derived from BrandSeed (4A6FE5 teaching-blue) via ColorScheme.fromSeed.
 *
 *   2. Typography
 *      - Full M3 type scale (15 base + 15 emphasized styles). Uses the system
 *        default font family; per M3 spec Roboto/Roboto Flex IS the correct
 *        default on Android (frontend-design skill guidance to avoid Roboto
 *        does NOT apply when implementing MD3).
 *      - Numeric tokens (labelSmall for timestamps/latency) get FontFamily.Monospace.
 *
 *   3. Shape
 *      - 5 corner radii from the MD3 shape token scale:
 *        extraSmall=4dp (chips, snackbars), small=8dp (text fields, menus),
 *        medium=12dp (cards), large=16dp (FABs, drawer),
 *        extraLarge=28dp (dialogs, sheets).
 *
 * To disable dynamic color (e.g. for branding consistency), pass dynamicColor=false.
 */
@Composable
fun TranslatorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 2026-09-24 redesign: dynamicColor disabled to enforce BrandSeed violet across all Android versions.
    // Material You would otherwise hijack the palette with the system wallpaper's tones, hiding the design
    // intent (and making A/B comparisons vs old build impossible).
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

// Full Material 3 type scale.
// Sizes/weights/line-heights follow m3.material.io/typescale (Roboto Flex defaults).
private val AppTypography = Typography(
    // Display - hero text, large numbers
    displayLarge   = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,     fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.25).sp),
    displayMedium  = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,     fontSize = 45.sp, lineHeight = 52.sp, letterSpacing = 0.sp),
    displaySmall   = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,     fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = 0.sp),

    // Headline - section headers
    headlineLarge  = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,     fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = 0.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,     fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = 0.sp),
    headlineSmall  = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,     fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.sp),

    // Title - smaller headers, card titles
    titleLarge     = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,   fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp),
    titleMedium    = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,   fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    titleSmall     = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,     fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),

    // Body - paragraph text, descriptions
    bodyLarge      = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,     fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp),
    bodyMedium     = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,     fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
    bodySmall      = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,     fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),

    // Label - buttons, chips, captions, timestamps
    labelLarge     = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,     fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium    = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,     fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    // Timestamps, latency values, model names — monospace per Material 3 guidance for numeric/code-like data
    labelSmall     = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal,   fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
)

// Material 3 shape token scale (5 corner-radius tiers + full pill).
private val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    small      = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium     = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    large      = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)
