package com.rovexquant.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
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
                "RESEARCH",
                "Macro & Event Research",
                ""
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
                Button(onClick = model::refreshMacro, enabled = !state.macroLoading,
                    modifier = Modifier.fillMaxWidth().testTag("macro.refresh")) {
                    Text(if (state.macroLoading) "Loading…" else "Refresh")
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
                Button(onClick = model::refreshIntegrity, enabled = !state.integrityLoading,
                    modifier = Modifier.fillMaxWidth().testTag("integrity.refresh")) {
                    Text(if (state.integrityLoading) "Checking…" else "Refresh status")
                }
                state.integrityError?.let { EmptyPanel("Integrity status unavailable", it, Coral) }
                state.integrity?.latestReport?.let { report ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${report.tradingDate} · ${report.status}", color = Mint, fontSize = 10.sp)
                        Text("${report.matchedRows}/${report.officialRows} matched", color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    if (report.mismatchRows > 0 || report.missingReferenceRows > 0 || report.missingOfficialRows > 0) {
                        Text("${report.mismatchRows} mismatches · ${report.missingReferenceRows} missing · ${report.missingOfficialRows} extra",
                            color = Gold, fontSize = 10.sp)
                    }
                } ?: Text(
                    if (state.integrityLoading) "No report loaded yet." else "No official reconciliation report is stored yet.",
                    color = Muted, fontSize = 10.sp
                )
            }
        }

        item {
            TerminalCard {
                Eyebrow("FRED")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("US macro data", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)
                    StatusPill(
                        when {
                            state.macro?.fredConfigured == null -> "UNKNOWN"
                            state.macro?.fredConfigured == true -> "READY"
                            else -> "SETUP NEEDED"
                        },
                        if (state.macro?.fredConfigured == true) Mint else Gold
                    )
                }
                if (state.macro?.fredConfigured == true) {
                    Text("${state.macro?.fredSeriesCount ?: 0} series available", color = Muted, fontSize = 10.sp)
                }
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
                EventDatePickerField(
                    value = state.eventDate,
                    onValueChange = model::setEventDate,
                    modifier = Modifier.fillMaxWidth().testTag("event.date")
                )
                OutlinedTextField(value = state.benchmarkSymbol, onValueChange = model::setBenchmarkSymbol,
                    label = { Text("Benchmark symbol (optional)") }, placeholder = { Text("NIFTYBEES (ETF proxy)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("event.benchmark"))
                Button(onClick = model::runEventStudy, enabled = !state.eventLoading,
                    modifier = Modifier.fillMaxWidth().testTag("event.run")) {
                    Text(if (state.eventLoading) "Running…" else "Run study")
                }
                if (state.eventLoading && state.eventStudy == null) {
                    CircularProgressIndicator(color = Mint, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                state.eventError?.let { EmptyPanel("Event study unavailable", it, Coral) }
                state.eventStudy?.let { EventStudyContent(it) }
            }
        }
    }
}

@Composable
private fun MacroSummaryContent(summary: MacroResearchSummary) {
    if (summary.persistedObservationCount > 0) {
        Text("${summary.persistedObservationCount} observations saved", color = Muted, fontSize = 9.sp)
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
        summary.providerErrors.take(3).forEach { issue ->
            Text(issue.substringBefore(":").replace('_', ' ') + " unavailable", color = Gold, fontSize = 9.sp)
        }
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventDatePickerField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var show by remember { mutableStateOf(false) }
    val selected = remember(value) { runCatching { LocalDate.parse(value) }.getOrNull() }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = selected?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
    )
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        modifier = modifier,
        label = { Text("Event date") },
        placeholder = { Text("Choose date") },
        trailingIcon = {
            TextButton(onClick = { show = true }) { Text("Calendar") }
        },
        singleLine = true
    )
    if (show) DatePickerDialog(
        onDismissRequest = { show = false },
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { millis ->
                    onValueChange(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString())
                }
                show = false
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } }
    ) { DatePicker(state = state) }
}
