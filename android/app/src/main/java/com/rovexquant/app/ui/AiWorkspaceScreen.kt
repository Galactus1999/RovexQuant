package com.rovexquant.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rovexquant.app.data.model.MarketEquityInstrument
import com.rovexquant.app.data.model.AiStrategyBrief
import com.rovexquant.app.data.model.QuantSession
import com.rovexquant.app.data.repository.QuantDataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

@Composable
fun AiWorkspaceScreen(
    session: QuantSession,
    repository: QuantDataRepository,
    equityInstruments: List<MarketEquityInstrument> = emptyList(),
    indexKeys: Map<String, String> = emptyMap(),
    onOpenSettings: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var providersLoading by remember { mutableStateOf(true) }
    var providers by remember { mutableStateOf(emptyList<com.rovexquant.app.data.model.AiProvider>()) }
    var providerError by remember { mutableStateOf<String?>(null) }
    var researchRuns by remember { mutableStateOf(emptyList<com.rovexquant.app.data.model.AiResearchRun>()) }
    var historyLoading by remember { mutableStateOf(true) }
    var historyError by remember { mutableStateOf<String?>(null) }
    var aiBudgetStatus by remember { mutableStateOf<com.rovexquant.app.data.model.AiBudgetStatus?>(null) }
    var selectedResearchRun by remember { mutableStateOf<com.rovexquant.app.data.model.AiResearchRunDetail?>(null) }
    var selectedRunLoading by remember { mutableStateOf(false) }
    var selectedRunError by remember { mutableStateOf<String?>(null) }
    val automaticTaskRouting = true
    var evidenceDraft by rememberSaveable { mutableStateOf("") }

    var symbol by rememberSaveable { mutableStateOf("RELIANCE") }
    var exchange by rememberSaveable { mutableStateOf("NSE") }
    var fromDate by rememberSaveable {
        mutableStateOf(LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(365).toString())
    }
    var toDate by rememberSaveable {
        mutableStateOf(LocalDate.now(ZoneId.of("Asia/Kolkata")).toString())
    }
    var strategyContext by rememberSaveable {
        mutableStateOf("Review a swing or positional strategy using trend, momentum and Donchian breakout evidence. Identify missing tests and failure modes.")
    }
    var briefLoading by remember { mutableStateOf(false) }
    var briefError by remember { mutableStateOf<String?>(null) }
    var brief by remember { mutableStateOf<AiStrategyBrief?>(null) }

    LaunchedEffect(session, repository) {
        providersLoading = true
        providerError = null
        try {
            providers = repository.fetchAiProviders(session)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            providerError = failure.message ?: "AI provider discovery failed."
        } finally {
            providersLoading = false
        }
    }

    LaunchedEffect(session, repository) {
        historyLoading = true
        historyError = null
        try {
            researchRuns = repository.fetchAiResearchRuns(session)
            aiBudgetStatus = repository.fetchAiBudgetStatus(session)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            historyError = failure.message ?: "AI research history is unavailable."
        } finally {
            historyLoading = false
        }
    }

    val configuredProviders = providers.filter { it.configured }
    val canUseResearch = configuredProviders.any { repository.isAiProviderEnabled(it.providerId) }

    fun recoverEodData() {
        if (briefLoading) return
        brief = null
        briefError = null
        briefLoading = true
        scope.launch {
            try {
                brief = repository.requestAiStrategyBrief(
                    session = session,
                    providerId = null,
                    model = null,
                    symbol = symbol.trim(),
                    exchange = exchange,
                    fromDate = fromDate.trim(),
                    toDate = toDate.trim(),
                    strategyContext = strategyContext.trim(),
                    fallbackProviders = emptyList(),
                    automaticTaskRouting = true
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                briefError = failure.message ?: "EOD recovery failed."
            } finally {
                briefLoading = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("ai.workspace.list"),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionHeading(
                "Evidence-led research · server-routed AI",
                "Quant Intelligence.",
                "Review archived research, attach verified evidence and run server-routed analysis. Market data remains authoritative."
            )
        }
        item {
            TerminalCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Eyebrow("Server-owned AI routing")
                    StatusPill(
                        if (providersLoading) "Checking" else if (canUseResearch) "Research ready" else "Setup required",
                        if (!providersLoading && canUseResearch) Mint else Gold
                    )
                }
                Text(
                    "${configuredProviders.size} configured provider(s) · switches and encrypted credentials are managed in Settings; model selection is automatic.",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
                providerError?.let { Text(it, color = Gold, fontSize = 10.sp) }
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().testTag("ai.open.settings")) {
                    Text("Manage providers in Settings")
                }
            }
        }
        item {
            AiResearchToolsPanel(session, repository) { context ->
                evidenceDraft = (context + "\n\n" + evidenceDraft).take(10000)
            }
        }
        item {
            AiResearchBriefingPanel(
                session = session,
                repository = repository,
                providerId = "",
                model = "",
                fallbackProviders = emptyList(),
                canUseAi = canUseResearch,
                evidenceDraft = evidenceDraft,
                automaticTaskRouting = automaticTaskRouting
            )
        }
        item {
            Column(
                modifier = Modifier.fillMaxWidth().testTag("ai.research.history"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TerminalCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Eyebrow("Persistent research history")
                        TextButton(
                            enabled = !historyLoading,
                            onClick = {
                                historyLoading = true
                                historyError = null
                                scope.launch {
                                    try {
                                        researchRuns = repository.fetchAiResearchRuns(session)
                                        aiBudgetStatus = repository.fetchAiBudgetStatus(session)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        historyError = failure.message ?: "Research history refresh failed."
                                    } finally {
                                        historyLoading = false
                                    }
                                }
                            },
                            modifier = Modifier.testTag("ai.research.history.refresh")
                        ) { Text(if (historyLoading) "Loading…" else "Refresh") }
                    }
                    Text(
                        "Archived runs are read-only evidence of previous AI requests. Token usage and estimated cost are telemetry, not a guarantee of provider billing.",
                        color = Muted, fontSize = 9.sp, lineHeight = 14.sp
                    )
                    aiBudgetStatus?.let { budget ->
                        StatusPill(
                            if (budget.enabled) "Token budgets enabled" else "Token budgets disabled",
                            if (budget.enabled) Mint else Gold
                        )
                        val daily = budget.dailyLimitTokens?.let { "${budget.dailyUsedTokens} / $it tokens" }
                            ?: "${budget.dailyUsedTokens} tokens · no daily cap"
                        val monthly = budget.monthlyLimitTokens?.let { "${budget.monthlyUsedTokens} / $it tokens" }
                            ?: "${budget.monthlyUsedTokens} tokens · no monthly cap"
                        Text("Daily: $daily", color = Muted, fontSize = 9.sp, modifier = Modifier.testTag("ai.research.budget.daily"))
                        Text("Monthly: $monthly", color = Muted, fontSize = 9.sp, modifier = Modifier.testTag("ai.research.budget.monthly"))
                    }
                    historyError?.let { Text("History unavailable: $it", color = Gold, fontSize = 9.sp, lineHeight = 14.sp) }
                    if (historyLoading && researchRuns.isEmpty()) CircularProgressIndicator()
                    if (!historyLoading && researchRuns.isEmpty() && historyError == null) {
                        Text("No archived research runs yet.", color = Muted, fontSize = 10.sp)
                    }
                    researchRuns.take(12).forEach { run ->
                        Spacer(Modifier.height(7.dp))
                        Column(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(PanelRaised)
                                .padding(10.dp)
                                .testTag("ai.research.run.${run.runId}")
                        ) {
                            Text(
                                run.taskType.replace('_', ' ').uppercase() + " · " + run.providerId + "/" + run.model,
                                color = MaterialTheme.colorScheme.onBackground,
                                fontSize = 10.sp, fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                run.createdAt.take(19).replace('T', ' ') + " · " + run.status +
                                    " · " + run.totalTokens + " tokens" +
                                    if (run.fallbackUsed) " · fallback used" else "",
                                color = Muted, fontSize = 9.sp
                            )
                            val cost = run.costUsd?.let { "Estimated USD " + String.format(Locale.US, "%.6f", it) }
                                ?: "Cost unavailable · ${run.costStatus}"
                            Text(cost, color = Gold, fontSize = 9.sp)
                            TextButton(
                                enabled = !selectedRunLoading,
                                onClick = {
                                    selectedRunLoading = true
                                    selectedRunError = null
                                    selectedResearchRun = null
                                    scope.launch {
                                        try {
                                            selectedResearchRun = repository.fetchAiResearchRun(session, run.runId)
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (failure: Exception) {
                                            selectedRunError = failure.message ?: "Archived research detail is unavailable."
                                        } finally {
                                            selectedRunLoading = false
                                        }
                                    }
                                },
                                modifier = Modifier.testTag("ai.research.run.open.${run.runId}")
                            ) { Text(if (selectedRunLoading) "Loading details…" else "Open run details") }
                        }
                    }
                    selectedRunError?.let { Text("Run detail unavailable: $it", color = Gold, fontSize = 9.sp) }
                    if (selectedRunLoading) CircularProgressIndicator()
                    selectedResearchRun?.let { detail ->
                        Column(
                            Modifier.fillMaxWidth().padding(top = 8.dp)
                                .testTag("ai.research.run.detail"),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Eyebrow("Archived run detail · ${detail.status}")
                            Text("Request", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text(detail.requestPayloadJson.take(6000), modifier = Modifier.testTag("ai.research.run.detail.request"), color = MaterialTheme.colorScheme.onBackground, fontSize = 9.sp, lineHeight = 13.sp)
                            Text("Response", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text(detail.responsePayloadJson.take(12000), modifier = Modifier.testTag("ai.research.run.detail.response"), color = MaterialTheme.colorScheme.onBackground, fontSize = 9.sp, lineHeight = 13.sp)
                            Text("Source references", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text(detail.sourceRefsJson.take(6000), color = Cyan, fontSize = 9.sp, lineHeight = 13.sp)
                            Text("Verification", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text(detail.verificationJson.take(6000), color = Gold, fontSize = 9.sp, lineHeight = 13.sp)
                            Text("Request hash · ${detail.requestHash}", color = Muted, fontSize = 8.sp)
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeading("Connected research tools", "NSE MCP.", "Discover permitted read-only exchange research tools and explicitly attach a selected result to an AI request.")
                NseMcpResearchPanel(
                    session = session,
                    repository = repository,
                    onAttachToAi = { context ->
                        evidenceDraft = (context + "\n\n" + evidenceDraft).take(10000)

                    },
                    equityInstruments = equityInstruments,
                    indexKeys = indexKeys
                )
            }
        }
        item {
            TerminalCard {
                Eyebrow("Contextual Copilot")
                Text(
                    "The conversational assistant is available from the Copilot control on Market Pulse. It uses the server-owned chat route and can receive metric or strategy context.",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 11.sp,
                    lineHeight = 17.sp
                )
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Review AI provider setup")
                }
            }
        }
        item {
            TerminalCard {
                Eyebrow("Final strategy stage · data rescue")
                Spacer(Modifier.height(6.dp))
                Text(
                    "If your CSV import is unavailable, Quant can retrieve corporate-action-adjusted daily OHLC from its TejHQ EOD adapter and calculate deterministic price evidence. If an AI provider is configured, Quant also asks it to review the strategy. Raw volume is excluded from relative-volume claims.",
                    color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(10.dp))
                InstrumentSymbolPicker(
                    value = symbol,
                    onValueChange = { symbol = it.uppercase().take(40) },
                    label = "NSE/BSE trading symbol",
                    testTag = "ai.recovery.symbol",
                    equities = if (exchange == "NSE") equityInstruments else emptyList(),
                    indexKeys = if (exchange == "NSE") indexKeys else emptyMap(),
                    enabled = exchange == "NSE"
                )
                Spacer(Modifier.height(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("NSE", "BSE").forEach { item ->
                        FilterChip(selected = exchange == item, onClick = { exchange = item }, label = { Text(item) })
                    }
                }
                OutlinedTextField(
                    value = fromDate, onValueChange = { fromDate = it.take(10) },
                    label = { Text("From date · YYYY-MM-DD") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("ai.recovery.from")
                )
                OutlinedTextField(
                    value = toDate, onValueChange = { toDate = it.take(10) },
                    label = { Text("To date · YYYY-MM-DD") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("ai.recovery.to")
                )
                OutlinedTextField(
                    value = strategyContext,
                    onValueChange = { strategyContext = it.take(1500) },
                    label = { Text("Strategy to review") },
                    minLines = 2, maxLines = 4,
                    modifier = Modifier.fillMaxWidth().testTag("ai.recovery.strategy")
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = ::recoverEodData,
                    enabled = symbol.isNotBlank() && fromDate.length == 10 && toDate.length == 10 && !briefLoading,
                    modifier = Modifier.fillMaxWidth().testTag("ai.recovery.run")
                ) {
                    Text(
                        if (briefLoading) "Retrieving and analysing…"
                        else if (canUseResearch) "Recover EOD data + analyse"
                        else "Recover adjusted EOD data"
                    )
                }
                if (briefError != null) Text(briefError!!, color = Coral, fontSize = 11.sp, lineHeight = 16.sp)
                Text(
                    "Fallback is adjusted EOD only, not intraday/live. If the source fails, recovery stops. If AI fails, the recovered bars and deterministic metrics are still returned with a warning. Quant never fabricates candles. Private personal research only.",
                    color = Muted, fontSize = 10.sp, lineHeight = 16.sp
                )
            }
        }
        if (brief != null) {
            val report = brief!!
            item {
                TerminalCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Eyebrow("Structurally validated · ${report.provider} · ${report.dataMode}")
                        StatusPill("EOD", Gold)
                    }
                    Spacer(Modifier.height(7.dp))
                    Text("${report.exchange}:${report.symbol}", color = MaterialTheme.colorScheme.onBackground, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("${report.barCount} daily bars · ${report.firstTradingDate} to ${report.latestTradingDate}", color = Muted, fontSize = 10.sp)
                    Spacer(Modifier.height(10.dp))
                    MetricTile("Latest adjusted EOD close", String.format(Locale.US, "₹%,.2f", report.evidence.latestClose), report.latestTradingDate, accent = Cyan)
                    Spacer(Modifier.height(8.dp))
                    AiIndicatorLine("SMA 20", report.evidence.sma20?.let { String.format(Locale.US, "₹%,.2f", it) } ?: "Insufficient history")
                    AiIndicatorLine("SMA 50", report.evidence.sma50?.let { String.format(Locale.US, "₹%,.2f", it) } ?: "Insufficient history")
                    AiIndicatorLine("RSI 14", report.evidence.rsi14?.let { String.format(Locale.US, "%.1f", it) } ?: "Unavailable")
                    AiIndicatorLine("ADX 14", report.evidence.adx14?.let { String.format(Locale.US, "%.1f", it) } ?: "Unavailable")
                    AiIndicatorLine("Donchian 20", report.evidence.donchianState)
                    AiIndicatorLine("Relative volume", report.evidence.relativeVolume20?.let { String.format(Locale.US, "%.2fx", it) } ?: "Unavailable")
                    AiIndicatorLine("Trend context", report.evidence.trendContext)
                }
            }
            item {
                TerminalCard {
                    val aiResult = report.ai
                    if (aiResult != null) {
                        Eyebrow("AI strategy review · ${aiResult.providerId} / ${aiResult.model}")
                        Spacer(Modifier.height(7.dp))
                        Text(aiResult.content, color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp, lineHeight = 19.sp)
                        if (aiResult.fallbackUsed) {
                            Spacer(Modifier.height(7.dp))
                            StatusPill("AI provider fallback used", Gold)
                        }
                    } else {
                        Eyebrow("Data recovered · AI review unavailable")
                        Spacer(Modifier.height(7.dp))
                        Text(
                            report.aiError ?: "The adjusted EOD data is available. Configure a backend AI provider and model to generate commentary.",
                            color = Gold, fontSize = 11.sp, lineHeight = 17.sp
                        )
                    }
                }
            }
            item {
                TerminalCard {
                    Eyebrow("Adjusted EOD bars · latest 8")
                    Spacer(Modifier.height(5.dp))
                    report.bars.takeLast(8).asReversed().forEach { bar ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(bar.date, color = Muted, fontSize = 9.sp)
                            Text(String.format(Locale.US, "O %.2f  H %.2f  L %.2f  C %.2f", bar.open, bar.high, bar.low, bar.close), color = MaterialTheme.colorScheme.onBackground, fontSize = 9.sp)
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(report.dataRights, color = Gold, fontSize = 10.sp, lineHeight = 16.sp)
                    report.limitations.forEach { limitation ->
                        Text("• $limitation", color = Muted, fontSize = 10.sp, lineHeight = 16.sp)
                    }
                }
            }
        }
    }
}


@Composable
private fun AiIndicatorLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Muted, fontSize = 11.sp)
        Text(value, color = MaterialTheme.colorScheme.onBackground, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}
