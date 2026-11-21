package com.rovexquant.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class Horizon(val title: String, val range: String, val summary: String) {
    SCALP("Scalping", "1m–5m", "Liquidity, spread, volatility and fast confirmation"),
    INTRADAY("Intraday", "5m–15m", "Session VWAP, opening range and relative volume"),
    SWING("Swing", "1D–1W", "Trend continuation, channel breakouts and momentum"),
    POSITIONAL("Positional", "1W–1M", "Market regime, sector strength and risk-adjusted trends"),
    INVESTMENT("Investment", "1M+", "Quality, value, momentum and portfolio concentration")
}

private data class StrategyItem(val name: String, val detail: String, val status: String, val implemented: Boolean)

@Composable
fun StrategyScannerScreen() {
    var horizon by rememberSaveable { mutableStateOf(Horizon.INTRADAY) }
    val strategyItems = when (horizon) {
        Horizon.SCALP -> listOf(
            StrategyItem("VWAP reclaim", "Session VWAP reclaim; spread and liquidity filters still pending", "Prototype logic", true),
            StrategyItem("Opening impulse", "Opening-range breakout; volume confirmation still pending", "Prototype logic", true),
            StrategyItem("Micro pullback", "Trend continuation after controlled retracement", "Research candidate", false)
        )
        Horizon.INTRADAY -> listOf(
            StrategyItem("Opening range breakout", "Break only after the opening range is complete", "Prototype logic", true),
            StrategyItem("Session VWAP cross", "Session-anchored HLC3 VWAP; resets by session", "Prototype logic", true),
            StrategyItem("Relative volume", "Volume versus time-of-day baseline", "Research candidate", false),
            StrategyItem("Gap continuation", "Gap context with trend and liquidity filters", "Research candidate", false)
        )
        Horizon.SWING -> listOf(
            StrategyItem("Donchian breakout", "Close beyond the prior N-bar channel", "Prototype logic", true),
            StrategyItem("EMA alignment", "Fast/slow trend regime", "Prototype logic", true),
            StrategyItem("RSI regime", "Momentum context; not a standalone entry", "Prototype logic", true),
            StrategyItem("Bollinger re-entry", "Close re-enters a prior outside-band region", "Prototype logic", true),
            StrategyItem("Volatility contraction", "Compression followed by range expansion", "Research candidate", false)
        )
        Horizon.POSITIONAL -> listOf(
            StrategyItem("Sector-relative strength", "Rank constituents against sector and benchmark", "Research candidate", false),
            StrategyItem("Trend + ATR regime", "Longer trend filter with volatility-aware risk", "Research candidate", false),
            StrategyItem("Market regime filter", "Trend, breadth and volatility state", "Research candidate", false)
        )
        Horizon.INVESTMENT -> listOf(
            StrategyItem("Quality + value", "Profitability, leverage and valuation factors", "Research candidate", false),
            StrategyItem("Momentum factor", "Point-in-time relative strength ranking", "Research candidate", false),
            StrategyItem("Low volatility", "Risk-aware universe and concentration filters", "Research candidate", false)
        )
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionHeading("Find setups, not stories", "Strategy scanner.", "Separate playbooks for every holding period. Strategies are research candidates, not buy/sell recommendations.") }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Horizon.values().forEach { item ->
                    FilterChip(selected = horizon == item, onClick = { horizon = item }, label = { Text(item.title) })
                }
            }
        }
        item {
            TerminalCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow(horizon.title + " workspace")
                        Spacer(Modifier.height(5.dp))
                        Text(horizon.summary, color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp)
                    }
                    Text(horizon.range, color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        items(strategyItems) { strategy ->
            TerminalCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(strategy.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(5.dp))
                        Text(strategy.detail, color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    StatusPill(if (strategy.implemented) "Backend" else "Planned", if (strategy.implemented) Mint else Gold)
                }
                Spacer(Modifier.height(10.dp))
                Text(strategy.status.uppercase(), color = if (strategy.implemented) Mint else Muted, fontSize = 8.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
            }
        }
        item { EmptyPanel("Scanner is in preview mode", "Universe filtering, live scheduling, ranking and alert delivery will activate only after an authorised market-data adapter and persistent scanner are connected.") }
    }
}
