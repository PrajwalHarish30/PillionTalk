package com.prajwal.pilliontalk.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/**
 * Pillion Talk's look: a dark "ride mode" with a warm amber accent (like an indicator light).
 *
 * Why always dark, instead of following the system or the wallpaper (dynamic colour)?
 * - You glance at it on a bike, often through a visor and in sunlight or at night:
 *   high contrast on a dark background is easiest to read.
 * - Dark pixels cost almost no battery on OLED screens.
 * - A fixed palette gives the app its own identity on every phone.
 */
object PillionColors {
    val Amber = Color(0xFFFFB300)
    val OnAmber = Color(0xFF221800)
    val Background = Color(0xFF0E1318)
    val Surface = Color(0xFF151C23)
    val SurfaceRaised = Color(0xFF1E2731)
    val Outline = Color(0xFF2E3A46)
    val TextPrimary = Color(0xFFECEFF3)
    val TextSecondary = Color(0xFF9AA7B4)

    // Status colours (not part of Material's scheme, so we keep them here).
    val Good = Color(0xFF4ADE80)
    val Warn = Color(0xFFFFA94D)
    val Bad = Color(0xFFFF6B6B)
}

private val colors = darkColorScheme(
    primary = PillionColors.Amber,
    onPrimary = PillionColors.OnAmber,
    background = PillionColors.Background,
    onBackground = PillionColors.TextPrimary,
    surface = PillionColors.Surface,
    onSurface = PillionColors.TextPrimary,
    surfaceVariant = PillionColors.SurfaceRaised,
    onSurfaceVariant = PillionColors.TextSecondary,
    surfaceContainer = PillionColors.Surface,
    surfaceContainerHigh = PillionColors.SurfaceRaised,
    outline = PillionColors.Outline,
    outlineVariant = PillionColors.Outline,
    error = PillionColors.Bad,
    onError = Color(0xFF2B0000),
)

// Start from Material's default type scale and make headings a little bolder.
private val baseType = Typography()
private val type = baseType.copy(
    displaySmall = baseType.displaySmall.copy(fontWeight = FontWeight.Bold),
    headlineMedium = baseType.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = baseType.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = baseType.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = baseType.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = baseType.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

/** Flutter equivalent: MaterialApp(theme: ThemeData(...)). Wrap the whole app in this. */
@Composable
fun PillionTalkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = type, content = content)
}
