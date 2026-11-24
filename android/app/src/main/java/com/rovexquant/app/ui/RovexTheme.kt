package com.rovexquant.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF0A1120)
val Panel = Color(0xFF151D2E)
val PanelRaised = Color(0xFF1B2940)
val Line = Color(0xFF25334A)
val Muted = Color(0xFF8C9AB0)
val Mint = Color(0xFF00E676)
val Cyan = Color(0xFF71C9F4)
val Coral = Color(0xFFFF5252)
val Gold = Color(0xFFF1C878)

private val RovexColors = darkColorScheme(
    primary = Mint, secondary = Cyan, tertiary = Gold, background = Ink,
    surface = Panel, onPrimary = Ink, onBackground = Color(0xFFEAF0FA),
    onSurface = Color(0xFFEAF0FA)
)

@Composable
fun RovexTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RovexColors, content = content)
}
