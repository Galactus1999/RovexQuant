package com.rovexquant.app.ui

import android.app.Application
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rovexquant.app.feature.workspace.QuantWorkspaceViewModel

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

    Column(Modifier.fillMaxSize().background(Ink)) {
        Column(Modifier.padding(horizontal = 17.dp)) {
            TerminalHeader(
                status = if (state.streamStatus.startsWith("Live")) "Live data" else "Backend",
                onSignOut = workspace::signOut
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(targetState = selected, label = "workspaceTransition") { destination ->
                when (destination) {
                    TerminalSection.PULSE -> MarketPulseScreen(
                        ticks = state.ticks,
                        indexKeys = state.indexKeys,
                        equityInstruments = state.equities,
                        streamStatus = state.streamStatus,
                        session = activeSession,
                        repository = workspace.repository,
                        onReconnect = workspace::reconnectMarketStream,
                        eodSnapshot = state.eodSnapshot,
                        eodSnapshotStatus = state.eodSnapshotStatus,
                        onRefreshEodSnapshot = workspace::refreshEodSnapshot,
                        marketIngestionStatus = state.marketIngestionStatus,
                        marketIngestionStatusLabel = state.marketIngestionStatusLabel,
                        onRefreshMarketIngestionStatus = workspace::refreshMarketIngestionStatus
                    )
                    TerminalSection.SCANNER -> StrategyScannerScreen(onOpenAi = { selected = TerminalSection.AI })
                    TerminalSection.SIGNALS -> SignalsScreen()
                    TerminalSection.LAB -> ResearchLabScreen(
                        session = activeSession,
                        repository = workspace.repository
                    )
                    TerminalSection.PORTFOLIO -> PortfolioScreen()
                    TerminalSection.INTELLIGENCE -> IntelligenceCentreScreen(session = activeSession, repository = workspace.repository)
                    TerminalSection.MACRO -> MacroEventResearchScreen(session = activeSession, repository = workspace.repository)
                    TerminalSection.AI -> AiWorkspaceScreen(session = activeSession, repository = workspace.repository)
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
