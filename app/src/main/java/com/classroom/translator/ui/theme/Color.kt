package com.classroom.translator.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Material 3 tonal palette colors - 2026-09-25 B-direction redesign (dual theme).
 *
 * User-confirmed requirements:
 * - Light mode scaffold background = pure white 0xFFFFFFFF
 * - Dark mode scaffold background = pure black 0xFF000000 (OLED power saving)
 * - App follows system dark/light auto-switch via isSystemInDarkTheme() (Theme.kt)
 * - All colors derived from brand seed 0xFF8B5CF6 (oklch 0.606 0.25 292.72)
 * - Gradient accent endpoints: dark mode uses bright tones (tone 80),
 *   light mode uses deep tones (tone 40) for contrast.
 *
 * Per Material 3 spec (m3.material.io):
 * - Cards keep their surfaceContainer fill on both pure backgrounds; hierarchy comes
 *   from the 5-level container scale (never hardcode these in Screen files).
 *
 * Do not introduce new hardcoded hex values in Screen files. Use
 * MaterialTheme.colorScheme.{role} or MaterialTheme.shapes.{token} instead.
 */

// Brand seed used by ColorScheme.fromSeed in Theme.kt for non-dynamic fallback.
internal val BrandSeed = Color(0xFF8B5CF6)

// Dark scheme colors (Material 3 tonal palette tones 0-100, surface pinned to pure black)
internal val DarkPrimary             = Color(0xFFD7BBFB) // tone 80 - bright violet, gradient endpoint (dark)
internal val DarkOnPrimary           = Color(0xFF381E72) // tone 10
internal val DarkPrimaryContainer    = Color(0xFF4F378B) // tone 30
internal val DarkOnPrimaryContainer  = Color(0xFFEADDFF) // tone 90

internal val DarkSecondary           = Color(0xFFBEC6DC)
internal val DarkOnSecondary         = Color(0xFF293041)
internal val DarkSecondaryContainer  = Color(0xFF3F4759)
internal val DarkOnSecondaryContainer= Color(0xFFDAE2F9)

internal val DarkTertiary            = Color(0xFFDDBDE2) // tone 80 - bright plum, gradient endpoint (dark)
internal val DarkOnTertiary          = Color(0xFF3F2843)
internal val DarkTertiaryContainer   = Color(0xFF573E5B)
internal val DarkOnTertiaryContainer = Color(0xFFFAD7FC)

internal val DarkError               = Color(0xFFFFB4AB)
internal val DarkOnError             = Color(0xFF690005)
internal val DarkErrorContainer      = Color(0xFF93000A)
internal val DarkOnErrorContainer    = Color(0xFFFFDAD6)

internal val DarkBackground          = Color(0xFF000000) // pure black - user requirement (OLED)
internal val DarkOnBackground        = Color(0xFFE3E2E9)
internal val DarkSurface             = Color(0xFF000000) // pure black - user requirement (OLED)
internal val DarkOnSurface           = Color(0xFFE3E2E9)
internal val DarkOnSurfaceVariant    = Color(0xFFC5C6D7)

// Container scale on pure black: subtle steps, never muddy grey wash
internal val DarkSurfaceContainerLowest  = Color(0xFF0A0A0C)
internal val DarkSurfaceContainerLow     = Color(0xFF131316)
internal val DarkSurfaceContainer        = Color(0xFF17171A)
internal val DarkSurfaceContainerHigh    = Color(0xFF1E1E22)
internal val DarkSurfaceContainerHighest = Color(0xFF26262B)

internal val DarkOutline             = Color(0xFF8F909A)
internal val DarkOutlineVariant      = Color(0xFF43444B) // hairline borders on pure black
internal val DarkInverseSurface      = Color(0xFFE3E2E9)
internal val DarkInverseOnSurface    = Color(0xFF2E2F36)
internal val DarkInversePrimary      = Color(0xFF8B5CF6) // BrandSeed violet for inverse

// Light scheme colors (surface pinned to pure white)
internal val LightPrimary             = Color(0xFF6750A4) // tone 40 - deep violet, gradient endpoint (light)
internal val LightOnPrimary           = Color(0xFFFFFFFF)
internal val LightPrimaryContainer    = Color(0xFFEADDFF) // tone 90
internal val LightOnPrimaryContainer  = Color(0xFF21005D) // tone 10

internal val LightSecondary           = Color(0xFF595D72)
internal val LightOnSecondary         = Color(0xFFFFFFFF)
internal val LightSecondaryContainer  = Color(0xFFDEE2F9)
internal val LightOnSecondaryContainer= Color(0xFF161B2C)

internal val LightTertiary            = Color(0xFF735471) // tone 40 - deep plum, gradient endpoint (light)
internal val LightOnTertiary          = Color(0xFFFFFFFF)
internal val LightTertiaryContainer   = Color(0xFFFAD7FC)
internal val LightOnTertiaryContainer = Color(0xFF2A1130)

internal val LightError               = Color(0xFFBA1A1A)
internal val LightOnError             = Color(0xFFFFFFFF)
internal val LightErrorContainer      = Color(0xFFFFDAD6)
internal val LightOnErrorContainer    = Color(0xFF410002)

internal val LightBackground          = Color(0xFFFFFFFF) // pure white - user requirement
internal val LightOnBackground        = Color(0xFF1B1B21)
internal val LightSurface             = Color(0xFFFFFFFF) // pure white - user requirement
internal val LightOnSurface           = Color(0xFF1B1B21)
internal val LightOnSurfaceVariant    = Color(0xFF46464F)

internal val LightSurfaceContainerLowest  = Color(0xFFF9F8FB)
internal val LightSurfaceContainerLow     = Color(0xFFF3F1F7)
internal val LightSurfaceContainer        = Color(0xFFEEECF3)
internal val LightSurfaceContainerHigh    = Color(0xFFE8E6EE)
internal val LightSurfaceContainerHighest = Color(0xFFE2E0E8)

internal val LightOutline             = Color(0xFF767680)
internal val LightOutlineVariant      = Color(0xFFC6C5D0)
internal val LightInverseSurface      = Color(0xFF2E2F36)
internal val LightInverseOnSurface    = Color(0xFFF1EFF5)
internal val LightInversePrimary      = Color(0xFFD0BCFF) // tone 80

// Compatibility aliases - existing Screen code references these names.
// Kept so legacy code paths still compile. New code must use
// MaterialTheme.colorScheme.{role} directly.
@Deprecated("Use MaterialTheme.colorScheme.primary", ReplaceWith("MaterialTheme.colorScheme.primary"))
val Blue500 = DarkPrimary
@Deprecated("Use MaterialTheme.colorScheme.primary", ReplaceWith("MaterialTheme.colorScheme.primary"))
val Green500 = DarkPrimary
@Deprecated("Use MaterialTheme.colorScheme.error", ReplaceWith("MaterialTheme.colorScheme.error"))
val Red500 = DarkError
@Deprecated("Use MaterialTheme.colorScheme.tertiary", ReplaceWith("MaterialTheme.colorScheme.tertiary"))
val Orange500 = DarkTertiary
