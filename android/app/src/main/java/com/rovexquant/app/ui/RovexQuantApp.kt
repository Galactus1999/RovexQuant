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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


private val dashboardIndexAliases = mapOf(
    "NIFTY 50" to listOf("NIFTY 50"),
    "NIFTY BANK" to listOf("NIFTY BANK"),
    "NIFTY FINANCIAL SERVICES" to listOf("NIFTY FINANCIAL SERVICES", "NIFTY FIN SERVICE"),
    "NIFTY IT" to listOf("NIFTY IT"),
    "NIFTY AUTO" to listOf("NIFTY AUTO"),
    "NIFTY PHARMA" to listOf("NIFTY PHARMA"),
    "NIFTY HEALTHCARE" to listOf("NIFTY HEALTHCARE"),
    "NIFTY FMCG" to listOf("NIFTY FMCG"),
    "NIFTY METAL" to listOf("NIFTY METAL"),
    "NIFTY OIL & GAS" to listOf("NIFTY OIL & GAS", "NIFTY OIL AND GAS"),
    "NIFTY REALTY" to listOf("NIFTY REALTY"),
    "NIFTY POWER" to listOf("NIFTY POWER"),
    "NIFTY MEDIA" to listOf("NIFTY MEDIA"),
    "NIFTY PSU BANK" to listOf("NIFTY PSU BANK"),
    "NIFTY PRIVATE BANK" to listOf("NIFTY PRIVATE BANK"),
    "NIFTY CONSUMER DURABLES" to listOf("NIFTY CONSUMER DURABLES"),
    "NIFTY CAPITAL GOODS" to listOf("NIFTY CAPITAL GOODS"),
    "NIFTY CHEMICALS" to listOf("NIFTY CHEMICALS"),
    "NIFTY CEMENT" to listOf("NIFTY CEMENT"),
    "NIFTY TELECOMMUNICATIONS" to listOf("NIFTY TELECOMMUNICATIONS", "NIFTY TELECOM")
)

private fun normalizeIndexName(value: String): String =
    value.uppercase().filter { it.isLetterOrDigit() }

private fun resolveDashboardIndices(master: List<MarketIndexInstrument>): Map<String, String> {
    val result = linkedMapOf<String, String>()
    for ((dashboardName, aliases) in dashboardIndexAliases) {
        val normalizedAliases = aliases.map(::normalizeIndexName).toSet()
        val match = master.firstOrNull { instrument ->
            normalizeIndexName(instrument.name) in normalizedAliases ||
                normalizeIndexName(instrument.tradingSymbol) in normalizedAliases
        }
        if (match != null) result[dashboardName] = match.instrumentKey
    }
    return result
}

private enum class TerminalSection(val label: String, val glyph: String) {
    PULSE("Pulse", "◈"),
    SCANNER("Scan", "⌕"),
    SIGNALS("Signals", "⌁"),
    LAB("Lab", "⌘"),
    PORTFOLIO("Portfolio", "▤")
}

@Composable
fun RovexQuantApp() {
    var session by remember { mutableStateOf<QuantSession?>(null) }
    var selected by remember { mutableStateOf(TerminalSection.PULSE) }
    val ticks = remember { mutableStateMapOf<String, MarketTick>() }
    val indexKeys = remember { mutableStateMapOf<String, String>() }
    var streamStatus by remember { mutableStateOf("Not connected") }

    if (session == null) {
        QuantLoginScreen(onLogin = { session = it })
        return
    }

    val activeSession = session ?: return
    val controller = remember(activeSession) {
        MarketStreamController(
            session = activeSession,
            onStatus = { streamStatus = it },
            onTick = { tick -> ticks[tick.instrumentKey] = tick }
        )
    }
    DisposableEffect(controller) {
        onDispose {
            controller.stop()
            ticks.clear()
            indexKeys.clear()
            streamStatus = "Not connected"
        }
    }
    LaunchedEffect(activeSession) {
        streamStatus = "Loading official NSE index master"
        try {
            val master = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                QuantApi.fetchIndexInstruments(activeSession)
            }
            val resolved = resolveDashboardIndices(master)
            indexKeys.clear()
            indexKeys.putAll(resolved)
            if (resolved.isEmpty()) throw IllegalStateException("No dashboard indices matched the official master.")
            controller.start(resolved.values.toList())
            streamStatus = "Connecting · ${resolved.size} indices"
        } catch (failure: Exception) {
            indexKeys.clear()
            indexKeys["NIFTY 50"] = "NSE_INDEX|Nifty 50"
            indexKeys["NIFTY BANK"] = "NSE_INDEX|Nifty Bank"
            streamStatus = "Instrument master unavailable · limited feed"
            controller.start(listOf("NSE_INDEX|Nifty 50", "NSE_INDEX|Nifty Bank"))
        }
    }

    Column(Modifier.fillMaxSize().background(Ink)) {
        Column(Modifier.padding(horizontal = 17.dp)) {
            TerminalHeader(
                status = if (streamStatus.startsWith("Live")) "Live data" else "Backend",
                onSignOut = { session = null }
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(targetState = selected, label = "workspaceTransition") { destination ->
                when (destination) {
                    TerminalSection.PULSE -> MarketPulseScreen(ticks = ticks, indexKeys = indexKeys, streamStatus = streamStatus)
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
