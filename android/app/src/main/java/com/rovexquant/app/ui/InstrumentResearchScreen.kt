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
import com.rovexquant.app.data.model.TechnicalIndicators
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
    var showSma by rememberSaveable(symbol) { mutableStateOf(false) }
    var showEma by rememberSaveable(symbol) { mutableStateOf(false) }
    var showBollinger by rememberSaveable(symbol) { mutableStateOf(false) }
    var showRsi by rememberSaveable(symbol) { mutableStateOf(false) }
    var showMacd by rememberSaveable(symbol) { mutableStateOf(false) }
    var showAtr by rememberSaveable(symbol) { mutableStateOf(false) }
    var showAdx by rememberSaveable(symbol) { mutableStateOf(false) }
    var indicators by remember(symbol) { mutableStateOf<TechnicalIndicators?>(null) }
    var indicatorsLoading by remember(symbol) { mutableStateOf(false) }
    var indicatorsError by remember(symbol) { mutableStateOf<String?>(null) }
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
        if (cutoff == null) bars else bars.filter {
            !it.timestamp.atZoneSameInstant(ZoneId.of("Asia/Kolkata")).toLocalDate().isBefore(cutoff)
        }
    }
    val chartVisibleData by produceState<ChartSeriesData?>(initialValue = null, chartData, visible) {
        val source = chartData
        val firstDate = visible.firstOrNull()
            ?.timestamp?.atZoneSameInstant(ZoneId.of("Asia/Kolkata"))?.toLocalDate()?.toString()
        val lastDate = visible.lastOrNull()
            ?.timestamp?.atZoneSameInstant(ZoneId.of("Asia/Kolkata"))?.toLocalDate()?.toString()
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
    val latest = visible.lastOrNull()
    val visibleStartIndex = remember(bars, visible) {
        val firstDate = visible.firstOrNull()?.timestamp
            ?.atZoneSameInstant(ZoneId.of("Asia/Kolkata"))?.toLocalDate()
        if (firstDate == null) 0 else bars.indexOfFirst {
            it.timestamp.atZoneSameInstant(ZoneId.of("Asia/Kolkata")).toLocalDate() == firstDate
        }.coerceAtLeast(0)
    }

    LaunchedEffect(bars) {
        if (bars.isEmpty()) {
            indicators = null
            indicatorsError = null
            indicatorsLoading = false
            return@LaunchedEffect
        }
        val windowSize = minOf(10_000, bars.size)
        val startIndex = bars.size - windowSize
        val requestBars = bars.subList(startIndex, bars.size).toList()
        indicators = null
        indicatorsLoading = true
        indicatorsError = null
        try {
            val result = withContext(Dispatchers.IO) {
                repository.fetchTechnicalIndicators(requestBars, startIndex)
            }
            require(result.startIndex == startIndex && result.barCount == requestBars.size) {
                "Backend indicator coverage does not match the requested OHLC window."
            }
            indicators = result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            indicators = null
            indicatorsError = failure.message ?: "Backend indicator calculation is unavailable."
        } finally {
            indicatorsLoading = false
        }
    }

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
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (series?.volumeAvailable == true) {
                FilterChip(
                    modifier = Modifier.testTag("instrument.chart.volume.toggle"),
                    selected = volume,
                    onClick = { volume = !volume },
                    label = { Text("Volume") }
                )
            }
            FilterChip(enabled = indicators != null, selected = showSma, onClick = { showSma = !showSma }, label = { Text("SMA 20") })
            FilterChip(enabled = indicators != null, selected = showEma, onClick = { showEma = !showEma }, label = { Text("EMA 20") })
            FilterChip(enabled = indicators != null, selected = showBollinger, onClick = { showBollinger = !showBollinger }, label = { Text("Bollinger") })
            FilterChip(enabled = indicators != null, selected = showRsi, onClick = { showRsi = !showRsi }, label = { Text("RSI") })
            FilterChip(enabled = indicators != null, selected = showMacd, onClick = { showMacd = !showMacd }, label = { Text("MACD") })
            FilterChip(enabled = indicators != null, selected = showAtr, onClick = { showAtr = !showAtr }, label = { Text("ATR") })
            FilterChip(enabled = indicators != null, selected = showAdx, onClick = { showAdx = !showAdx }, label = { Text("ADX") })
        }
        when {
            indicatorsLoading -> Text("Calculating indicators on the backend…", color = Muted, fontSize = 9.sp)
            indicatorsError != null -> Text("Indicators unavailable: $indicatorsError", color = Coral, fontSize = 9.sp)
            indicators != null -> Text(
                "Deterministic backend indicators · ${indicators!!.barCount} bars · " +
                    "${bars.getOrNull(indicators!!.startIndex)?.timestamp?.toLocalDate()?.toString() ?: "coverage pending"} onward",
                color = Muted,
                fontSize = 9.sp
            )
        }
        if (series?.volumeAvailable == false) {
            Text(
                "Traded volume is not published for this index series.",
                color = Muted,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (series?.volumeAvailable == false) {
            Text(
                "Traded volume is not published for this index series.",
                color = Muted,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
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
                        indicators = indicators,
                        indicatorSourceOffset = visibleStartIndex,
                        showVolume = volume,
                        showSma = showSma,
                        showEma = showEma,
                        showBollinger = showBollinger,
                        showRsi = showRsi,
                        showMacd = showMacd,
                        showAtr = showAtr,
                        showAdx = showAdx
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
