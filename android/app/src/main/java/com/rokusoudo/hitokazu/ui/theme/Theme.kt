package com.rokusoudo.hitokazu.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Primary = Color(0xFF1976D2)
private val Secondary = Color(0xFFFF6F00)
private val Background = Color(0xFFF5F5F5)

// 残り時間わずかの警告色（DESIGN.md の --color-warning に対応）。
// CountdownTimer.kt から参照し、テーマ外への直書きを解消する（Issue #37）。
val Warning = Color(0xFFFFA000)

private val LightColors = lightColorScheme(
    primary = Primary,
    secondary = Secondary,
    background = Background,
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
)

@Composable
fun HitokazuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content,
    )
}
