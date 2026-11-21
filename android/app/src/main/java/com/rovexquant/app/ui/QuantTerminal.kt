package com.rovexquant.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class Workspace(val label: String, val glyph: String, val subtitle: String) {
    HOME("Pulse", "◈", "Your market at a glance"),
    SCALP("Scalp", "⌁", "Fast setups • strict execution filters"),
    INTRADAY("Day", "◷", "Session structure • VWAP • opening range"),
    SWING("Swing", "↗", "Multi-day trends • momentum • breakouts"),
    PORTFOLIO("Invest", "▤", "Positional ideas • long-term allocation")
}

@Composable
fun QuantTerminal() {
    var selected by remember { mutableStateOf(Workspace.HOME) }
    var watchTab by remember { mutableStateOf("Watchlist") }
    Column(Modifier.fillMaxSize().background(Ink)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(18.dp))
            Header()
            Spacer(Modifier.height(26.dp))
            WorkspaceHeading(selected)
            Spacer(Modifier.height(18.dp))
            when (selected) {
                Workspace.HOME -> PulseScreen(watchTab) { watchTab = it }
                Workspace.SCALP -> StrategyScreen("SCALPING DESK", "1m – 5m", "Liquidity and spread first", listOf("VWAP reclaim", "Opening range break", "Momentum burst"), "Strict filters: spread, volume, volatility and data freshness")
                Workspace.INTRADAY -> StrategyScreen("INTRADAY DESK", "5m – 15m", "Trade the session, not the noise", listOf("Opening range breakout", "VWAP trend pullback", "Relative volume expansion"), "Session-aware setups with opening range, VWAP and market breadth context")
                Workspace.SWING -> StrategyScreen("SWING DESK", "1D – 1W", "Find strength that can persist", listOf("Trend continuation", "Volatility contraction", "Relative strength breakout"), "Multi-day confirmation with sector-relative strength and ATR-based invalidation")
                Workspace.PORTFOLIO -> StrategyScreen("POSITIONAL & INVESTMENT", "1W – 1M", "Build conviction with evidence", listOf("Quality + value screen", "Sector rotation", "Long-term trend filter"), "Fundamental snapshots, valuation context and portfolio exposure tools")
            }
            Spacer(Modifier.height(24.dp))
            FooterNote()
            Spacer(Modifier.height(22.dp))
        }
        BottomDock(selected = selected, onSelect = { selected = it })
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(PanelRaised), contentAlignment = Alignment.Center) {
            Text("R", color = Mint, fontSize = 25.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text("ROVEX", color = MaterialTheme.colorScheme.onBackground, fontSize = 17.sp, fontWeight = FontWeight.Black, letterSpacing = 2.2.sp)
            Text("QUANT TERMINAL", color = Muted, fontSize = 9.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.clip(CircleShape).background(Panel).border(1.dp, Line, CircleShape).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Gold))
            Spacer(Modifier.width(7.dp))
            Text("DEMO MODE", color = Gold, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun WorkspaceHeading(workspace: Workspace) {
    Text(workspace.subtitle.uppercase(), color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(5.dp))
    Text(
        when (workspace) {
            Workspace.HOME -> "Market pulse."
            Workspace.SCALP -> "Precision entries."
            Workspace.INTRADAY -> "Own the session."
            Workspace.SWING -> "Catch the move."
            Workspace.PORTFOLIO -> "Think in cycles."
        },
        color = MaterialTheme.colorScheme.onBackground, fontSize = 29.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(6.dp))
    Text("Research workspace  /  " + workspace.label, color = Muted, fontSize = 12.sp)
}

@Composable
private fun PulseScreen(watchTab: String, onTab: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        MetricCard("NIFTY 50", "—", "Awaiting feed", Modifier.weight(1f), Mint)
        MetricCard("SENSEX", "—", "Awaiting feed", Modifier.weight(1f), Cyan)
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        MetricCard("MARKET BREADTH", "—", "Not calculated", Modifier.weight(1f), Gold)
        MetricCard("ACTIVE SIGNALS", "0", "No live engine", Modifier.weight(1f), Coral)
    }
    Spacer(Modifier.height(16.dp))
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow("MARKET STRUCTURE")
                Text("Index overview", color = MaterialTheme.colorScheme.onBackground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("SAMPLE CHART", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
        }
        Spacer(Modifier.height(14.dp))
        MarketLine()
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("09:15", color = Muted, fontSize = 10.sp)
            Text("12:00", color = Muted, fontSize = 10.sp)
            Text("15:30", color = Muted, fontSize = 10.sp)
        }
    }
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Your market", color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text("EDIT UNIVERSE  ↗", color = Mint, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Watchlist", "Top movers", "Sectors").forEach { label ->
            val active = watchTab == label
            Box(Modifier.clip(CircleShape).background(if (active) PanelRaised else Ink).border(1.dp, if (active) Mint.copy(alpha = .55f) else Line, CircleShape).clickable { onTab(label) }.padding(horizontal = 13.dp, vertical = 9.dp)) {
                Text(label, color = if (active) Mint else Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
    Spacer(Modifier.height(9.dp))
    PanelCard {
        when (watchTab) {
            "Watchlist" -> listOf("NIFTY 50", "BANKNIFTY", "RELIANCE", "HDFCBANK").forEachIndexed { i, name ->
                WatchRow(name, listOf("Index", "Index", "Energy", "Banking")[i], "—", "Feed not connected")
                if (i != 3) Hairline()
            }
            "Top movers" -> EmptyState("Mover scanner is waiting for an authorised market-data feed.")
            else -> EmptyState("Sector strength and breadth will appear when data is connected.")
        }
    }
    Spacer(Modifier.height(16.dp))
    PanelCard {
        Eyebrow("SIGNAL INTELLIGENCE")
        Text("No invented calls. No stale prices.", color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("Signals will only unlock after feed validation, timestamp checks and risk verification.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun StrategyScreen(kicker: String, timeframe: String, headline: String, strategies: List<String>, detail: String) {
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow(kicker)
                Text(headline, color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(Modifier.clip(CircleShape).background(PanelRaised).padding(horizontal = 11.dp, vertical = 7.dp)) {
                Text(timeframe, color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(detail, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
    Spacer(Modifier.height(14.dp))
    Text("STRATEGY PLAYBOOK", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(9.dp))
    strategies.forEachIndexed { index, strategy ->
        PanelCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(PanelRaised), contentAlignment = Alignment.Center) {
                    Text("0" + (index + 1), color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(strategy, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Rule-based setup • awaiting data", color = Muted, fontSize = 10.sp)
                }
                Text("↗", color = Mint, fontSize = 18.sp)
            }
        }
        Spacer(Modifier.height(9.dp))
    }
    PanelCard {
        Eyebrow("WORKSPACE TOOLS")
        Text("Scanner  ·  Setup journal  ·  Backtest lab  ·  Risk controls", color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(7.dp))
        Text("Tools are staged in the interface; calculations and data adapters will be connected in subsequent implementation phases.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun MetricCard(label: String, value: String, hint: String, modifier: Modifier, accent: androidx.compose.ui.graphics.Color) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Panel).border(1.dp, Line, RoundedCornerShape(18.dp)).padding(14.dp)) {
        Text(label, color = Muted, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(11.dp))
        Text(value, color = accent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(3.dp))
        Text(hint, color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).border(1.dp, Line.copy(alpha = .8f), RoundedCornerShape(20.dp)).padding(16.dp), content = content)
}

@Composable
private fun Eyebrow(value: String) {
    Text(value, color = Mint, fontSize = 9.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun WatchRow(name: String, type: String, price: String, status: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(type, color = Muted, fontSize = 10.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(price, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(status, color = Muted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun Hairline() {
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(Line.copy(alpha = .7f)))
}

@Composable
private fun EmptyState(message: String) {
    Text(message, color = Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(vertical = 12.dp))
}

@Composable
private fun MarketLine() {
    Canvas(Modifier.fillMaxWidth().height(112.dp)) {
        val values = listOf(.68f, .61f, .66f, .48f, .55f, .43f, .49f, .35f, .4f, .27f, .33f, .2f, .25f, .14f, .19f, .1f, .16f)
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = size.width * index / (values.size - 1)
            val y = size.height * value
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        for (i in 1..3) {
            val y = size.height * i / 4f
            drawLine(Line, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1f)
        }
        drawPath(path, Mint, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(Mint, radius = 4.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width, size.height * values.last()))
    }
}

@Composable
private fun BottomDock(selected: Workspace, onSelect: (Workspace) -> Unit) {
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 9.dp, bottom = 8.dp).clip(RoundedCornerShape(25.dp)).background(PanelRaised).border(1.dp, Line, RoundedCornerShape(25.dp)).padding(horizontal = 5.dp, vertical = 7.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        Workspace.values().forEach { item ->
            val active = item == selected
            val tint by animateColorAsState(if (active) Mint else Muted, label = "navTint")
            val indicatorWidth by animateDpAsState(if (active) 64.dp else 42.dp, label = "navWidth")
            Column(Modifier.weight(1f).clip(RoundedCornerShape(17.dp)).clickable { onSelect(item) }.animateContentSize().padding(vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(indicatorWidth).clip(RoundedCornerShape(12.dp)).background(if (active) Mint.copy(alpha = .12f) else PanelRaised).padding(vertical = 5.dp), contentAlignment = Alignment.Center) {
                    Text(item.glyph, color = tint, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(3.dp))
                Text(item.label, color = tint, fontSize = 9.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

@Composable
private fun FooterNote() {
    Row(verticalAlignment = Alignment.Top) {
        Text("i", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text("Interface preview • Market feed disconnected • Values are intentionally blank until verified data is available.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
    }
}
