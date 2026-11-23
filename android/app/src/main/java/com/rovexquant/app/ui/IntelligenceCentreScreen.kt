package com.rovexquant.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rovexquant.app.data.model.MarketEvent
import com.rovexquant.app.data.model.MacroResearchSummary
import com.rovexquant.app.data.model.EventStudyResult
import com.rovexquant.app.data.model.QuantSession
import com.rovexquant.app.data.repository.QuantDataRepository
import com.rovexquant.app.feature.intelligence.IntelligenceCentreViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun IntelligenceCentreScreen(session: QuantSession, repository: QuantDataRepository) {
    val factory = remember(session, repository) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(IntelligenceCentreViewModel::class.java))
                return IntelligenceCentreViewModel(session, repository) as T
            }
        }
    }
    val model: IntelligenceCentreViewModel = viewModel(
        key = "intelligence-${session.baseUrl}",
        factory = factory
    )
    val state by model.uiState.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("India OR oil OR war OR technology OR central bank") }
    var category by rememberSaveable { mutableStateOf("ALL") }
    val uriHandler = LocalUriHandler.current

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("intelligence.screen"),
        contentPadding = PaddingValues(start = 17.dp, end = 17.dp, top = 8.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionHeading(
                "Research intelligence · evidence first",
                "AI Intelligence Centre",
                "Global developments translated into transparent Indian-sector exposure hypotheses. Chat is separate and optional."
            )
        }
        item {
            Column(Modifier.testTag("market.data-health")) {
                TerminalCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("Market data pipeline")
                    StatusPill(
                        state.dataHealth?.overallStatus ?: if (state.dataHealthLoading) "CHECKING" else "UNAVAILABLE",
                        when (state.dataHealth?.overallStatus) {
                            "HEALTHY" -> Mint
                            "DEGRADED" -> Coral
                            else -> Gold
                        }
                    )
                }
                Text(
                    "NSE · ${state.dataHealth?.marketDataMode ?: "END_OF_DAY_ONLY"}",
                    color = Muted, fontSize = 9.sp
                )
                state.dataHealth?.let { health ->
                    val eod = health.eod
                    Text(
                        "EOD: ${eod.status} · ${eod.records ?: 0} rows · ${eod.freshness} · ${eod.updatedAt ?: "no successful import"}",
                        color = MaterialTheme.colorScheme.onBackground, fontSize = 10.sp, lineHeight = 15.sp
                    )
                    health.macroProviders.forEach { item ->
                        Text(
                            "Macro · ${item.name}: ${item.status} · ${item.records ?: 0} rows · ${item.freshness}",
                            color = if (item.status == "FAILED") Coral else Muted, fontSize = 9.sp
                        )
                    }
                    Text(
                        "Intelligence: ${health.intelligence.status} · ${health.intelligence.records ?: 0} events · ${health.intelligence.freshness}",
                        color = if (health.intelligence.status == "FAILED") Coral else Muted, fontSize = 9.sp
                    )
                    health.derivedBackfills.forEach { item ->
                        Text(
                            "History · ${item.name}: ${item.status} · ${item.records ?: 0} rows",
                            color = if (item.status == "FAILED") Coral else Muted, fontSize = 9.sp
                        )
                    }
                    Text(
                        "Official reconciliation: ${health.reconciliation.status} · ${health.reconciliation.detail ?: "No report yet"}",
                        color = if (health.reconciliation.status == "MISMATCHED") Coral else Muted,
                        fontSize = 9.sp, lineHeight = 14.sp
                    )
                }
                state.dataHealthError?.let { EmptyPanel("Data health unavailable", it, Coral) }
                Button(
                    onClick = model::refreshHealth,
                    enabled = !state.dataHealthLoading,
                    modifier = Modifier.fillMaxWidth().testTag("market.data-health.refresh")
                ) {
                    Text(if (state.dataHealthLoading) "Checking data sources…" else "Refresh data health")
                }
                Text(
                    "EOD and delayed research data only. A successful fetch is not the same as official-source reconciliation.",
                    color = Muted, fontSize = 9.sp, lineHeight = 14.sp
                )
                }
            }
        }

        item {
            TerminalCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("Global event discovery")
                    StatusPill(state.feed?.source ?: "GDELT", Cyan)
                }
                Spacer(Modifier.height(7.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(160) },
                    label = { Text("Search global developments") },
                    placeholder = { Text("India, oil, conflict, AI, central banks…") },
                    modifier = Modifier.fillMaxWidth().testTag("intelligence.query"),
                    minLines = 1,
                    maxLines = 2
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("1d", "3d", "7d").forEach { span ->
                        FilterChip(
                            selected = state.timespan == span,
                            onClick = { model.refresh(query, span) },
                            label = { Text(span) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { model.refresh(query, state.timespan) },
                    enabled = !state.loading && query.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("intelligence.refresh")
                ) {
                    Text(if (state.loading) "Retrieving developments…" else "Refresh intelligence")
                }
                Text(
                    "On-demand retrieval · global news discovery, not a live market feed.",
                    color = Muted, fontSize = 10.sp, lineHeight = 15.sp
                )
            }
        }
        if (state.loading && state.feed == null) {
            item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Mint) } }
        }
        if (state.error != null) {
            item { EmptyPanel("Intelligence retrieval unavailable", state.error ?: "Try again later.", Coral) }
        }
        state.feed?.let { feed ->
            item {
                TerminalCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Eyebrow("Evidence inventory")
                            Text(
                                "${feed.eventCount} distinct events",
                                modifier = Modifier.testTag("intelligence.feed.ready"),
                                color = MaterialTheme.colorScheme.onBackground,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text("${feed.articleCount} source articles · ${feed.timespan} window", color = Muted, fontSize = 10.sp)
                            Text("Archive ${feed.archiveStatus} · NSE master ${feed.issuerMasterStatus} · ${feed.mappedEventCount} mapped candidates", color = Muted, fontSize = 9.sp)
                        }
                        StatusPill("On demand", Gold)
                    }
                    Text("Retrieved ${feed.retrievedAt}", color = Muted, fontSize = 9.sp)
                }
            }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("ALL", "WAR_CONFLICT", "MONETARY_POLICY", "COMMODITY_SUPPLY", "TECHNOLOGY_INNOVATION", "GEOPOLITICS_TRADE", "REGULATION_POLICY").forEach { item ->
                        FilterChip(
                            selected = category == item,
                            onClick = { category = item },
                            label = { Text(item.replace('_', ' ').take(14), fontSize = 9.sp) }
                        )
                    }
                }
            }
            val visibleEvents = feed.events.filter { category == "ALL" || it.eventCategory == category }
            if (visibleEvents.isEmpty()) {
                item { EmptyPanel("No matching events", "No event in this retrieval window matched the selected category. This is not evidence that the topic has no market impact.") }
            } else {
                items(visibleEvents, key = { it.eventId }) { event ->
                    IntelligenceEventCard(event = event, onOpenSource = { url ->
                        runCatching { uriHandler.openUri(url) }
                    })
                }
            }
            item {
                TerminalCard {
                    Eyebrow("Coverage and interpretation limits")
                    feed.limitations.forEach { limitation ->
                        Text("• $limitation", color = Muted, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        item { MacroResearchToolPanel(session, repository) }
        item { EodEventStudyToolPanel(session, repository) }
    }
}

@Composable
private fun MacroResearchToolPanel(
    session: QuantSession,
    repository: QuantDataRepository
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<MacroResearchSummary?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    TerminalCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Macro research · World Bank / FRED / AMFI")
            StatusPill(if (summary == null) "Not loaded" else "Retrieved", if (summary == null) Gold else Mint)
        }
        Text(
            "Pulls actual provider observations through the authenticated backend. Annual macro series, daily/monthly FRED series and mutual-fund NAV remain separate data types.",
            color = Muted, fontSize = 10.sp, lineHeight = 15.sp
        )
        Button(
            onClick = {
                if (loading) return@Button
                loading = true
                error = null
                scope.launch {
                    try {
                        summary = repository.fetchMacroResearch(session)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        error = failure.message ?: "Macro research request failed."
                    } finally {
                        loading = false
                    }
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth().testTag("intelligence.macro.refresh")
        ) {
            if (loading) CircularProgressIndicator(modifier = Modifier.size(16.dp)) else Text("Fetch macro observations")
        }
        error?.let { Text(it, color = Coral, fontSize = 10.sp) }
        summary?.let { data ->
            Text("Retrieved ${data.retrievedAt.ifBlank { "timestamp not supplied" }}", color = Muted, fontSize = 9.sp)
            if (data.worldBankSeries.isEmpty()) {
                Text("World Bank observations unavailable in this response.", color = Gold, fontSize = 10.sp)
            }
            data.worldBankSeries.forEach { series ->
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(series.label, color = MaterialTheme.colorScheme.onBackground, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${series.latestValue?.let { "%.3f".format(java.util.Locale.US, it) } ?: "No observation"} ${series.unit} · ${series.latestPeriod ?: "period unavailable"} · ${series.frequency}",
                        color = Cyan, fontSize = 10.sp
                    )
                    Text("Source: ${series.source}", color = Muted, fontSize = 9.sp)
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(
                "FRED: ${if (data.fredConfigured) "${data.fredSeriesCount} series returned" else "not configured on backend"} · AMFI: ${data.amfiNavCount} NAV records (mutual funds, not equity quotes)",
                color = Muted, fontSize = 9.sp, lineHeight = 14.sp
            )
            data.amfiExamples.take(3).forEach { nav ->
                Text("${nav.schemeName}: ₹${"%.4f".format(java.util.Locale.US, nav.nav)} · ${nav.navDate}", color = Muted, fontSize = 9.sp)
            }
            data.providerErrors.forEach { issue ->
                Text("Partial source failure: $issue", color = Gold, fontSize = 9.sp, lineHeight = 13.sp)
            }
        }
    }
}

@Composable
private fun EodEventStudyToolPanel(
    session: QuantSession,
    repository: QuantDataRepository
) {
    val scope = rememberCoroutineScope()
    var symbol by rememberSaveable { mutableStateOf("RELIANCE") }
    var eventDate by rememberSaveable { mutableStateOf("") }
    var benchmark by rememberSaveable { mutableStateOf("NIFTY") }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<EventStudyResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    TerminalCard {
        Eyebrow("Historical event study · persisted EOD bars")
        Text(
            "Measures pre/post-event price response from the stored EOD history. This is descriptive event-window analysis, not proof that the event caused the return.",
            color = Muted, fontSize = 10.sp, lineHeight = 15.sp
        )
        OutlinedTextField(
            value = symbol,
            onValueChange = { symbol = it.uppercase().filter { ch -> ch.isLetterOrDigit() || ch in "&._-" }.take(40) },
            label = { Text("NSE symbol") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("intelligence.event.symbol")
        )
        OutlinedTextField(
            value = eventDate,
            onValueChange = { eventDate = it.take(10) },
            label = { Text("Event date · YYYY-MM-DD") },
            placeholder = { Text("2026-10-15") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("intelligence.event.date")
        )
        OutlinedTextField(
            value = benchmark,
            onValueChange = { benchmark = it.uppercase().filter { ch -> ch.isLetterOrDigit() || ch in "&._-" }.take(40) },
            label = { Text("Benchmark symbol · optional") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                if (loading) return@Button
                loading = true
                error = null
                result = null
                scope.launch {
                    try {
                        result = repository.fetchEventStudy(
                            session, symbol.trim(), eventDate.trim(), benchmark.trim().ifBlank { null }
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        error = failure.message ?: "Event study failed."
                    } finally {
                        loading = false
                    }
                }
            },
            enabled = !loading && symbol.isNotBlank() && eventDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")),
            modifier = Modifier.fillMaxWidth().testTag("intelligence.event.run")
        ) {
            if (loading) CircularProgressIndicator(modifier = Modifier.size(16.dp)) else Text("Run event-window analysis")
        }
        error?.let { Text(it, color = Coral, fontSize = 10.sp, lineHeight = 15.sp) }
        result?.let { study ->
            Spacer(Modifier.height(8.dp))
            Text("${study.exchange}:${study.symbol} · event ${study.eventDate}", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("Anchor ${study.anchorDate} · post window ${study.firstPostEventDate} to ${study.lastPostEventDate} · ${study.postBarsUsed} bars", color = Muted, fontSize = 9.sp, lineHeight = 14.sp)
            listOf(
                "Pre-event return" to study.preEventReturn,
                "Post-event cumulative return" to study.cumulativeReturn,
                "Benchmark-adjusted return" to study.marketAdjustedReturn,
                "Cumulative abnormal return" to study.cumulativeAbnormalReturn
            ).forEach { (label, value) ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, color = Muted, fontSize = 10.sp)
                    Text(
                        value?.let { "%+.2f%%".format(java.util.Locale.US, it * 100.0) } ?: "Unavailable",
                        color = value?.let { if (it >= 0) Mint else Coral } ?: Gold,
                        fontSize = 10.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
            Text("Benchmark: ${study.benchmarkSymbol ?: "not used"} · matched observations: ${study.benchmarkMatches}", color = Muted, fontSize = 9.sp)
            Text("Method: ${study.method}", color = Muted, fontSize = 9.sp, lineHeight = 14.sp)
            study.limitations.forEach { limitation ->
                Text("• $limitation", color = Gold, fontSize = 9.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun IntelligenceEventCard(event: MarketEvent, onOpenSource: (String) -> Unit) {
    TerminalCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(event.eventCategory.replace('_', ' '))
            StatusPill("${event.independentSourceCount} sources", if (event.independentSourceCount > 1) Mint else Gold)
        }
        Spacer(Modifier.height(6.dp))
        Text(event.headline, color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(7.dp))
        Text(
            "Rule-match score ${(event.classificationConfidence * 100).toInt()}% · heuristic, not a probability",
            color = Muted, fontSize = 9.sp
        )
        if (event.sectorImpacts.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Eyebrow("Potential sector transmission")
            event.sectorImpacts.forEach { impact ->
                Column(Modifier.fillMaxWidth().padding(top = 7.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(impact.sector.replace('_', ' '), color = MaterialTheme.colorScheme.onBackground, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(impact.direction.replace('_', ' '), color = when {
                            impact.direction.contains("NEGATIVE") -> Coral
                            impact.direction.contains("SUPPORTIVE") -> Mint
                            else -> Gold
                        }, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(impact.mechanism, color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                }
            }
        } else {
            Text("No deterministic sector mapping exists for this headline.", color = Muted, fontSize = 10.sp)
        }
        if (event.issuerMappings.isNotEmpty()) {
            Spacer(Modifier.height(9.dp))
            Eyebrow("Potential NSE issuer candidates · verify manually")
            event.issuerMappings.forEach { mapping ->
                Column(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                    Text(
                        "${mapping.exchange}:${mapping.symbol} · ${mapping.companyName}",
                        color = MaterialTheme.colorScheme.onBackground, fontSize = 10.sp, fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "${(mapping.confidence * 100).toInt()}% heuristic · ${mapping.mappingMethod.replace('_', ' ')} · ${mapping.mappingScope}",
                        color = Gold, fontSize = 9.sp
                    )
                    Text(mapping.evidence, color = Muted, fontSize = 9.sp, lineHeight = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(event.stockMappingNote, color = Gold, fontSize = 10.sp, lineHeight = 15.sp)
        event.sources.forEach { source ->
            if (source.url.startsWith("https://") || source.url.startsWith("http://")) {
                Text(
                    "↗ ${source.domain.ifBlank { "Open original source" }} · seen ${source.seenAt}",
                    color = Cyan, fontSize = 10.sp,
                    modifier = Modifier.padding(top = 7.dp).clickable { onOpenSource(source.url) }
                )
            }
        }
    }
}
