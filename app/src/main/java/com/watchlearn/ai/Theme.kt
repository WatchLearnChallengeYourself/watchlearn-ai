package com.watchlearn.ai

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val WatchLearnDark = darkColorScheme(
    primary = Color(0xFF22D3EE),
    onPrimary = Color(0xFF001F26),
    secondary = Color(0xFFF59E0B),
    background = Color(0xFF0B1020),
    surface = Color(0xFF111A33),
    onBackground = Color(0xFFF8FAFC),
    onSurface = Color(0xFFF8FAFC)
)

private val WatchLearnLight = lightColorScheme(
    primary = Color(0xFF075985),
    onPrimary = Color.White,
    secondary = Color(0xFFB45309),
    background = Color(0xFFF8FAFC),
    surface = Color.White,
    onBackground = Color(0xFF0B1020),
    onSurface = Color(0xFF0B1020)
)

@Composable
fun WatchLearnTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) WatchLearnDark else WatchLearnLight,
        content = content
    )
}
