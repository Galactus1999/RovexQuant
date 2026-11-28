package com.rovexquant.app.ui

import com.rovexquant.app.data.model.QuantSession
import com.rovexquant.app.data.model.MarketEquityInstrument
import com.rovexquant.app.data.local.TejHqOhlcvStore
import com.rovexquant.app.data.local.ResearchCsvDatasetStore
import com.rovexquant.app.data.local.ResearchCsvFolderImporter
import com.rovexquant.app.data.local.ResearchCsvAutoSyncManager
import com.rovexquant.app.data.model.MacroResearchSummary
import com.rovexquant.app.data.model.EventStudyResult
import com.rovexquant.app.data.repository.QuantDataRepository

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import java.io.InputStream
import java.util.Locale

private const val MAX_CSV_BYTES = 20 * 1024 * 1024

@Composable
fun ResearchLabScreen(
    session: QuantSession? = null,
    repository: QuantDataRepository? = null,
    equityInstruments: List<MarketEquityInstrument> = emptyList(),
    indexKeys: Map<String, String> = emptyMap()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<OhlcvResearchSummary?>(null) }
    var datasetName by remember { mutableStateOf("No dataset loaded") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var csvFolderUri by remember { mutableStateOf(ResearchCsvFolderImporter.savedFolderUri(context)?.toString()) }
    var csvFolderLoading by remember { mutableStateOf(false) }
    var csvFolderStatus by remember { mutableStateOf<String?>(null) }
    var csvFolderError by remember { mutableStateOf<String?>(null) }
    var csvFolderDatasets by remember { mutableStateOf(ResearchCsvDatasetStore.list(context)) }
    val csvAutoSyncPrefs = remember(context) {
        context.getSharedPreferences("research_csv_auto_sync", 0)
    }
    var csvAutoSyncEnabled by remember(context) {
        mutableStateOf(csvAutoSyncPrefs.getBoolean("enabled", true))
    }
    var csvAutoSyncLoading by remember { mutableStateOf(false) }
    var csvAutoSyncStatus by remember { mutableStateOf<String?>(null) }
    var csvAutoSyncError by remember { mutableStateOf<String?>(null) }
    var macroSummary by remember { mutableStateOf<MacroResearchSummary?>(null) }
    var macroLoading by remember { mutableStateOf(false) }
    var macroError by remember { mutableStateOf<String?>(null) }
    var eventSymbol by rememberSaveable { mutableStateOf("RELIANCE") }
    var eventDate by rememberSaveable { mutableStateOf("") }
    var benchmarkSymbol by rememberSaveable { mutableStateOf("") }
    var eventResult by remember { mutableStateOf<EventStudyResult?>(null) }
    var eventLoading by remember { mutableStateOf(false) }
    var eventError by remember { mutableStateOf<String?>(null) }
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

    fun runCsvAutoSync(force: Boolean) {
        if (csvAutoSyncLoading) return
        if (session == null || repository == null) {
            csvAutoSyncError = "Sign in and connect the Quant workspace before refreshing imported datasets."
            return
        }
        if (csvFolderDatasets.isEmpty()) {
            csvAutoSyncStatus = "Import instrument-mapped CSV datasets first."
            return
        }
        csvAutoSyncLoading = true
        csvAutoSyncError = null
        scope.launch {
            try {
                val report = ResearchCsvAutoSyncManager.sync(
                    context, repository, session, force = force
                )
                csvFolderDatasets = withContext(Dispatchers.IO) {
                    ResearchCsvDatasetStore.list(context)
                }
                csvAutoSyncStatus = report.summary
                csvAutoSyncError = report.errors.take(5).joinToString(" · ")
                    .takeIf { it.isNotBlank() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                csvAutoSyncError = failure.message ?: "Imported datasets could not be refreshed."
            } finally {
                csvAutoSyncLoading = false
            }
        }
    }

    LaunchedEffect(session, repository, csvAutoSyncEnabled, csvFolderDatasets.size) {
        if (csvAutoSyncEnabled && session != null && repository != null && csvFolderDatasets.isNotEmpty()) {
            runCsvAutoSync(force = false)
        }
    }

    fun loadSelectedFile(uri: Uri) {
        loading = true
        error = null
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytesLimited() }
                        ?: throw IllegalArgumentException("Could not open the selected file")
                    val sourceName = uri.lastPathSegment?.substringAfterLast('/') ?: "Imported CSV"
                    val exchangeDatasets = ResearchCsvFolderImporter.importExchangeBhavcopy(
                        context, sourceName, bytes
                    )
                    if (exchangeDatasets != null) {
                        val first = exchangeDatasets.firstOrNull()
                            ?: throw IllegalArgumentException("Exchange Bhavcopy contained no importable instruments.")
                        val bars = ResearchCsvDatasetStore.load(context, first)
                            ?: throw IllegalStateException("Imported exchange dataset could not be reopened.")
                        OhlcvCsvResearch.summarize(bars) to
                            ("Exchange Bhavcopy · " + exchangeDatasets.size + " instruments imported")
                    } else {
                        val csv = ResearchCsvFolderImporter.decodeCsvPayload(bytes)
                        val namedDataset = ResearchCsvFolderImporter.importNamedInstrumentCsv(
                            context, sourceName, csv
                        )
                        if (namedDataset != null) {
                            val bars = ResearchCsvDatasetStore.load(context, namedDataset)
                                ?: throw IllegalStateException("Imported instrument dataset could not be reopened.")
                            OhlcvCsvResearch.summarize(bars) to
                                ("CSV · " + namedDataset.label + " · " + bars.size + " bars")
                        } else {
                            val bars = OhlcvCsvResearch.parse(csv)
                            // Unnamed files remain isolated until the user supplies instrument identity.
                            TejHqOhlcvStore.save(context, bars)
                            OhlcvCsvResearch.summarize(bars) to sourceName
                        }
                    }
                }
                summary = loaded.first
                datasetName = loaded.second
                csvFolderDatasets = ResearchCsvDatasetStore.list(context)
                if (csvAutoSyncEnabled) runCsvAutoSync(force = false)
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

    suspend fun scanCsvFolder(uriString: String) {
        if (csvFolderLoading) return
        csvFolderLoading = true
        csvFolderError = null
        try {
            val report = withContext(Dispatchers.IO) {
                ResearchCsvFolderImporter.scan(context, Uri.parse(uriString))
            }
            csvFolderDatasets = report.datasets
            csvFolderStatus = report.summary
            if (csvAutoSyncEnabled && session != null && repository != null) {
                runCsvAutoSync(force = false)
            }
            csvFolderError = report.errors.take(5).joinToString(" · ").takeIf { it.isNotBlank() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            csvFolderError = failure.message ?: "Selected CSV folder could not be scanned."
        } finally {
            csvFolderLoading = false
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val sameFolder = csvFolderUri == uri.toString()
            try {
                ResearchCsvFolderImporter.persistFolder(context, uri)
                csvFolderUri = uri.toString()
                csvFolderStatus = "Folder permission saved. Scanning daily CSV files…"
                if (sameFolder) scope.launch { scanCsvFolder(uri.toString()) }
            } catch (failure: Exception) {
                csvFolderError = failure.message ?: "Could not persist folder permission."
            }
        }
    }

    LaunchedEffect(context, csvFolderUri) {
        csvFolderUri?.let { scanCsvFolder(it) }
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
                datasetName = "Saved local dataset"

            } catch (failure: Exception) {
                error = failure.message ?: "Saved dataset could not be read"
            } finally {
                loading = false
            }
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize().testTag("research.lab.list"), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionHeading("RESEARCH", "Research lab.", "") }
        item {
            TejHqOhlcvPanel(
                repository = repository,
                onDatasetLoaded = { loaded, name ->
                summary = loaded
                datasetName = name
                },
                equityInstruments = equityInstruments,
                indexKeys = indexKeys
            )
        }
        if (session != null && repository != null) {
            item { NseMcpResearchPanel(session, repository, equityInstruments = equityInstruments, indexKeys = indexKeys) }
            item {
                TerminalCard {
                    Eyebrow("Event study")
                    InstrumentSymbolPicker(
                        value = eventSymbol,
                        onValueChange = { eventSymbol = it.uppercase().take(80) },
                        label = "NSE company or index",
                        testTag = "research.lab.event.symbol",
                        equities = equityInstruments,
                        indexKeys = indexKeys
                    )
                    EventDatePickerField(
                        value = eventDate,
                        onValueChange = { eventDate = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                    InstrumentSymbolPicker(
                        value = benchmarkSymbol,
                        onValueChange = { benchmarkSymbol = it.uppercase().take(80) },
                        label = "Benchmark company or index · optional",
                        testTag = "research.lab.event.benchmark",
                        equities = equityInstruments,
                        indexKeys = indexKeys
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow("Automatic data updates")
                        Text(
                            "Refresh imported daily datasets",
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Switch(
                        checked = csvAutoSyncEnabled,
                        onCheckedChange = { enabled ->
                            csvAutoSyncEnabled = enabled
                            csvAutoSyncPrefs.edit().putBoolean("enabled", enabled).apply()
                        },
                        modifier = Modifier.testTag("research.csv.auto_sync")
                    )
                }
                Text(
                    "When Research Lab opens, the app checks mapped datasets every 4 hours. Equity history is refreshed in exchange batches from the persistent EOD Data Hub; supported NSE indices use Upstox historical candles. Requires internet and a signed-in Quant session. It does not run while the app is closed.",
                    color = Muted,
                    fontSize = 10.sp,
                    lineHeight = 16.sp
                )
                Button(
                    onClick = { runCsvAutoSync(force = true) },
                    enabled = !csvAutoSyncLoading && csvFolderDatasets.isNotEmpty() &&
                        session != null && repository != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (csvAutoSyncLoading) "Refreshing imported datasets…" else "Sync imported datasets now")
                }
                csvAutoSyncStatus?.let {
                    Text(it, color = Mint, fontSize = 10.sp, lineHeight = 15.sp)
                }
                csvAutoSyncError?.let {
                    Text(it, color = Coral, fontSize = 10.sp, lineHeight = 15.sp)
                }
            }
        }
        item {
            TerminalCard {
                Eyebrow("Automatic CSV folder import")
                Text(
                    "Choose a folder once. Its immediate CSV files are rescanned whenever Research Lab opens or when you tap Scan now. Only this selected folder is read; the app does not crawl device storage.",
                    color = Muted,
                    fontSize = 10.sp,
                    lineHeight = 16.sp
                )
                Text(
                    "Daily filenames: NSE_RELIANCE.csv, BSE_TCS.csv, NSE_NIFTY_50.csv. Supports Date/Datetime/Timestamp, Open, High, Low, Close and common Volume/Vol exports. Date-only and local timestamps are interpreted as Asia/Kolkata; equity files require volume, while index volume may be omitted. Limits: 20 MB/file, 100 MB/scan, 100 CSV files.",
                    color = Muted,
                    fontSize = 10.sp,
                    lineHeight = 16.sp
                )
                Button(
                    onClick = { folderPicker.launch(null) },
                    enabled = !csvFolderLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (csvFolderUri == null) "Choose CSV folder" else "Change CSV folder")
                }
                if (csvFolderUri != null) {
                    Text("Folder permission saved · auto-scan on Research Lab open", color = Mint, fontSize = 10.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { scope.launch { scanCsvFolder(csvFolderUri!!) } },
                            enabled = !csvFolderLoading
                        ) { Text(if (csvFolderLoading) "Scanning…" else "Scan now") }
                        TextButton(onClick = {
                            ResearchCsvFolderImporter.clearFolder(context)
                            csvFolderUri = null
                            csvFolderStatus = "Folder access removed. Imported datasets are retained."
                            csvFolderError = null
                        }) { Text("Forget folder") }
                    }
                }
                csvFolderStatus?.let {
                    Text(it, color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                }
                csvFolderError?.let {
                    Text(it, color = Coral, fontSize = 10.sp, lineHeight = 15.sp)
                }
                if (csvFolderDatasets.isNotEmpty()) {
                    Text("IMPORTED DAILY DATASETS", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    csvFolderDatasets.take(12).forEach { dataset ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(dataset.label, color = MaterialTheme.colorScheme.onBackground, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    dataset.rowCount.toString() + " bars · " +
                                        dataset.firstTimestamp.substringBefore("T") + " to " +
                                        dataset.lastTimestamp.substringBefore("T") +
                                        (dataset.lastSyncProvider?.let { " · updated via " + it } ?: " · awaiting online update") +
                                        (if (dataset.priceBasis == "ADJUSTED_CLOSE") " · adjusted-only" else ""),
                                    color = Muted,
                                    fontSize = 9.sp
                                )
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    try {
                                        val opened = withContext(Dispatchers.IO) {
                                            val bars = ResearchCsvDatasetStore.load(context, dataset)
                                            bars?.let { it to OhlcvCsvResearch.summarize(it) }
                                        }
                                        if (opened != null) {
                                            summary = opened.second
                                            datasetName = "Folder CSV · " + dataset.label
                                            error = null
                                        } else {
                                            csvFolderError = "Imported dataset file is no longer available."
                                        }
                                    } catch (failure: Exception) {
                                        csvFolderError = failure.message ?: "Imported dataset could not be opened."
                                    }
                                }
                            }) { Text("Open") }
                            TextButton(onClick = {
                                ResearchCsvDatasetStore.delete(context, dataset)
                                csvFolderDatasets = ResearchCsvDatasetStore.list(context)
                                if (datasetName == "Folder CSV · " + dataset.label) {
                                    summary = null
                                    datasetName = "No dataset loaded"
                                }
                            }) { Text("Delete") }
                        }
                    }
                }
            }
        }
        item {
            TerminalCard {
                Eyebrow("Offline dataset")
                Spacer(Modifier.height(7.dp))
                Text(datasetName, color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text("Supports CSV and single-CSV ZIP files from common broker exports and NSE/BSE UDiFF Bhavcopy. Delimiters, common header aliases, quoted values, UTF-8/UTF-16 and Indian date formats are detected. Multi-security Bhavcopy rows are split into separate instrument datasets. Maximum selected file size: 20 MB.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { picker.launch(arrayOf("text/*", "text/csv", "application/csv", "application/vnd.ms-excel", "*/*")) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                    Text(if (loading) "Importing…" else "Import OHLCV CSV")
                }
                if (summary != null) {
                    TextButton(onClick = {
                        TejHqOhlcvStore.clearResearchDataset(context)
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
            item { EmptyPanel("No historical dataset loaded", "Fetch daily OHLCV history above or import a CSV. Data is stored privately on this device.") }
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

private fun InputStream.readBytesLimited(): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= MAX_CSV_BYTES) { "Selected file exceeds the 20 MB import limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
