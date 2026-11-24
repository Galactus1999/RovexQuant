package com.rovexquant.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rovexquant.app.domain.OhlcvCsvResearch
import com.rovexquant.app.domain.OhlcvResearchSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private enum class Horizon(val title: String, val range: String, val summary: String) {
    SCALP("Scalping", "1m–5m", "Liquidity, spread, volatility and fast confirmation"),
    INTRADAY("Intraday", "5m–15m", "Session VWAP, opening range and relative volume"),
    SWING("Swing", "1D–1W", "Trend continuation, channel breakouts and momentum"),
    POSITIONAL("Positional", "1W–1M", "Market regime, sector strength and risk-adjusted trends"),
    INVESTMENT("Investment", "1M+", "Quality, value, momentum and portfolio concentration")
}
private data class StrategyItem(val name: String, val detail: String, val status: String, val implemented: Boolean)

@Composable
fun StrategyScannerScreen(onOpenAi: () -> Unit = {}, onOpenResearchLab: () -> Unit = {}) {
    val context = LocalContext.current
    var localSummary by remember { mutableStateOf<OhlcvResearchSummary?>(null) }
    var loadingLocalData by remember { mutableStateOf(false) }
    var localDataError by remember { mutableStateOf<String?>(null) }
    var expandedStrategy by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(context) {
        if (context.fileList().contains(OhlcvCsvResearch.STORAGE_FILE_NAME)) {
            loadingLocalData = true
            try {
                localSummary = withContext(Dispatchers.IO) {
                    context.openFileInput(OhlcvCsvResearch.STORAGE_FILE_NAME).bufferedReader().use { reader ->
                        OhlcvCsvResearch.summarize(OhlcvCsvResearch.parse(reader.readText()))
                    }
                }
            } catch (failure: Exception) {
                localDataError = failure.message ?: "Local dataset could not be analysed"
            } finally {
                loadingLocalData = false
            }
        }
    }

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
    LazyColumn(modifier = Modifier.fillMaxSize().testTag("scanner.list"), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionHeading("Research workbench", "Strategy scanner.", "Historical data first. No live signals until a strategy is validated.") }
        item {
            when {
                loadingLocalData -> EmptyPanel("Loading local dataset", "Reading your privately stored historical CSV.")
                localDataError != null -> GracefulErrorState("Saved dataset unavailable. Re-import your CSV.")
                localSummary == null -> {
                    TerminalCard {
                        Eyebrow("Strategy workspace")
                        EmptyCandlestickChart(
                            message = "Your risk / reward canvas is ready when historical data is loaded.",
                            actionLabel = "＋ Import CSV",
                            onAction = onOpenResearchLab
                        )
                    }
                }
                else -> {
                    val report = localSummary!!
                    TerminalCard {
                        Eyebrow("Local dataset scan · offline")
                        Spacer(Modifier.height(7.dp))
                        Text(report.trend, color = if (report.trend.startsWith("Bullish")) Mint else if (report.trend.startsWith("Bearish")) Coral else Gold, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        ScannerMetric("Donchian 20", report.donchian20)
                        ScannerMetric("Relative volume 20", report.relativeVolume20?.let { String.format(Locale.US, "%.2fx", it) } ?: "Warming up")
                        ScannerMetric("RSI 14", report.rsi14?.let { String.format(Locale.US, "%.1f", it) } ?: "Warming up")
                        Spacer(Modifier.height(5.dp))
                        Text(report.rows.toString() + " bars · as of " + report.lastTimestamp.toString(), color = Muted, fontSize = 9.sp)
                        Text("Historical file analysis only · not a live signal.", color = Muted, fontSize = 9.sp)
                        Spacer(Modifier.height(8.dp))
                        RiskRewardPlaceholder(
                            message = "A single-instrument file cannot populate a multi-asset risk / reward scatter.",
                            onImport = onOpenResearchLab
                        )
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Horizon.values().forEach { item -> FilterChip(selected = horizon == item, onClick = { horizon = item }, label = { Text(item.title) }) }
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
                        if (expandedStrategy == strategy.name) {
                            Spacer(Modifier.height(5.dp))
                            Text(strategy.detail, color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                        StatusPill(if (strategy.implemented) "Prototype" else "Planned", if (strategy.implemented) Gold else Muted)
                        androidx.compose.material3.TextButton(onClick = { expandedStrategy = if (expandedStrategy == strategy.name) null else strategy.name }) {
                            Text(if (expandedStrategy == strategy.name) "Less" else "Details")
                        }
                    }
                }
                Text(strategy.status.uppercase(), color = Muted, fontSize = 8.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
            }
        }
        item {
            TerminalCard {
                Eyebrow("Final strategy stage · fallback")
                Spacer(Modifier.height(6.dp))
                Text(
                    "If local CSV import is unavailable, open Quant AI to retrieve corporate-action-adjusted daily EOD bars and review the strategy with source-labelled evidence.",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 11.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(9.dp))
                Button(onClick = onOpenAi, modifier = Modifier.fillMaxWidth().testTag("scanner.ai.recovery")) {
                    Text("Open AI data recovery")
                }
            }
        }
        item { Text("Not a validated trading system · multi-stock scanning and live alerts are not enabled.", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 4.dp)) }
    }
}

@Composable
private fun ScannerMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 11.sp)
        Text(value, color = MaterialTheme.colorScheme.onBackground, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RiskRewardPlaceholder(message: String, onImport: () -> Unit) {
    TerminalCard {
        Eyebrow("Risk / reward map")
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(132.dp)) {
            val grid = Color(0xFF334155)
            for (i in 1..4) {
                val y = size.height * i / 5f
                drawLine(grid, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 0.8f)
            }
            for (i in 1..5) {
                val x = size.width * i / 6f
                drawLine(grid, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 0.8f)
            }
            drawLine(Color(0xFF64748B), androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, 0f), 1.2f)
        }
        Text(message, color = Muted, fontSize = 9.sp, lineHeight = 14.sp)
        androidx.compose.material3.TextButton(onClick = onImport) { Text("Add historical data") }
    }
}