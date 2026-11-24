package com.rovexquant.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rovexquant.app.data.model.MarketEquityInstrument
import com.rovexquant.app.data.local.TejHqOhlcvStore
import com.rovexquant.app.data.local.TejHqOhlcvSyncManager
import com.rovexquant.app.data.repository.QuantDataRepository
import com.rovexquant.app.domain.OhlcvCsvResearch
import com.rovexquant.app.domain.OhlcvResearchSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun TejHqOhlcvPanel(
    repository: QuantDataRepository?,
    onDatasetLoaded: (OhlcvResearchSummary, String) -> Unit,
    equityInstruments: List<MarketEquityInstrument> = emptyList(),
    indexKeys: Map<String, String> = emptyMap()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember(context) { TejHqOhlcvStore.settings(context) }
    var exchange by rememberSaveable { mutableStateOf(initial.exchange.uppercase()) }
    var symbol by rememberSaveable { mutableStateOf(initial.symbol) }
    var fromDate by rememberSaveable { mutableStateOf(initial.fromDate) }
    var toDate by rememberSaveable { mutableStateOf(LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()) }
    var autoSync by rememberSaveable { mutableStateOf(initial.autoSync) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    fun runFetch(incremental: Boolean) {
        val source = repository ?: run {
            error = "Connect to the Quant workspace to use the extractor."
            return
        }
        val from = runCatching { LocalDate.parse(fromDate) }.getOrNull()
        val to = runCatching { LocalDate.parse(toDate) }.getOrNull()
        if (from == null || to == null || from.isAfter(to)) {
            error = "Choose a valid date range."
            return
        }
        if (loading) return
        loading = true
        error = null
        status = null
        scope.launch {
            try {
                val bars = TejHqOhlcvSyncManager.fetchAndSave(
                    context, source, exchange, symbol, from, to, incremental
                )
                onDatasetLoaded(
                    OhlcvCsvResearch.summarize(bars),
                    "$symbol · $exchange · TejHQ raw EOD"
                )
                status = "${bars.size} bars saved on this device"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = "Historical data unavailable. Try another date range."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(context, repository) {
        if (repository != null && context.fileList().contains(OhlcvCsvResearch.STORAGE_FILE_NAME)) {
            try {
                val updated = TejHqOhlcvSyncManager.refreshIfDue(context, repository)
                if (updated != null) {
                    val settings = TejHqOhlcvStore.settings(context)
                    onDatasetLoaded(
                        OhlcvCsvResearch.summarize(updated),
                        "${settings.symbol} · ${settings.exchange.uppercase()} · TejHQ raw EOD"
                    )
                    status = "Updated automatically · ${updated.size} bars"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = "Historical data refresh delayed."
            }
        }
    }

    TerminalCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Eyebrow("Automatic OHLCV")
            StatusPill(if (autoSync) "AUTO ON" else "AUTO OFF", if (autoSync) Mint else Gold)
        }
        Text(
            "Daily NSE/BSE history · saved privately on this device",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { exchange = "NSE" },
                modifier = Modifier.weight(1f).testTag("ohlcv.exchange.nse")
            ) { Text(if (exchange == "NSE") "● NSE" else "NSE") }
            TextButton(
                onClick = { exchange = "BSE" },
                modifier = Modifier.weight(1f).testTag("ohlcv.exchange.bse")
            ) { Text(if (exchange == "BSE") "● BSE" else "BSE") }
        }
        InstrumentSymbolPicker(
            value = symbol,
            onValueChange = { symbol = it.uppercase().take(40) },
            label = "Stock or index name",
            testTag = "ohlcv.symbol",
            equities = if (exchange == "NSE") equityInstruments else emptyList(),
            indexKeys = if (exchange == "NSE") indexKeys else emptyMap(),
            enabled = exchange == "NSE",
            placeholder = if (exchange == "NSE") "Company name or ticker" else "BSE symbol · manual entry"
        )
        EventDatePickerField(
            value = fromDate,
            onValueChange = { fromDate = it },
            modifier = Modifier.fillMaxWidth().testTag("ohlcv.from"),
            label = "From date"
        )
        EventDatePickerField(
            value = toDate,
            onValueChange = { toDate = it },
            modifier = Modifier.fillMaxWidth().testTag("ohlcv.to"),
            label = "To date"
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Auto-refresh when this screen opens", color = MaterialTheme.colorScheme.onBackground, fontSize = 11.sp)
                Text("Checks every 12 hours", color = Muted, fontSize = 9.sp)
            }
            Switch(
                checked = autoSync,
                onCheckedChange = {
                    autoSync = it
                    TejHqOhlcvStore.saveSettings(
                        context,
                        TejHqOhlcvStore.settings(context).copy(autoSync = it)
                    )
                },
                modifier = Modifier.testTag("ohlcv.autoSync")
            )
        }
        Button(
            onClick = { runFetch(incremental = false) },
            enabled = !loading && repository != null && symbol.isNotBlank(),
            modifier = Modifier.fillMaxWidth().testTag("ohlcv.fetch")
        ) {
            Text(if (loading) "Fetching…" else "Fetch OHLCV history")
        }
        if (loading) CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp))
        status?.let { Text(it, color = Mint, fontSize = 10.sp) }
        error?.let { GracefulErrorState("Historical data delayed. Tap retry to check again.") }
        Text(
            "Raw exchange EOD bars. Corporate-action adjustments are not applied.",
            color = Muted,
            fontSize = 9.sp
        )
    }
}
