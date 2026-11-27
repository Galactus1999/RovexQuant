package com.rovexquant.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rovexquant.app.data.model.AiChatMessage
import com.rovexquant.app.data.model.AiStrategyBrief
import com.rovexquant.app.data.repository.QuantDataRepository
import com.rovexquant.app.data.model.QuantSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

@Composable
fun StrategyPlayground(
    session: QuantSession,
    repository: QuantDataRepository,
    onAskCopilot: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var symbol by rememberSaveable { mutableStateOf("RELIANCE") }
    var horizon by rememberSaveable { mutableStateOf("Swing") }
    var setup by rememberSaveable { mutableStateOf("Donchian breakout") }
    var filter by rememberSaveable { mutableStateOf("RSI < 30") }
    var riskPercent by rememberSaveable { mutableFloatStateOf(1f) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<AiStrategyBrief?>(null) }
    var peek by remember { mutableStateOf<String?>(null) }
    val today = remember { LocalDate.now(ZoneId.of("Asia/Kolkata")) }
    val fromDate = remember { today.minusDays(365).toString() }
    val toDate = remember { today.toString() }

    val generatedContext = remember(horizon, setup, filter, riskPercent) {
        buildString {
            append("Horizon: ").append(horizon).append(". ")
            append("Setup: ").append(setup).append(". ")
            append("Momentum filter: ").append(filter).append(". ")
            append("User-selected planned risk budget: ")
            append(String.format(Locale.US, "%.2f", riskPercent))
            append("% of capital per trade. This is an analysis assumption only; it is not ")
            append("a deterministic portfolio risk limit, broker order control, or execution approval. ")
            append("Evaluate the selected setup against the supplied adjusted daily EOD evidence. ")
            append("Report supporting observations, invalidation conditions, missing evidence, ")
            append("and tests required before any strategy could be considered validated. ")
            append("Do not issue a buy/sell instruction or fabricate a backtest.")
        }
    }

    TerminalCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Eyebrow("Interactive research builder")
                Text("Strategy playground", color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Choose a setup. Rovex builds the evidence request.", color = Muted, fontSize = 10.sp)
            }
            StatusPill("EOD research", Cyan)
        }
        Spacer(Modifier.height(12.dp))
        Text("INSTRUMENT", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("RELIANCE", "HDFCBANK", "INFY", "TCS").forEach { candidate ->
                FilterChip(
                    selected = symbol == candidate,
                    onClick = { symbol = candidate; result = null; error = null },
                    label = { Text(candidate) },
                    modifier = Modifier.testTag("strategy.symbol.$candidate")
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("HORIZON", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("Swing", "Positional").forEach { option ->
                FilterChip(selected = horizon == option, onClick = { horizon = option }, label = { Text(option) })
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("SETUP", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("Donchian breakout", "EMA alignment", "Bollinger re-entry").forEach { option ->
                FilterChip(
                    selected = setup == option,
                    onClick = { setup = option },
                    label = { Text(option) },
                    modifier = Modifier.testTag("strategy.setup.${option.substringBefore(' ').lowercase()}")
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("MOMENTUM FILTER", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("RSI < 30", "RSI > 50", "No RSI filter").forEach { option ->
                FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option) })
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Planned risk assumption", color = MaterialTheme.colorScheme.onBackground, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(String.format(Locale.US, "%.2f%%", riskPercent), color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("strategy.risk.value"))
        }
        Slider(
            value = riskPercent,
            onValueChange = { riskPercent = it },
            valueRange = 0.25f..3f,
            steps = 10,
            modifier = Modifier.testTag("strategy.risk.slider")
        )
        Text("This value is passed to the research prompt as context. It does not enforce portfolio risk or place orders.", color = Muted, fontSize = 9.sp, lineHeight = 14.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Ready to analyze $horizon · $setup · $filter on $symbol",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("strategy.summary")
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                if (loading) return@Button
                loading = true
                error = null
                result = null
                peek = null
                scope.launch {
                    try {
                        result = repository.requestAiStrategyBrief(
                            session = session,
                            providerId = null,
                            model = null,
                            symbol = symbol,
                            exchange = "NSE",
                            fromDate = fromDate,
                            toDate = toDate,
                            strategyContext = generatedContext,
                            fallbackProviders = emptyList(),
                            automaticTaskRouting = true
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        error = failure.message ?: "Strategy research request failed."
                    } finally {
                        loading = false
                    }
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth().testTag("strategy.run")
        ) {
            if (loading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            else Text("Run evidence-backed analysis")
        }
        Text("Source path: adjusted daily EOD recovery. This is research, not a validated backtest or live signal.", color = Muted, fontSize = 9.sp, lineHeight = 14.sp)
        error?.let { Text(it, color = Coral, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.testTag("strategy.error")) }
    }

    result?.let { report ->
        Spacer(Modifier.height(10.dp))
        TerminalCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Eyebrow("Verified deterministic evidence")
                StatusPill(report.dataMode.replace('_', ' '), Cyan)
            }
            Text("${report.exchange}:${report.symbol}", color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("${report.barCount} daily bars · ${report.firstTradingDate} to ${report.latestTradingDate} · ${report.provider}", color = Muted, fontSize = 9.sp)
            Spacer(Modifier.height(8.dp))
            StrategyEvidenceMetric(
                "RSI 14",
                report.evidence.rsi14?.let { String.format(Locale.US, "%.1f", it) } ?: "Unavailable",
                "RSI measures the magnitude of recent gains versus losses. It is a momentum oscillator, not a standalone buy signal.",
                onLongPress = { peek = it }
            )
            StrategyEvidenceMetric(
                "Donchian 20",
                report.evidence.donchianState,
                "Donchian state compares the latest close with the prior channel. A breakout needs a precisely defined lookback and completed-bar rule.",
                onLongPress = { peek = it }
            )
            StrategyEvidenceMetric(
                "Trend context",
                report.evidence.trendContext,
                "Trend context summarizes the relationship between the latest close and deterministic moving-average evidence.",
                onLongPress = { peek = it }
            )
            peek?.let { explanation ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("strategy.metric.peek"),
                    color = PanelRaised,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Eyebrow("Metric peek")
                        Text(explanation, color = MaterialTheme.colorScheme.onBackground, fontSize = 11.sp, lineHeight = 16.sp)
                        Button(
                            onClick = { onAskCopilot("Explain this metric in the context of ${report.exchange}:${report.symbol}. Evidence: ${report.evidence}. Metric explanation: $explanation") },
                            modifier = Modifier.fillMaxWidth().testTag("strategy.metric.ask.copilot")
                        ) { Text("Ask Copilot for full breakdown") }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (report.ai != null) {
                Eyebrow("Server-routed AI interpretation")
                Text(report.ai.content, color = MaterialTheme.colorScheme.onBackground, fontSize = 11.sp, lineHeight = 17.sp)
            } else {
                Text(
                    report.aiError ?: "Deterministic EOD evidence was retrieved; no AI interpretation was returned.",
                    color = Gold, fontSize = 10.sp, lineHeight = 15.sp
                )
            }
            Text(report.dataRights, color = Gold, fontSize = 9.sp, lineHeight = 14.sp)
            report.limitations.take(4).forEach { Text("• $it", color = Muted, fontSize = 9.sp, lineHeight = 14.sp) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StrategyEvidenceMetric(
    label: String,
    value: String,
    explanation: String,
    onLongPress: (String) -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = { onLongPress(explanation) })
            .padding(vertical = 7.dp)
            .testTag("strategy.metric.${label.lowercase().replace(' ', '.') }"),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Muted, fontSize = 10.sp)
        Text(value, color = MaterialTheme.colorScheme.onBackground, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun QuantCopilotSheet(
    session: QuantSession,
    repository: QuantDataRepository,
    initialPrompt: String
) {
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<AiChatMessage>() }
    var input by remember(initialPrompt) { mutableStateOf(initialPrompt) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxWidth().heightIn(min = 400.dp, max = 620.dp).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Eyebrow("Contextual research assistant")
        Text("Quant Copilot", color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Server-routed AI · no provider keys or model configuration in the cockpit.", color = Muted, fontSize = 10.sp)
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            items(messages) { message ->
                Column(
                    Modifier.fillMaxWidth().background(PanelRaised, RoundedCornerShape(12.dp)).padding(10.dp)
                ) {
                    Text(if (message.role == "user") "YOU" else "COPILOT", color = if (message.role == "user") Cyan else Mint, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    Text(message.content, color = MaterialTheme.colorScheme.onBackground, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
            if (sending) item { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) }
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it.take(6000); error = null },
            modifier = Modifier.fillMaxWidth().testTag("copilot.input"),
            placeholder = { Text("Ask about this metric or strategy…") },
            minLines = 1,
            maxLines = 3
        )
        Button(
            onClick = {
                val prompt = input.trim()
                if (prompt.isEmpty() || sending) return@Button
                val userMessage = AiChatMessage("user", prompt)
                messages.add(userMessage)
                input = ""
                sending = true
                error = null
                scope.launch {
                    try {
                        val response = repository.sendAiMessage(
                            session = session,
                            providerId = null,
                            model = null,
                            messages = listOf(
                                AiChatMessage(
                                    "system",
                                    "You are RovexQuant Copilot. Answer as a careful quantitative research assistant. Distinguish verified supplied evidence from hypotheses. Do not invent prices, indicators, data sources, backtests or citations. Do not issue orders or claim that a strategy is validated. State when the evidence is insufficient."
                                )
                            ) + messages.toList(),
                            fallbackProviders = emptyList(),
                            taskType = "chat"
                        )
                        messages.add(AiChatMessage("assistant", response.content))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        error = failure.message ?: "Copilot request failed."
                    } finally {
                        sending = false
                    }
                }
            },
            enabled = input.isNotBlank() && !sending,
            modifier = Modifier.fillMaxWidth().testTag("copilot.send")
        ) { Text(if (sending) "Thinking…" else "Ask Copilot") }
        error?.let { Text(it, color = Coral, fontSize = 10.sp, lineHeight = 15.sp) }
    }
}
