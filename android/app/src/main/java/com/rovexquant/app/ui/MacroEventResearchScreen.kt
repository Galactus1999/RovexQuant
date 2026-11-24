package com.rovexquant.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rovexquant.app.data.model.MacroResearchSummary
import com.rovexquant.app.data.model.EventStudyResult
import com.rovexquant.app.data.model.QuantSession
import com.rovexquant.app.data.repository.QuantDataRepository
import com.rovexquant.app.feature.research.MacroEventResearchViewModel
import java.util.Locale

@Composable
fun MacroEventResearchScreen(session: QuantSession, repository: QuantDataRepository) {
    val factory = remember(session, repository) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(MacroEventResearchViewModel::class.java))
                return MacroEventResearchViewModel(session, repository) as T
            }
        }
    }
    val model: MacroEventResearchViewModel = viewModel(
        key = "macro-event-${session.baseUrl}",
        factory = factory
    )
    val state by model.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("macro-event.screen"),
        contentPadding = PaddingValues(horizontal = 17.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        item {
            SectionHeading(
                "Macro regime · event attribution",
                "Macro & Event Research",
                "Official macro series and deterministic EOD event windows. Descriptive research, not causal claims or trading instructions."
            )
        }
        item {
            TerminalCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Eyebrow("India macro · World Bank")
                    StatusPill(if (state.macro != null) "RETRIEVED" else "ON DEMAND", Cyan)
                }
                Text("Macroeconomic indicators", color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "GDP growth, inflation, exports, current account and per-capita energy use · annual/official release cadence",
                    color = Muted, fontSize = 10.sp, lineHeight = 15.sp
                )
                Button(onClick = model::refreshMacro, enabled = !state.macroLoading,
                    modifier = Modifier.fillMaxWidth().testTag("macro.refresh")) {
                    Text(if (state.macroLoading) "Retrieving official series…" else "Refresh macro data")
                }
                if (state.macroLoading && state.macro == null) {
                    CircularProgressIndicator(color = Mint, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                state.macroError?.let { EmptyPanel("Macro retrieval unavailable", it, Coral) }
                state.macro?.let { MacroSummaryContent(it) }
            }
        }

        item {
            TerminalCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Eyebrow("P3 · independent exchange verification")
                    StatusPill(state.integrity?.status ?: if (state.integrityLoading) "CHECKING" else "NOT CHECKED", if (state.integrity?.status == "MATCHED") Mint else Gold)
                }
                Text("Market data integrity", color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Displays the latest stored comparison of TejHQ EOD bars against an authorized official NSE/BSE bhavcopy. This status check does not download exchange files or start a new reconciliation; a new run requires approved source access and an explicit reconciliation job.",
                    color = Muted, fontSize = 10.sp, lineHeight = 15.sp
                )
                Button(onClick = model::refreshIntegrity, enabled = !state.integrityLoading,
                    modifier = Modifier.fillMaxWidth().testTag("integrity.refresh")) {
                    Text(if (state.integrityLoading) "Checking source evidence…" else "Refresh integrity status")
                }
                state.integrityError?.let { EmptyPanel("Integrity status unavailable", it, Coral) }
                state.integrity?.latestReport?.let { report ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${report.tradingDate} · ${report.status}", color = Mint, fontSize = 10.sp)
                        Text("${report.matchedRows}/${report.officialRows} matched", color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "Value mismatches ${report.mismatchRows} · missing stored ${report.missingReferenceRows} · extra stored ${report.missingOfficialRows}",
                        color = if (report.status == "MATCHED") Mint else Gold, fontSize = 9.sp
                    )
                    report.differences.take(8).forEach { diff ->
                        Text(
                            "${diff.kind}: ${diff.symbol} ${diff.series} ${diff.field ?: ""} · official ${diff.officialValue ?: "—"} / stored ${diff.storedValue ?: "—"}",
                            color = Gold, fontSize = 9.sp, lineHeight = 13.sp
                        )
                    }
                    Text("Source: ${report.sourceUrl}", color = Muted, fontSize = 8.sp, lineHeight = 12.sp)
                } ?: Text(
                    if (state.integrityLoading) "No report loaded yet." else "No official reconciliation report is stored yet.",
                    color = Muted, fontSize = 10.sp
                )
                state.integrity?.derivedBackfills?.takeIf { it.isNotEmpty() }?.let { derivedBackfills ->
                    Spacer(Modifier.height(8.dp))
                    Eyebrow("Adjusted prices · corporate actions · symbol history")
                    derivedBackfills.forEach { derived ->
                        Text(
                            "${if (derived.year == 0) "ALL" else derived.year} · ${derived.datasetKind} · ${derived.status} · ${derived.rowsSeen} rows scanned",
                            color = if (derived.status == "SUCCEEDED") Mint else Gold, fontSize = 9.sp
                        )
                    }
                }
                state.integrity?.backfills?.takeIf { it.isNotEmpty() }?.let { backfills ->
                    Spacer(Modifier.height(8.dp))
                    Eyebrow("Historical Parquet imports")
                    backfills.forEach { backfill ->
                        Text(
                            "${backfill.year} · ${backfill.status} · ${backfill.rowsSeen} rows scanned · ${backfill.rowsUpserted} rows upserted",
                            color = if (backfill.status == "SUCCEEDED") Mint else Gold, fontSize = 9.sp
                        )
                    }
                }
            }
        }

        item {
            TerminalCard {
                Eyebrow("United States · FRED")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Cross-market macro context", color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.SemiBold)
                    StatusPill(
                        when {
                            state.macro == null -> "NOT CHECKED"
                            state.macro?.fredConfigured == true -> "CONFIGURED"
                            else -> "NOT CONFIGURED"
                        },
                        if (state.macro?.fredConfigured == true) Mint else Gold
                    )
                }
                Text(
                    when {
                        state.macro == null ->
                            "FRED configuration has not yet been checked. Tap Refresh macro data to query the backend and retrieve US yields, Fed funds, CPI and dollar observations."
                        state.macro?.fredConfigured == true ->
                            "FRED_API_KEY is configured on the backend. ${state.macro?.fredSeriesCount ?: 0} series have persisted observations; check provider status below for retrieval or persistence failures."
                        else ->
                            "The backend reports FRED_API_KEY is not configured. Add it to the deployed backend environment and redeploy."
                    },
                    color = Muted, fontSize = 10.sp, lineHeight = 15.sp
                )
                Text(
                    "AMFI NAV examples are mutual-fund NAV observations, not equity prices or stock-market returns.",
                    color = Gold, fontSize = 10.sp, lineHeight = 15.sp
                )
            }
        }
        item {
            TerminalCard {
                Eyebrow("Deterministic event study · persisted EOD bars")
                Text("Test a dated event against a stock and benchmark",
                    color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold)
                OutlinedTextField(value = state.symbol, onValueChange = model::setSymbol,
                    label = { Text("NSE symbol") }, placeholder = { Text("RELIANCE") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("event.symbol"))
                OutlinedTextField(value = state.eventDate, onValueChange = model::setEventDate,
                    label = { Text("Event date (YYYY-MM-DD)") }, placeholder = { Text("2026-10-15") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("event.date"))
                OutlinedTextField(value = state.benchmarkSymbol, onValueChange = model::setBenchmarkSymbol,
                    label = { Text("Benchmark symbol (optional)") }, placeholder = { Text("NIFTYBEES (ETF proxy)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("event.benchmark"))
                Text("Uses up to 10 pre-event and 10 post-event trading bars. Benchmark matching is by trading date.",
                    color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                Button(onClick = model::runEventStudy, enabled = !state.eventLoading,
                    modifier = Modifier.fillMaxWidth().testTag("event.run")) {
                    Text(if (state.eventLoading) "Calculating…" else "Run event-window study")
                }
                if (state.eventLoading && state.eventStudy == null) {
                    CircularProgressIndicator(color = Mint, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                state.eventError?.let { EmptyPanel("Event study unavailable", it, Coral) }
                state.eventStudy?.let { EventStudyContent(it) }
            }
        }
        item {
            TerminalCard {
                Eyebrow("Methodology and limits")
                Text(
                    "Event-window returns are descriptive. Even benchmark-adjusted results do not establish causation. Results depend on the event date supplied by the researcher, stored bar coverage, corporate-action treatment and benchmark alignment.",
                    color = Muted, fontSize = 10.sp, lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
private fun MacroSummaryContent(summary: MacroResearchSummary) {
    Text("Retrieved ${summary.retrievedAt}", color = Muted, fontSize = 9.sp)
    if (summary.persistedObservationCount > 0) {
        Spacer(Modifier.height(6.dp))
        Eyebrow("Durable macro archive · ${summary.persistedObservationCount} observations · RBI mirror series ${summary.rbiDbieSeriesCount}")
    }
    summary.providerStatuses.forEach { Text(it, color = if (it.contains("FAILED")) Coral else Muted, fontSize = 9.sp) }
    if (summary.worldBankSeries.isEmpty()) {
        Text("No World Bank observations returned.", color = Muted, fontSize = 10.sp)
    } else {
        summary.worldBankSeries.forEach { item ->
            Column(Modifier.fillMaxWidth().padding(top = 9.dp)) {
                Text(item.label, color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(item.latestPeriod ?: "No period", color = Muted, fontSize = 10.sp)
                    Text(
                        item.latestValue?.let { String.format(Locale.US, "%.2f %s", it, item.unit) } ?: "Unavailable",
                        color = if (item.latestValue == null) Gold else Mint,
                        fontSize = 11.sp, fontWeight = FontWeight.Bold
                    )
                }
                Text("${item.frequency} · ${item.source}", color = Muted, fontSize = 9.sp)
            }
        }
    }
    if (summary.amfiNavCount > 0) {
        Spacer(Modifier.height(10.dp))
        Eyebrow("AMFI daily NAV · ${summary.amfiNavCount} observations")
        summary.amfiExamples.take(5).forEach { item ->
            Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(item.schemeName, color = MaterialTheme.colorScheme.onBackground, fontSize = 10.sp)
                    Text(item.navDate, color = Muted, fontSize = 9.sp)
                }
                Text(String.format(Locale.US, "%.4f", item.nav), color = Mint, fontSize = 11.sp)
            }
        }
    }
    if (summary.providerErrors.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        summary.providerErrors.forEach { Text("• $it", color = Gold, fontSize = 9.sp, lineHeight = 13.sp) }
    }
}

@Composable
private fun EventStudyContent(result: EventStudyResult) {
    Spacer(Modifier.height(10.dp))
    Eyebrow("${result.exchange} · ${result.symbol} · ${result.eventDate}")
    Text("${result.anchorDate} → ${result.lastPostEventDate} · ${result.postBarsUsed} post-event bars",
        color = Muted, fontSize = 9.sp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EventMetric("Pre-event", result.preEventReturn, Modifier.weight(1f))
        EventMetric("Post-event", result.cumulativeReturn, Modifier.weight(1f))
    }
    if (result.marketAdjustedReturn != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EventMetric("Market-adjusted", result.marketAdjustedReturn, Modifier.weight(1f))
            EventMetric("Cumulative abnormal", result.cumulativeAbnormalReturn, Modifier.weight(1f))
        }
        Text("Benchmark ${result.benchmarkSymbol ?: "—"} · ${result.benchmarkMatches} aligned observations · ETF proxy where used",
            color = Muted, fontSize = 9.sp)
    } else {
        Text("No benchmark-relative result was calculated.", color = Gold, fontSize = 10.sp)
    }
    Text("Method: ${result.method} · Price basis: ${result.priceBasis}", color = Muted, fontSize = 9.sp)
    if (result.isin != null) {
        Text(
            "Point-in-time identity: ${result.exchange}:${result.symbolAsOfAnchor ?: result.symbol} · ISIN ${result.isin} · valid from ${result.symbolValidFrom ?: "—"} to ${result.symbolValidTo ?: "current"}",
            color = Muted, fontSize = 9.sp, lineHeight = 13.sp
        )
        Text("Identity source: ${result.symbolHistoryProvider ?: "historical symbol archive"}", color = Muted, fontSize = 8.sp)
    }
    if (result.corporateActions.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Eyebrow("Corporate actions within ±60 days")
        result.corporateActions.forEach { action ->
            Text(
                "${action.exDate} · ${action.actionType.uppercase()} · ${action.rawSubject.ifBlank { action.companyName }}",
                color = Gold, fontSize = 9.sp, lineHeight = 13.sp
            )
        }
    } else {
        Text("No corporate actions found in the stored ±60-day window.", color = Muted, fontSize = 9.sp)
    }
    result.limitations.forEach { Text("• $it", color = Muted, fontSize = 9.sp, lineHeight = 13.sp) }
}

@Composable
private fun EventMetric(label: String, value: Double?, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 8.dp)) {
        Text(label, color = Muted, fontSize = 9.sp)
        Text(
            value?.let { String.format(Locale.US, "%+.2f%%", it * 100.0) } ?: "—",
            color = value?.let { if (it >= 0) Mint else Coral } ?: Muted,
            fontSize = 14.sp, fontWeight = FontWeight.Bold
        )
    }
}
