package com.rovexquant.app.ui

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rovexquant.app.feature.workspace.QuantWorkspaceViewModel
import java.time.Instant

private enum class TerminalSection(val label: String, val glyph: String) {
    PULSE("Pulse", "◈"),
    SCANNER("Scan", "⌕"),
    SIGNALS("Signals", "⌁"),
    LAB("Lab", "⌘"),
    PORTFOLIO("Portfolio", "▤"),
    INTELLIGENCE("Intel", "✦"),
    MACRO("Macro", "◎"),
    AI("Chat", "◉")
}

@Composable
fun RovexQuantApp(viewModelFactory: ViewModelProvider.Factory? = null) {
    val application = LocalContext.current.applicationContext as Application
    val workspace: QuantWorkspaceViewModel = viewModel(
        factory = viewModelFactory ?: ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    )
    val state by workspace.uiState.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(TerminalSection.PULSE) }
    var showSettings by remember { mutableStateOf(false) }
    var showInstrumentSearch by remember { mutableStateOf(false) }
    val wideLayout = shouldUseNavigationRail(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp)
    var selectedInstrument by remember { mutableStateOf<Pair<String, String>?>(null) }
    BackHandler(enabled = showInstrumentSearch || showSettings || selectedInstrument != null) {
        when {
            showInstrumentSearch -> showInstrumentSearch = false
            showSettings -> showSettings = false
            else -> selectedInstrument = null
        }
    }

    if (state.restoringSession) {
        Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Mint)
        }
        return
    }

    val activeSession = state.session
    if (activeSession == null) {
        QuantLoginScreen(
            savedConnection = state.savedConnection,
            loading = state.loginLoading,
            error = state.loginError,
            onLogin = workspace::login
        )
        return
    }

    if (showInstrumentSearch) {
        InstrumentSearchDialog(
            equities = state.equities,
            indexKeys = state.indexKeys,
            onDismiss = { showInstrumentSearch = false },
            onSelect = { symbol, name ->
                selectedInstrument = symbol to name
                selected = TerminalSection.PULSE
                showSettings = false
                showInstrumentSearch = false
            }
        )
    }

    Column(Modifier.fillMaxSize().background(Ink)) {
        Column(Modifier.padding(horizontal = 17.dp)) {
            val nowMillis = System.currentTimeMillis()
            val healthLevel = resolveSystemHealthLevel(
                streamStatus = state.streamStatus,
                eodSnapshotStatus = state.eodSnapshotStatus,
                hasEodSnapshot = state.eodSnapshot != null,
                ticks = state.ticks.values.toList(),
                nowMillis = nowMillis
            )
            val latestTickAt = state.ticks.values.maxByOrNull { it.receivedAt }?.receivedAt
            TerminalHeader(
                status = state.streamStatus,
                healthLevel = healthLevel,
                diagnosticDetails = listOf(
                    "Market stream · ${state.streamStatus}",
                    "Latest quote · ${latestTickAt ?: "No quote received"} · ${state.ticks.size} instruments cached",
                    "NSE EOD snapshot · ${state.eodSnapshot?.let { "${it.tradingDate} · ${it.pricedSymbols} priced symbols" } ?: state.eodSnapshotStatus}",
                    "Database ingestion · ${state.marketIngestionStatusLabel}"
                ),
                onRetry = {
                    workspace.reconnectMarketStream()
                    workspace.refreshEodSnapshot()
                    workspace.refreshMarketIngestionStatus()
                },
                onSignOut = workspace::signOut,
                onOpenSettings = { showSettings = true }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = { showInstrumentSearch = true },
                    modifier = Modifier.testTag("instrument.global.search.open")
                ) { Text("⌕ Find company / index", color = Cyan, fontSize = 11.sp) }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (wideLayout && !showSettings && selectedInstrument == null) {
                TerminalNavigation(selected, onSelect = { selected = it }, vertical = true)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                val contentWidth = maxWidth.coerceAtMost(1120.dp)
                Box(Modifier.width(contentWidth).fillMaxHeight().align(Alignment.TopCenter)) {
            if (showSettings) {
                SettingsScreen(
                    session = activeSession,
                    repository = workspace.repository,
                    onBack = { showSettings = false },
                    onOpenAiSettings = { showSettings = false; selected = TerminalSection.AI },
                    onOpenMarketPulse = { showSettings = false; selected = TerminalSection.PULSE }
                )
            } else if (selectedInstrument != null) {
                val instrument = selectedInstrument!!
                InstrumentResearchScreen(
                    symbol = instrument.first,
                    displayName = instrument.second,
                    repository = workspace.repository,
                    session = activeSession,
                    onBack = { selectedInstrument = null }
                )
            } else {
            AnimatedContent(targetState = selected, label = "workspaceTransition") { destination ->
                when (destination) {
                    TerminalSection.PULSE -> MarketPulseScreen(
                        ticks = state.ticks,
                        indexKeys = state.indexKeys,
                        equityInstruments = state.equities,
                        streamStatus = state.streamStatus,
                        session = activeSession,
                        onReconnect = workspace::reconnectMarketStream,
                        eodSnapshot = state.eodSnapshot,
                        eodSnapshotStatus = state.eodSnapshotStatus,
                        onRefreshEodSnapshot = workspace::refreshEodSnapshot,
                        onOpenInstrument = { symbol, name -> selectedInstrument = symbol to name },
                        repository = workspace.repository
                    )
                    TerminalSection.SCANNER -> StrategyScannerScreen(onOpenAi = { selected = TerminalSection.AI })
                    TerminalSection.SIGNALS -> SignalsScreen()
                    TerminalSection.LAB -> ResearchLabScreen(
                        session = activeSession,
                        repository = workspace.repository,
                        equityInstruments = state.equities,
                        indexKeys = state.indexKeys
                    )
                    TerminalSection.PORTFOLIO -> PortfolioScreen()
                    TerminalSection.INTELLIGENCE -> IntelligenceCentreScreen(
                        session = activeSession,
                        repository = workspace.repository,
                        equityInstruments = state.equities,
                        indexKeys = state.indexKeys
                    )
                    TerminalSection.MACRO -> MacroEventResearchScreen(
                        session = activeSession,
                        repository = workspace.repository,
                        equityInstruments = state.equities,
                        indexKeys = state.indexKeys
                    )
                    TerminalSection.AI -> AiWorkspaceScreen(
                        session = activeSession,
                        repository = workspace.repository,
                        equityInstruments = state.equities,
                        indexKeys = state.indexKeys
                    )
                }
            }
            }
                }
            }
        }
        if (!showSettings && selectedInstrument == null && !wideLayout) {
            TerminalNavigation(selected = selected, onSelect = { selected = it })
        }
    }
}

/** Use the expanded app-window width breakpoint for persistent rail navigation. */
internal fun shouldUseNavigationRail(windowWidthDp: Int): Boolean = windowWidthDp >= 840

@Composable
private fun TerminalNavigation(
    selected: TerminalSection,
    onSelect: (TerminalSection) -> Unit,
    vertical: Boolean = false
) {
    val primary = listOf(
        TerminalSection.PULSE,
        TerminalSection.SCANNER,
        TerminalSection.LAB,
        TerminalSection.INTELLIGENCE
    )
    val secondary = listOf(
        TerminalSection.SIGNALS,
        TerminalSection.PORTFOLIO,
        TerminalSection.MACRO,
        TerminalSection.AI
    )
    var moreExpanded by remember { mutableStateOf(false) }

    if (vertical) {
        Column(
            Modifier.width(92.dp).fillMaxHeight().navigationBarsPadding()
                .padding(start = 8.dp, top = 8.dp, bottom = 8.dp)
                .clip(RoundedCornerShape(24.dp)).background(PanelRaised).padding(6.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            (primary + secondary).forEach { destination ->
                val active = selected == destination
                val tint by animateColorAsState(if (active) Mint else Muted, label = "railTint")
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
                        .background(if (active) Mint.copy(alpha = .12f) else PanelRaised)
                        .semantics {
                            contentDescription = "Open ${destination.label}"
                            this.selected = active
                        }
                        .clickable(
                            role = Role.Tab,
                            onClickLabel = "Open ${destination.label}"
                        ) { onSelect(destination) }
                        .padding(vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(destination.glyph, color = tint, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text(
                        destination.label,
                        color = tint,
                        fontSize = 9.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
        return
    }

    BoxWithConstraints(
        Modifier.fillMaxWidth().navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 7.dp, bottom = 7.dp)
            .clip(RoundedCornerShape(25.dp)).background(PanelRaised)
            .padding(horizontal = 4.dp, vertical = 6.dp)
    ) {
        val slotWidth = maxWidth / 5
        val selectedIndex = primary.indexOf(selected).let { if (it >= 0) it else 4 }
        val animatedIndex by animateFloatAsState(
            targetValue = selectedIndex.toFloat(),
            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
            label = "navigationGlide"
        )
        Box(
            Modifier.offset(x = slotWidth * animatedIndex + 2.dp)
                .width(slotWidth - 4.dp).height(45.dp)
                .clip(RoundedCornerShape(16.dp)).background(Mint.copy(alpha = .12f))
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            primary.forEach { destination ->
                val active = selected == destination
                val tint by animateColorAsState(if (active) Mint else Muted, label = "navigationTint")
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                        .semantics {
                            contentDescription = "Open ${destination.label}"
                            this.selected = active
                        }
                        .clickable(
                            role = Role.Tab,
                            onClickLabel = "Open ${destination.label}"
                        ) { onSelect(destination) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(destination.glyph, color = tint, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        destination.label,
                        color = tint,
                        fontSize = 9.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .semantics {
                            contentDescription = "More workspaces"
                            this.selected = selected in secondary
                        }
                        .clickable(role = Role.Button, onClickLabel = "More workspaces") {
                            moreExpanded = true
                        }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "⋯",
                        color = if (selected in secondary) Mint else Muted,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        "More",
                        color = if (selected in secondary) Mint else Muted,
                        fontSize = 9.sp,
                        fontWeight = if (selected in secondary) FontWeight.Bold else FontWeight.Medium
                    )
                }
                DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                    secondary.forEach { destination ->
                        DropdownMenuItem(
                            text = { Text(destination.label) },
                            leadingIcon = { Text(destination.glyph) },
                            onClick = {
                                moreExpanded = false
                                onSelect(destination)
                            }
                        )
                    }
                }
            }
        }
    }
}


internal fun resolveSystemHealthLevel(
    streamStatus: String,
    eodSnapshotStatus: String,
    hasEodSnapshot: Boolean,
    ticks: List<com.rovexquant.app.data.model.MarketTick>,
    nowMillis: Long
): SystemHealthLevel {
    val stream = streamStatus.lowercase()
    val eod = eodSnapshotStatus.lowercase()
    val hardFailure = listOf("failed", "error", "unavailable").any { token ->
        stream.contains(token) || (eod.contains(token) && !hasEodSnapshot)
    }
    if (hardFailure) return SystemHealthLevel.ERROR

    val freshValidTick = ticks.any { tick ->
        tick.quality == "VALID" && runCatching {
            val receivedAt = Instant.parse(tick.receivedAt).toEpochMilli()
            receivedAt <= nowMillis + 60_000 && receivedAt >= nowMillis - 30_000
        }.getOrDefault(false)
    }
    val liveStream = stream.startsWith("live")
    return if (liveStream && freshValidTick && hasEodSnapshot) {
        SystemHealthLevel.HEALTHY
    } else {
        SystemHealthLevel.DEGRADED
    }
}
