package com.rovexquant.app.ui

import com.rovexquant.app.data.model.QuantSession
import com.rovexquant.app.data.repository.QuantDataRepository

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
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

private const val MAX_CSV_BYTES = 20 * 1024 * 1024

@Composable
fun ResearchLabScreen(session: QuantSession? = null, repository: QuantDataRepository? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<OhlcvResearchSummary?>(null) }
    var datasetName by remember { mutableStateOf("No dataset loaded") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

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

    LaunchedEffect(context) {
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
        item { SectionHeading("Test the idea before risking capital", "Research lab.", "Use official NSE research tools on demand or import permitted historical OHLCV data for transparent local analysis.") }
        if (session != null && repository != null) item { NseMcpResearchPanel(session, repository) }
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
        item { EmptyPanel("Data integrity first", "Naive timestamps, duplicate or unordered rows, invalid OHLC ranges, negative volume and oversized files are rejected. Imported data is never labelled live.") }
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
