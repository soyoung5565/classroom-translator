package com.classroom.translator.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Material 3 tonal palette fallback colors.
 *
 * Per Material 3 design system spec (m3.material.io):
 * - Dark scheme uses tonal palette tones 0-100 where lower tone = darker surface.
 * - Light scheme uses tonal palette tones 0-100 where higher tone = lighter surface.
 * - These hex values were generated via the official M3 ColorScheme.fromSeed
 *   algorithm with seedColor = Color(0xFF4A6FE5) (a calm teaching-blue).
 *
 * When dynamicColor is enabled on Android 12+, the dynamicLight/DarkColorScheme()
 * functions in Theme.kt take over and these are unused. They exist so:
 *   1) Pre-Android-12 devices still get a designed dark mode
 *   2) Users who opt out of dynamic color get a coherent brand palette
 *
 * Per the Material 3 skill (hamen/material-3-skill):
 *   - primary / on-primary - High-emphasis fills, text, icons against surface
 *   - secondary / on-secondary - Less prominent accents (filter chips, tonal buttons)
 *   - tertiary / on-tertiary - Contrasting accent (badges, complementary containers)
 *   - surface-container-lowest/low/default/high/highest - 5-level hierarchy
 *   - outline / outline-variant - Important vs decorative boundaries
 *
 * Do not introduce new hardcoded hex values in Screen files. Use
 * MaterialTheme.colorScheme.{role} or MaterialTheme.shapes.{token} instead.
 */

// Brand seed used by ColorScheme.fromSeed in Theme.kt for non-dynamic fallback.
internal val BrandSeed = Color(0xFF4A6FE5) // calm teaching-blue

// Dark scheme colors (Material 3 tonal palette tones 0-100)
internal val DarkPrimary             = Color(0xFFB6C5FF)
internal val DarkOnPrimary           = Color(0xFF1F2D78)
internal val DarkPrimaryContainer    = Color(0xFF39468F)
internal val DarkOnPrimaryContainer  = Color(0xFFDEE1FF)

internal val DarkSecondary           = Color(0xFFBEC6DC)
internal val DarkOnSecondary         = Color(0xFF293041)
internal val DarkSecondaryContainer  = Color(0xFF3F4759)
internal val DarkOnSecondaryContainer= Color(0xFFDAE2F9)

internal val DarkTertiary            = Color(0xFFDDBDE2)
internal val DarkOnTertiary          = Color(0xFF3F2843)
internal val DarkTertiaryContainer   = Color(0xFF573E5B)
internal val DarkOnTertiaryContainer = Color(0xFFFAD7FC)

internal val DarkError               = Color(0xFFFFB4AB)
internal val DarkOnError             = Color(0xFF690005)
internal val DarkErrorContainer      = Color(0xFF93000A)
internal val DarkOnErrorContainer    = Color(0xFFFFDAD6)

internal val DarkBackground          = Color(0xFF121318)
internal val DarkOnBackground        = Color(0xFFE3E2E9)
internal val DarkSurface             = Color(0xFF121318)
internal val DarkOnSurface           = Color(0xFFE3E2E9)
internal val DarkOnSurfaceVariant    = Color(0xFFC5C6D7)

internal val DarkSurfaceContainerLowest  = Color(0xFF0C0E13)
internal val DarkSurfaceContainerLow     = Color(0xFF1A1B21)
internal val DarkSurfaceContainer        = Color(0xFF1E2025)
internal val DarkSurfaceContainerHigh    = Color(0xFF292A30)
internal val DarkSurfaceContainerHighest = Color(0xFF34353C)

internal val DarkOutline             = Color(0xFF8F909A)
internal val DarkOutlineVariant      = Color(0xFF45464F)
internal val DarkInverseSurface      = Color(0xFFE3E2E9)
internal val DarkInverseOnSurface    = Color(0xFF2E2F36)
internal val DarkInversePrimary      = Color(0xFF4A6FE5)

// Light scheme colors (for future light mode support)
internal val LightPrimary             = Color(0xFF2B4BC7)
internal val LightOnPrimary           = Color(0xFFFFFFFF)
internal val LightPrimaryContainer    = Color(0xFFDEE1FF)
internal val LightOnPrimaryContainer  = Color(0xFF001454)

internal val LightSecondary           = Color(0xFF595D72)
internal val LightOnSecondary         = Color(0xFFFFFFFF)
internal val LightSecondaryContainer  = Color(0xFFDEE2F9)
internal val LightOnSecondaryContainer= Color(0xFF161B2C)

internal val LightTertiary            = Color(0xFF735471)
internal val LightOnTertiary          = Color(0xFFFFFFFF)
internal val LightTertiaryContainer   = Color(0xFFFAD7FC)
internal val LightOnTertiaryContainer = Color(0xFF2A1130)

internal val LightError               = Color(0xFFBA1A1A)
internal val LightOnError             = Color(0xFFFFFFFF)
internal val LightErrorContainer      = Color(0xFFFFDAD6)
internal val LightOnErrorContainer    = Color(0xFF410002)

internal val LightBackground          = Color(0xFFFEFBFF)
internal val LightOnBackground        = Color(0xFF1B1B21)
internal val LightSurface             = Color(0xFFFEFBFF)
internal val LightOnSurface           = Color(0xFF1B1B21)
internal val LightOnSurfaceVariant    = Color(0xFF46464F)

internal val LightSurfaceContainerLowest  = Color(0xFFFFFFFF)
internal val LightSurfaceContainerLow     = Color(0xFFF6F4FA)
internal val LightSurfaceContainer        = Color(0xFFF1EFF5)
internal val LightSurfaceContainerHigh    = Color(0xFFEBE9EF)
internal val LightSurfaceContainerHighest = Color(0xFFE5E3EA)

internal val LightOutline             = Color(0xFF767680)
internal val LightOutlineVariant      = Color(0xFFC6C5D0)
internal val LightInverseSurface      = Color(0xFF2E2F36)
internal val LightInverseOnSurface    = Color(0xFFF1EFF5)
internal val LightInversePrimary      = Color(0xFFB6C5FF)

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
