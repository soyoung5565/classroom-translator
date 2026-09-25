package com.classroom.translator.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Design tokens — "B 方向 · 活力渐变" dual-theme final (2026-09-25).
 *
 * Converted 1:1 from the design source CSS (oklch -> sRGB) and verified by
 * pixel-sampling the design renders: card #0C0B12, gradients #8B5CF6->#C084FC
 * (dark) / #7C3AED->#9334EA (light), body text #E8E6F1 — all match.
 *
 * - Light: scaffold pure white 0xFFFFFFFF
 * - Dark : scaffold pure black 0xFF000000 (OLED power saving)
 * - Theme follows the system via isSystemInDarkTheme() (see Theme.kt).
 *
 * Screens must consume MaterialTheme.colorScheme.* or LocalAppTokens —
 * never hardcode hex values in screen files.
 */

// Brand gradient endpoints — used by the three gradient focus points
// (status chip / latest-card ring / FAB) plus progress fill and brandmark.
internal val LightGradA = Color(0xFF7C3AED)
internal val LightGradB = Color(0xFF9334EA)
internal val DarkGradA  = Color(0xFF8B5CF6)
internal val DarkGradB  = Color(0xFFC084FC)

// Legacy brand seed reference.
internal val BrandSeed = Color(0xFF8B5CF6)

// ---------- LIGHT scheme ----------
internal val LightPrimary              = Color(0xFF702ADE)
internal val LightOnPrimary            = Color(0xFFFFFFFF)
internal val LightPrimaryContainer     = Color(0xFFE0D8FF)
internal val LightOnPrimaryContainer   = Color(0xFF3B1D75)
internal val LightSecondary            = Color(0xFF716A8E)
internal val LightOnSecondary          = Color(0xFFFAF9FD)
internal val LightSecondaryContainer   = Color(0xFFE6E3FA)
internal val LightOnSecondaryContainer = Color(0xFF38344B)
internal val LightTertiary             = Color(0xFF9334EA)
internal val LightOnTertiary           = Color(0xFFFFFFFF)
internal val LightTertiaryContainer    = Color(0xFFEADAFF)
internal val LightOnTertiaryContainer  = Color(0xFF4B2574)
internal val LightError                = Color(0xFFCD171E)
internal val LightOnError              = Color(0xFFFFFFFF)
internal val LightErrorContainer       = Color(0xFFFFD9D3)
internal val LightOnErrorContainer     = Color(0xFF861213)
internal val LightBackground           = Color(0xFFFFFFFF)
internal val LightOnBackground         = Color(0xFF1F1A2F)
internal val LightSurface              = Color(0xFFFFFFFF)
internal val LightOnSurface            = Color(0xFF1F1A2F)
internal val LightOnSurfaceVariant     = Color(0xFF565266)
internal val LightSurfaceContainerLowest  = Color(0xFFFFFFFF)
internal val LightSurfaceContainerLow     = Color(0xFFF6F5FA)
internal val LightSurfaceContainer        = Color(0xFFF0EFF6)
internal val LightSurfaceContainerHigh    = Color(0xFFEEECF5)
internal val LightSurfaceContainerHighest = Color(0xFFE8E7F2)
internal val LightOutline              = Color(0xFF75708A)
internal val LightOutlineVariant       = Color(0xFFE6E5E8)
internal val LightInverseSurface       = Color(0xFF2E2F36)
internal val LightInverseOnSurface     = Color(0xFFF1EFF5)
internal val LightInversePrimary       = Color(0xFFC084FC)
internal val LightTs                   = Color(0xFF4F2A97)

// ---------- DARK scheme ----------
internal val DarkPrimary              = Color(0xFFA580FF)
internal val DarkOnPrimary            = Color(0xFF1C0A3D)
internal val DarkPrimaryContainer     = Color(0xFF56369E)
internal val DarkOnPrimaryContainer   = Color(0xFFE8E2FF)
internal val DarkSecondary            = Color(0xFF756E98)
internal val DarkOnSecondary          = Color(0xFFE8E6F4)
internal val DarkSecondaryContainer   = Color(0xFF36314B)
internal val DarkOnSecondaryContainer = Color(0xFFE5E1F7)
internal val DarkTertiary             = Color(0xFFC084FC)
internal val DarkOnTertiary           = Color(0xFF25103B)
internal val DarkTertiaryContainer    = Color(0xFF513A70)
internal val DarkOnTertiaryContainer  = Color(0xFFE9DFFB)
internal val DarkError                = Color(0xFFFA6863)
internal val DarkOnError              = Color(0xFF5C1717)
internal val DarkErrorContainer       = Color(0xFF7C2524)
internal val DarkOnErrorContainer     = Color(0xFFFEDBD7)
internal val DarkBackground           = Color(0xFF000000)
internal val DarkOnBackground         = Color(0xFFE8E6F1)
internal val DarkSurface              = Color(0xFF000000)
internal val DarkOnSurface            = Color(0xFFE8E6F1)
internal val DarkOnSurfaceVariant     = Color(0xFFABA8BA)
internal val DarkSurfaceContainerLowest  = Color(0xFF000000)
internal val DarkSurfaceContainerLow     = Color(0xFF0C0B12)
internal val DarkSurfaceContainer        = Color(0xFF15141C)
internal val DarkSurfaceContainerHigh    = Color(0xFF1E1D26)
internal val DarkSurfaceContainerHighest = Color(0xFF292732)
internal val DarkOutline              = Color(0xFF7B778E)
internal val DarkOutlineVariant       = Color(0xFF3A3844)
internal val DarkInverseSurface       = Color(0xFFE8E6F1)
internal val DarkInverseOnSurface     = Color(0xFF2E2C36)
internal val DarkInversePrimary       = Color(0xFF8B5CF6)
internal val DarkTs                   = Color(0xFFA692EF)

// Compatibility aliases - legacy screen code references these names.
@Deprecated("Use MaterialTheme.colorScheme.primary", ReplaceWith("MaterialTheme.colorScheme.primary"))
val Blue500 = DarkPrimary
@Deprecated("Use MaterialTheme.colorScheme.primary", ReplaceWith("MaterialTheme.colorScheme.primary"))
val Green500 = DarkPrimary
@Deprecated("Use MaterialTheme.colorScheme.error", ReplaceWith("MaterialTheme.colorScheme.error"))
val Red500 = DarkError
@Deprecated("Use MaterialTheme.colorScheme.tertiary", ReplaceWith("MaterialTheme.colorScheme.tertiary"))
val Orange500 = DarkTertiary
