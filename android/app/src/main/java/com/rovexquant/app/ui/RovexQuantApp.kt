package com.rovexquant.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp\nimport androidx.compose.material3.Button\nimport androidx.compose.material3.OutlinedTextField\nimport androidx.compose.runtime.mutableStateMapOf\nimport androidx.compose.ui.platform.LocalContext

private enum class TerminalSection(val label: String, val glyph: String) {
    PULSE("Pulse", "◈"),
    SCANNER("Scan", "⌕"),
    SIGNALS("Signals", "⌁"),
    LAB("Lab", "⌘"),
    PORTFOLIO("Portfolio", "▤")
}

@Composable
fun RovexQuantApp() {
    var selected by remember { mutableStateOf(TerminalSection.PULSE) }
    Column(Modifier.fillMaxSize().background(Ink)) {
        Column(Modifier.padding(horizontal = 17.dp)) { TerminalHeader(status = if (streamStatus.startsWith("Live")) "Live data" else "Backend", onSignOut = { session = null }) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(targetState = selected, label = "workspaceTransition") { destination ->
                when (destination) {
                    TerminalSection.PULSE -> MarketPulseScreen(ticks = ticks, streamStatus = streamStatus)
                    TerminalSection.SCANNER -> StrategyScannerScreen()
                    TerminalSection.SIGNALS -> SignalsScreen()
                    TerminalSection.LAB -> ResearchLabScreen()
                    TerminalSection.PORTFOLIO -> PortfolioScreen()
                }
            }
        }
        TerminalNavigation(selected = selected, onSelect = { selected = it })
    }
}

@Composable
private fun TerminalNavigation(selected: TerminalSection, onSelect: (TerminalSection) -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 7.dp, bottom = 7.dp)
            .clip(RoundedCornerShape(25.dp)).background(PanelRaised).padding(horizontal = 4.dp, vertical = 6.dp)
    ) {
        val slotWidth = maxWidth / TerminalSection.values().size
        val selectedIndex = TerminalSection.values().indexOf(selected)
        val animatedIndex by animateFloatAsState(
            targetValue = selectedIndex.toFloat(),
            animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
            label = "navigationGlide"
        )
        Box(
            Modifier.offset(x = slotWidth * animatedIndex + 2.dp)
                .width(slotWidth - 4.dp).height(43.dp).clip(RoundedCornerShape(16.dp))
                .background(Mint.copy(alpha = .12f))
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TerminalSection.values().forEach { destination ->
                val active = selected == destination
                val tint by animateColorAsState(if (active) Mint else Muted, label = "navigationTint")
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { onSelect(destination) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(destination.glyph, color = tint, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text(destination.label, color = tint, fontSize = 8.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}
