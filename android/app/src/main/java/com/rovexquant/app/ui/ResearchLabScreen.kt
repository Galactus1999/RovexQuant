package com.rovexquant.app.ui

import com.rovexquant.app.data.model.QuantSession
import com.rovexquant.app.data.local.TejHqOhlcvStore
import com.rovexquant.app.data.local.TejHqOhlcvSyncManager
import com.rovexquant.app.data.model.MacroResearchSummary
import com.rovexquant.app.data.model.EventStudyResult
import com.rovexquant.app.data.repository.QuantDataRepository

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rovexquant.app.domain.OhlcvCsvResearch
import com.rovexquant.app.domain.OhlcvResearchSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.time.LocalDate
import java.time.ZoneId

private const val MAX_CSV_BYTES = 20 * 1024 * 1024

@Composable
fun ResearchLabScreen(session: QuantSession? = null, repository: QuantDataRepository? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<OhlcvResearchSummary?>(null) }
    var datasetName by remember { mutableStateOf("No dataset loaded") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var macroSummary by remember { mutableStateOf<MacroResearchSummary?>(null) }
    var macroLoading by remember { mutableStateOf(false) }
    var macroError by remember { mutableStateOf<String?>(null) }
    var eventSymbol by rememberSaveable { mutableStateOf("RELIANCE") }
    var eventDate by rememberSaveable { mutableStateOf("") }
    var benchmarkSymbol by rememberSaveable { mutableStateOf("") }
    var eventResult by remember { mutableStateOf<EventStudyResult?>(null) }
    var eventLoading by remember { mutableStateOf(false) }
    var eventError by remember { mutableStateOf<String?>(null) }
    val savedOhlcvSettings = remember(context) { TejHqOhlcvStore.settings(context) }
    var ohlcvExchange by rememberSaveable { mutableStateOf(savedOhlcvSettings.exchange.uppercase()) }
    var ohlcvSymbol by rememberSaveable { mutableStateOf(savedOhlcvSettings.symbol) }
    var ohlcvFrom by rememberSaveable { mutableStateOf(savedOhlcvSettings.fromDate) }
    var ohlcvTo by rememberSaveable { mutableStateOf(LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()) }
    var ohlcvAutoSync by rememberSaveable { mutableStateOf(savedOhlcvSettings.autoSync) }
    var ohlcvLoading by remember { mutableStateOf(false) }
    var ohlcvError by remember { mutableStateOf<String?>(null) }
    var ohlcvStatus by remember { mutableStateOf<String?>(null) }

    fun runEventStudy() {
        if (session == null || repository == null || eventLoading) return
        eventLoading = true
        eventError = null
        scope.launch {
            try {
                eventResult = repository.fetchEventStudy(
                    session, eventSymbol.trim(), eventDate.trim(),
                    benchmarkSymbol.trim().takeIf { it.isNotEmpty() }
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                eventError = failure.message ?: "Event study could not be calculated."
            } finally {
                eventLoading = false
            }
        }
    }

    fun refreshMacroResearch() {
        if (session == null || repository == null || macroLoading) return
        macroLoading = true
        macroError = null
        scope.launch {
            try {
                macroSummary = repository.fetchMacroResearch(session)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                macroError = failure.message ?: "Macro research providers are unavailable."
            } finally {
                macroLoading = false
            }
        }
    }

    LaunchedEffect(session, repository) {
        refreshMacroResearch()
    }

    fun loadSelectedFile(uri: Uri) {
        loading = true
        error = null
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val csv = context.contentResolver.openInputStream(uri)?.use { it.readUtf8Limited() }
                        ?: throw IllegalArgumentException("Could not open the selected file")
                    val bars = OhlcvCsvResearch.parse(csv)
                    val temporary = File(context.filesDir, OhlcvCsvResearch.STORAGE_FILE_NAME + ".tmp")
                    val destination = File(context.filesDir, OhlcvCsvResearch.STORAGE_FILE_NAME)
                    temporary.writeText(csv, Charsets.UTF_8)
                    try {
                        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                    OhlcvCsvResearch.summarize(bars)
                }
                summary = loaded
                datasetName = uri.lastPathSegment?.substringAfterLast('/') ?: "Imported CSV"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Could not import this CSV"
            } finally {
                loading = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadSelectedFile(uri)
    }

    LaunchedEffect(context, repository) {
        if (context.fileList().contains(OhlcvCsvResearch.STORAGE_FILE_NAME)) {
            loading = true
            try {
                val restored = withContext(Dispatchers.IO) {
                    context.openFileInput(OhlcvCsvResearch.STORAGE_FILE_NAME).bufferedReader().use { reader ->
                        OhlcvCsvResearch.summarize(OhlcvCsvResearch.parse(reader.readText()))
                    }
                }
                summary = restored
                val settings = TejHqOhlcvStore.settings(context)
                datasetName = "${settings.symbol} · ${settings.exchange.uppercase()} · saved locally"
                if (repository != null) {
                    try {
                        val updated = TejHqOhlcvSyncManager.refreshIfDue(context, repository)
                        if (updated != null) {
                            summary = OhlcvCsvResearch.summarize(updated)
                            ohlcvStatus = "Updated automatically · ${updated.size} bars"
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        ohlcvError = failure.message ?: "Automatic OHLCV refresh failed"
                    }
                }
            } catch (failure: Exception) {
                error = failure.message ?: "Saved dataset could not be read"
            } finally {
                loading = false
            }
        }
    }

    fun fetchOhlcvHistory(incremental: Boolean) {
        val activeRepository = repository ?: run {
            ohlcvError = "Connect to the Quant workspace to use the in-app extractor."
            return
        }
        val from = runCatching { LocalDate.parse(ohlcvFrom) }.getOrNull()
        val to = runCatching { LocalDate.parse(ohlcvTo) }.getOrNull()
        if (from == null || to == null || from.isAfter(to)) {
            ohlcvError = "Choose a valid date range."
            return
        }
        if (ohlcvLoading) return
        ohlcvLoading = true
        ohlcvError = null
        ohlcvStatus = null
        scope.launch {
            try {
                val bars = TejHqOhlcvSyncManager.fetchAndSave(
                    context, activeRepository, ohlcvExchange, ohlcvSymbol, from, to,
                    incremental = incremental
                )
                summary = OhlcvCsvResearch.summarize(bars)
                datasetName = "${ohlcvSymbol.uppercase()} · ${ohlcvExchange.uppercase()} · TejHQ"
                ohlcvStatus = "${bars.size} bars saved on this device"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                ohlcvError = failure.message ?: "OHLCV extraction failed"
            } finally {
                ohlcvLoading = false
            }
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize().testTag("research.lab.list"), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionHeading("RESEARCH", "Research lab.", "") }
        if (session != null && repository != null) {
            item { NseMcpResearchPanel(session, repository) }
            item {
                TerminalCard {
                    Eyebrow("Event study")
                    OutlinedTextField(
                        value = eventSymbol,
                        onValueChange = { eventSymbol = it.take(40).uppercase() },
                        label = { Text("NSE symbol") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    EventDatePickerField(
                        value = eventDate,
                        onValueChange = { eventDate = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = benchmarkSymbol,
                        onValueChange = { benchmarkSymbol = it.take(40).uppercase() },
                        label = { Text("Benchmark · optional") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(
                        onClick = { runEventStudy() },
                        enabled = !eventLoading && eventDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && eventSymbol.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (eventLoading) "Calculating…" else "Calculate event window") }
                    eventError?.let { Text(it, color = Coral, fontSize = 10.sp) }
                    eventResult?.let { result ->
                        IndicatorLine("Pre-event return", result.preEventReturn?.let { String.format(Locale.US, "%.2f%%", it * 100) } ?: "Unavailable")
                        IndicatorLine("Post-event return", result.cumulativeReturn?.let { String.format(Locale.US, "%.2f%%", it * 100) } ?: "Unavailable")
                        IndicatorLine("Market-adjusted", result.marketAdjustedReturn?.let { String.format(Locale.US, "%.2f%%", it * 100) } ?: "No benchmark match")
                        Text("${result.symbol} · ${result.anchorDate} → ${result.lastPostEventDate} · ${result.postBarsUsed} bars", color = Muted, fontSize = 9.sp)
                        result.limitations.forEach { Text("• $it", color = Muted, fontSize = 9.sp) }
                    }
                }
            }
            item {
                TerminalCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Eyebrow("Macro & fund research · delayed official data")
                        TextButton(onClick = { refreshMacroResearch() }, enabled = !macroLoading) {
                            Text(if (macroLoading) "Loading…" else "Refresh")
                        }
                    }
                    if (macroLoading && macroSummary == null) {
                        Text("Retrieving World Bank, AMFI and FRED status…", color = Muted, fontSize = 11.sp)
                    }
                    macroError?.let { Text(it, color = Coral, fontSize = 11.sp) }
                    macroSummary?.let { report ->
                        Text("World Bank · India", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        report.worldBankSeries.forEach { series ->
                            val value = series.latestValue?.let { String.format(Locale.US, "%.2f", it) } ?: "Unavailable"
                            IndicatorLine(series.label, "$value ${series.unit} · ${series.latestPeriod ?: "period n/a"}")
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("AMFI mutual-fund NAV · ${report.amfiNavCount} matches", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        report.amfiExamples.take(3).forEach { nav ->
                            IndicatorLine(nav.schemeName, String.format(Locale.US, "%.4f · %s", nav.nav, nav.navDate))
                        }
                        Text(
                            when (report.fredConfigured) {
                                true -> "FRED configured · ${report.fredSeriesCount} series returned"
                                false -> "FRED not configured · add FRED_API_KEY as a backend secret"
                                null -> "FRED configuration status unavailable · refresh backend status"
                            },
                            color = if (report.fredConfigured == true) Mint else Gold, fontSize = 10.sp
                        )
                        if (report.providerErrors.isNotEmpty()) {
                            report.providerErrors.forEach { Text(it, color = Gold, fontSize = 9.sp) }
                        }
                        Text("Retrieved ${report.retrievedAt}. Annual macro observations and daily fund NAVs are not equity quotes or intraday signals.", color = Muted, fontSize = 9.sp, lineHeight = 14.sp)
                    }
                }
            }
        }
        item {
            TerminalCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Eyebrow("Automatic OHLCV")
                    StatusPill(if (ohlcvAutoSync) "AUTO ON" else "AUTO OFF", if (ohlcvAutoSync) Mint else Gold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = ohlcvExchange,
                        onValueChange = { ohlcvExchange = it.take(3).uppercase() },
                        label = { Text("Exchange") },
                        singleLine = true,
                        modifier = Modifier.weight(0.7f).testTag("ohlcv.exchange")
                    )
                    OutlinedTextField(
                        value = ohlcvSymbol,
                        onValueChange = { ohlcvSymbol = it.take(30).uppercase() },
                        label = { Text("Symbol") },
                        placeholder = { Text("RELIANCE") },
                        singleLine = true,
                        modifier = Modifier.weight(1.3f).testTag("ohlcv.symbol")
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = ohlcvFrom,
                        onValueChange = { ohlcvFrom = it.take(10) },
                        label = { Text("From · YYYY-MM-DD") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("ohlcv.from")
                    )
                    OutlinedTextField(
                        value = ohlcvTo,
                        onValueChange = { ohlcvTo = it.take(10) },
                        label = { Text("To · YYYY-MM-DD") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("ohlcv.to")
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Refresh this dataset automatically when Research Lab opens", color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = ohlcvAutoSync,
                        onCheckedChange = {
                            ohlcvAutoSync = it
                            val old = TejHqOhlcvStore.settings(context)
                            TejHqOhlcvStore.saveSettings(context, old.copy(
                                exchange = ohlcvExchange.lowercase(),
                                symbol = ohlcvSymbol.uppercase(),
                                fromDate = ohlcvFrom,
                                autoSync = it
                            ))
                        },
                        modifier = Modifier.testTag("ohlcv.autoSync")
                    )
                }
                Button(
                    onClick = { fetchOhlcvHistory(incremental = false) },
                    enabled = !ohlcvLoading && repository != null && ohlcvSymbol.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("ohlcv.fetch")
                ) {
                    Text(if (ohlcvLoading) "Fetching…" else "Fetch OHLCV history")
                }
                if (ohlcvLoading) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.width(22.dp).height(22.dp)
                    )
                }
                ohlcvStatus?.let { Text(it, color = Mint, fontSize = 10.sp) }
                ohlcvError?.let { Text(it, color = Coral, fontSize = 10.sp) }
            }
        }
        item {
            TerminalCard {
                Eyebrow("Offline dataset")
                Spacer(Modifier.height(7.dp))
                Text(datasetName, color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text("CSV columns required: timestamp, open, high, low, close, volume. Timestamps must include an ISO-8601 timezone offset. Maximum file size: 20 MB.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { picker.launch(arrayOf("text/*", "text/csv", "application/csv", "application/vnd.ms-excel", "*/*")) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                    Text(if (loading) "Importing…" else "Import OHLCV CSV")
                }
                if (summary != null) {
                    TextButton(onClick = {
                        context.deleteFile(OhlcvCsvResearch.STORAGE_FILE_NAME)
                        summary = null
                        datasetName = "No dataset loaded"
                        error = null
                    }, modifier = Modifier.fillMaxWidth()) { Text("Clear saved dataset") }
                }
                if (error != null) {
                    Spacer(Modifier.height(5.dp))
                    Text(error!!, color = Coral, fontSize = 11.sp, lineHeight = 17.sp)
                }
            }
        }
        if (summary != null) {
            val report = summary!!
            item {
                TerminalCard {
                    Eyebrow("Dataset summary · local historical data")
                    Spacer(Modifier.height(8.dp))
                    Text(report.trend, color = when {
                        report.trend.startsWith("Bullish") -> Mint
                        report.trend.startsWith("Bearish") -> Coral
                        else -> Gold
                    }, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    MetricTile("Rows imported", report.rows.toString(), "Validated OHLCV records")
                    Spacer(Modifier.height(8.dp))
                    MetricTile("Latest close", String.format(Locale.US, "₹%,.2f", report.latestClose), report.lastTimestamp.toString(), accent = Cyan)
                    Spacer(Modifier.height(8.dp))
                    MetricTile("Latest volume", "%,d".format(Locale.US, report.latestVolume), "Reported units in source CSV", accent = Gold)
                }
            }
            item {
                TerminalCard {
                    Eyebrow("Technical evidence")
                    IndicatorLine("SMA 20", report.sma20?.let { String.format(Locale.US, "₹%,.2f", it) } ?: "Warming up")
                    IndicatorLine("SMA 50", report.sma50?.let { String.format(Locale.US, "₹%,.2f", it) } ?: "Warming up")
                    IndicatorLine("RSI 14", report.rsi14?.let { String.format(Locale.US, "%.1f", it) } ?: "Warming up")
                    IndicatorLine("Donchian 20", report.donchian20)
                    IndicatorLine("Relative volume 20", report.relativeVolume20?.let { String.format(Locale.US, "%.2fx", it) } ?: "Warming up")
                    Spacer(Modifier.height(8.dp))
                    Text("These are descriptive indicators from the imported file, not a live quote, validated strategy, or buy/sell recommendation.", color = Muted, fontSize = 10.sp, lineHeight = 16.sp)
                }
            }
        } else {
            item { EmptyPanel("No historical dataset loaded", "Import a licensed OHLCV CSV to calculate trend, momentum and breakout conditions on-device. Your imported dataset is stored privately on this device.") }
        }

    }
}

@Composable
private fun IndicatorLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun InputStream.readUtf8Limited(): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= MAX_CSV_BYTES) { "CSV exceeds the 20 MB import limit" }
        output.write(buffer, 0, count)
    }
    return String(output.toByteArray(), Charsets.UTF_8)
}
