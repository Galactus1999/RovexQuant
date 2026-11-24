package com.rovexquant.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rovexquant.app.data.local.TejHqOhlcvStore
import com.rovexquant.app.data.local.TejHqOhlcvSyncManager
import com.rovexquant.app.data.repository.QuantDataRepository
import com.rovexquant.app.domain.InstrumentCatalog
import com.rovexquant.app.domain.ChartSeriesMapper
import com.rovexquant.app.domain.ChartSeriesData
import com.rovexquant.app.domain.MarketOhlcvSeries
import com.rovexquant.app.domain.OhlcvBar
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun InstrumentResearchScreen(
    symbol: String,
    displayName: String,
    repository: QuantDataRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val identity = remember(symbol) { runCatching { InstrumentCatalog.resolve(symbol, "NSE") }.getOrNull() }
    val identityError = remember(symbol) {
        runCatching { InstrumentCatalog.resolve(symbol, "NSE") }.exceptionOrNull()?.message
    }
    var series by remember(symbol) { mutableStateOf<MarketOhlcvSeries?>(null) }
    var chartData by remember(symbol) { mutableStateOf<ChartSeriesData?>(null) }
    var loading by remember(symbol) { mutableStateOf(true) }
    var refreshing by remember(symbol) { mutableStateOf(false) }
    var error by remember(symbol) { mutableStateOf<String?>(null) }
    var range by rememberSaveable(symbol) { mutableStateOf("1Y") }
    var volume by rememberSaveable(symbol) { mutableStateOf(true) }
    var sma20 by rememberSaveable(symbol) { mutableStateOf(false) }
    var sma50 by rememberSaveable(symbol) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refreshSeries(fullHistory: Boolean = false) {
        val selected = identity ?: return
        refreshing = true
        error = null
        try {
            val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))
            val result = withContext(Dispatchers.IO) {
                val fetched = TejHqOhlcvSyncManager.fetchAndSave(
                    context = context,
                    repository = repository,
                    instrument = selected,
                    from = if (fullHistory) today.minusDays(6200) else today.minusYears(3),
                    to = today,
                    incremental = !fullHistory
                )
                fetched to ChartSeriesMapper.map(fetched)
            }
            series = result.first
            chartData = result.second
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Historical data refresh failed."
        } finally {
            refreshing = false
            loading = false
        }
    }

    LaunchedEffect(identity) {
        val selected = identity
        if (selected == null) {
            error = identityError ?: "Unsupported instrument identity."
            loading = false
            return@LaunchedEffect
        }
        val cachedResult = withContext(Dispatchers.IO) {
            val cached = runCatching { TejHqOhlcvStore.load(context, selected) }.getOrNull()
            if (cached.isNullOrEmpty()) {
                null
            } else {
                runCatching {
                    val lastSync = TejHqOhlcvStore.lastSyncedMillis(context, selected)
                    val cachedSeries = MarketOhlcvSeries(
                        instrument = selected,
                        bars = cached,
                        retrievedAt = lastSync.takeIf { it > 0L }?.let(Instant::ofEpochMilli),
                        volumeAvailable = selected.provider == com.rovexquant.app.domain.OhlcvProvider.TEJHQ_EOD,
                        adjustmentStatus = if (selected.provider == com.rovexquant.app.domain.OhlcvProvider.TEJHQ_EOD) {
                            "Raw end-of-day bhavcopy prices; corporate-action adjustment not applied"
                        } else {
                            "Price Return Index OHLC; dividends are not included"
                        }
                    )
                    cachedSeries to ChartSeriesMapper.map(cachedSeries)
                }
            }
        }
        if (cachedResult != null) {
            cachedResult.onSuccess { (cachedSeries, mapped) ->
                series = cachedSeries
                chartData = mapped
                loading = false
            }.onFailure { failure ->
                error = failure.message ?: "Cached chart data failed validation."
            }
        }
        refreshSeries()
    }

    val bars = series?.bars.orEmpty()
    val chartVisibleData by produceState<ChartSeriesData?>(initialValue = null, chartData, visible) {
        val source = chartData
        val firstDate = visible.firstOrNull()?.timestamp?.toLocalDate()?.toString()
        val lastDate = visible.lastOrNull()?.timestamp?.toLocalDate()?.toString()
        value = if (source == null || firstDate == null || lastDate == null) {
            null
        } else {
            withContext(Dispatchers.Default) {
                source.copy(
                    candles = source.candles.filter { it.time >= firstDate && it.time <= lastDate },
                    volumeBars = source.volumeBars?.filter { it.time >= firstDate && it.time <= lastDate }
                )
            }
        }
    }
    val visible = remember(bars, range) {
        val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))
        val cutoff = when (range) {
            "1M" -> today.minusMonths(1)
            "3M" -> today.minusMonths(3)
            "6M" -> today.minusMonths(6)
            "1Y" -> today.minusYears(1)
            "3Y" -> today.minusYears(3)
            else -> null
        }
        if (cutoff == null) bars else bars.filter { !it.timestamp.toLocalDate().isBefore(cutoff) }
    }
    val latest = visible.lastOrNull()

    Column(
        Modifier.fillMaxSize().background(Ink).padding(16.dp)
            .testTag("instrument.research.screen")
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back to Pulse") }
            Spacer(Modifier.weight(1f))
            TextButton(
                enabled = identity != null && !refreshing,
                onClick = { scope.launch { refreshSeries() } }
            ) {
                Text(if (refreshing) "Refreshing…" else "↻ Refresh")
            }
        }
        Text(displayName.ifBlank { symbol }, color = MaterialTheme.colorScheme.onBackground,
            fontSize = 25.sp, fontWeight = FontWeight.Bold)
        if (!displayName.equals(symbol, ignoreCase = true)) {
            Text(symbol, color = Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        if (series != null) {
            Surface(
                color = androidx.compose.ui.graphics.Color(0xFF18352F),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    "${series!!.sourceLabel}  ·  ${series!!.instrument.interval.key.uppercase()}",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    color = androidx.compose.ui.graphics.Color(0xFF8FE0B7),
                    fontSize = 10.sp
                )
            }
            Text(
                series!!.adjustmentStatus,
                color = Muted,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 5.dp)
            )
            series!!.providerNote?.let { Text(it, color = Muted, fontSize = 9.sp) }
            series!!.retrievedAt?.let {
                Text("Retrieved: ${it.atZone(ZoneId.of("Asia/Kolkata")).toLocalDateTime()} IST",
                    color = Muted, fontSize = 9.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            listOf("1M", "3M", "6M", "1Y", "3Y", "ALL").forEach { period ->
                FilterChip(
                    enabled = !refreshing,
                    selected = range == period,
                    onClick = {
                        range = period
                        if (period == "ALL") {
                            val earliest = series?.bars?.firstOrNull()?.timestamp?.toLocalDate()
                            val target = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(6190)
                            if (earliest == null || earliest.isAfter(target)) {
                                scope.launch { refreshSeries(fullHistory = true) }
                            }
                        }
                    },
                    label = { Text(period) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (series?.volumeAvailable == true) {
                FilterChip(selected = volume, onClick = { volume = !volume }, label = { Text("Volume") })
            }
            FilterChip(selected = sma20, onClick = { sma20 = !sma20 }, label = { Text("SMA 20") })
            FilterChip(selected = sma50, onClick = { sma50 = !sma50 }, label = { Text("SMA 50") })
        }
        if (range == "ALL" && bars.isNotEmpty()) {
            Text(
                if (refreshing) "Loading extended history…" else "All locally available bars · earliest ${bars.first().timestamp.toLocalDate()}",
                color = Muted, fontSize = 9.sp
            )
        }
        Spacer(Modifier.height(10.dp))
        when {
            loading -> CircularProgressIndicator()
            visible.isEmpty() -> TerminalCard {
                Text(error ?: "No historical bars are available for this instrument.", color = Muted)
                if (identity != null) {
                    TextButton(onClick = { scope.launch { refreshSeries() } }, enabled = !refreshing) {
                        Text("Retry historical data")
                    }
                }
            }
            else -> TerminalCard {
                Text(
                    "${visible.size} bars  ·  ${visible.first().timestamp.toLocalDate()} → ${visible.last().timestamp.toLocalDate()}",
                    color = Muted, fontSize = 10.sp
                )
                chartVisibleData?.let { validated ->
                    ValidatedCandlestickChart(
                        data = validated,
                        showVolume = volume,
                        showSma20 = sma20,
                        showSma50 = sma50
                    )
                } ?: CircularProgressIndicator()
                latest?.let {
                    Text(
                        "Close  ${"%.2f".format(it.close)}",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (series?.volumeAvailable == true) {
                        Text("Volume  ${it.volume}", color = Muted, fontSize = 10.sp)
                    } else {
                        Text("Traded volume is not published for this index series.", color = Muted, fontSize = 10.sp)
                    }
                }
            }
        }
        error?.let {
            Text(it, color = Coral, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

internal fun simpleMovingAverage(bars: List<OhlcvBar>, period: Int): List<Double?> {
    require(period > 0) { "Moving-average period must be positive." }
    val result = MutableList<Double?>(bars.size) { null }
    var rollingSum = 0.0
    for (index in bars.indices) {
        rollingSum += bars[index].close
        if (index >= period) rollingSum -= bars[index - period].close
        if (index >= period - 1) result[index] = rollingSum / period
    }
    return result
}
