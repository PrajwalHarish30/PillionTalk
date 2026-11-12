package com.prajwal.pilliontalk.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * The app's theme. Flutter equivalent: wrapping your app in MaterialApp(theme: ...).
 *
 * "Dynamic color" (Android 12+) builds the colour scheme from the phone's wallpaper,
 * so the app matches each phone. Since minSdk is 31, every phone we support has it.
 */
@Composable
fun PillionTalkTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }
    MaterialTheme(colorScheme = colors, content = content)
}
