package com.rovexquant.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF080D17)
val Panel = Color(0xFF111A29)
val PanelRaised = Color(0xFF172337)
val Line = Color(0xFF25334A)
val Muted = Color(0xFF8C9AB0)
val Mint = Color(0xFF55E0B5)
val Cyan = Color(0xFF71C9F4)
val Coral = Color(0xFFFF817D)
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
