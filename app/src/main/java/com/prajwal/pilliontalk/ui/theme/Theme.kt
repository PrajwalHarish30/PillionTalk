package com.prajwal.pilliontalk.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/**
 * Pillion Talk's look: pure mono, following the phone's light/dark setting.
 *
 * The colour rules (stick to these and the screen can't get noisy):
 * 1. Everything is neutral grey: backgrounds, cards, buttons, avatars.
 * 2. One accent, the "ink" colour (white in dark mode, black in light), for live things only:
 *    the speaking ring, the status line, the volume slider. Boost is a fainter tint of it.
 * 3. Red appears in exactly one place: the End button. Red always means "this ends the ride".
 *
 * How the Material roles are used:
 *   background        page
 *   surface           cards and tiles
 *   surfaceVariant    buttons and controls
 *   primary           the ink / accent
 *   onSurfaceVariant  secondary text
 *   error             the End button
 */
private val EndRed = Color(0xFFE5484D)

private val DarkColors = darkColorScheme(
    background = Color(0xFF111111),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF1C1C1C),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF2B2B2B),
    onSurfaceVariant = Color(0xFF8C8C8C),
    surfaceContainer = Color(0xFF1C1C1C),
    surfaceContainerHigh = Color(0xFF242424),
    surfaceContainerHighest = Color(0xFF2B2B2B),
    surfaceContainerLow = Color(0xFF171717),
    primary = Color(0xFFF2F2F2),
    onPrimary = Color(0xFF111111),
    secondaryContainer = Color(0xFF2B2B2B),
    onSecondaryContainer = Color(0xFFF2F2F2),
    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF2A2A2A),
    error = EndRed,
    onError = Color.White,
)

private val LightColors = lightColorScheme(
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFF2F2F2),
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFE4E4E4),
    onSurfaceVariant = Color(0xFF6B6B6B),
    surfaceContainer = Color(0xFFF2F2F2),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFE4E4E4),
    surfaceContainerLow = Color(0xFFF7F7F7),
    primary = Color(0xFF111111),
    onPrimary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4E4E4),
    onSecondaryContainer = Color(0xFF111111),
    outline = Color(0xFFD0D0D0),
    outlineVariant = Color(0xFFE6E6E6),
    error = EndRed,
    onError = Color.White,
)

// Material's default type scale, with headings a touch bolder.
private val baseType = Typography()
private val type = baseType.copy(
    headlineMedium = baseType.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = baseType.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = baseType.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = baseType.labelLarge.copy(fontWeight = FontWeight.Medium),
)

/** Flutter equivalent: MaterialApp(theme: …, darkTheme: …). Wrap the whole app in this. */
@Composable
fun PillionTalkTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = type, content = content)
}

/** Handy for code outside composables (e.g. picking the window background). */
fun pillionColors(dark: Boolean): ColorScheme = if (dark) DarkColors else LightColors
