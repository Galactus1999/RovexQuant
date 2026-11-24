package com.rovexquant.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.roundToInt

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
                TejHqOhlcvSyncManager.fetchAndSave(
                    context = context,
                    repository = repository,
                    instrument = selected,
                    from = if (fullHistory) today.minusDays(6200) else today.minusYears(3),
                    to = today,
                    incremental = !fullHistory
                ).also(ChartSeriesMapper::map)
            }
            series = result
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
        val cached = withContext(Dispatchers.IO) {
            runCatching { TejHqOhlcvStore.load(context, selected) }.getOrNull()
        }
        if (!cached.isNullOrEmpty()) {
            val lastSync = TejHqOhlcvStore.lastSyncedMillis(context, selected)
            series = MarketOhlcvSeries(
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
            loading = false
        }
        refreshSeries()
    }

    val bars = series?.bars.orEmpty()
    val chartData by produceState<ChartSeriesData?>(initialValue = null, series) {
        val source = series
        value = if (source == null) null else runCatching {
            withContext(Dispatchers.Default) { ChartSeriesMapper.map(source) }
        }.getOrNull()
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
                chartData?.let { validated ->
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

@Composable
private fun InteractivePriceChart(
    bars: List<OhlcvBar>,
    showVolume: Boolean,
    showSma20: Boolean,
    showSma50: Boolean
) {
    var selected by remember(bars) { mutableIntStateOf(bars.lastIndex) }
    var windowStart by remember(bars) { mutableIntStateOf(0) }
    var windowCount by remember(bars) { mutableIntStateOf(bars.size.coerceAtLeast(1)) }
    val safeStart = windowStart.coerceIn(0, (bars.size - windowCount).coerceAtLeast(0))
    val safeEnd = (safeStart + windowCount).coerceAtMost(bars.size)
    val visibleBars = remember(bars, safeStart, safeEnd) {
        if (safeStart < safeEnd) bars.subList(safeStart, safeEnd) else emptyList()
    }
    val sma20Values = remember(bars) { simpleMovingAverage(bars, 20) }
    val sma50Values = remember(bars) { simpleMovingAverage(bars, 50) }
    val bar = bars.getOrNull(selected)

    Column {
        bar?.let {
            Text(
                "${it.timestamp.toLocalDate()}  O ${"%.2f".format(it.open)}  H ${"%.2f".format(it.high)}  L ${"%.2f".format(it.low)}  C ${"%.2f".format(it.close)}",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 10.sp
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                enabled = bars.isNotEmpty() && (windowStart != 0 || windowCount != bars.size),
                onClick = {
                    windowStart = 0
                    windowCount = bars.size.coerceAtLeast(1)
                    selected = bars.lastIndex
                }
            ) { Text("Reset zoom") }
        }
        Canvas(
            Modifier.fillMaxWidth().height(if (showVolume) 230.dp else 180.dp)
                .pointerInput(bars.size) {
                    detectTapGestures { point ->
                        if (bars.isNotEmpty() && size.width > 0 && visibleBars.isNotEmpty()) {
                            val local = ((point.x / size.width) * visibleBars.size)
                                .toInt().coerceIn(visibleBars.indices)
                            selected = safeStart + local
                        }
                    }
                }
                .pointerInput(bars.size) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (bars.isEmpty() || size.width <= 0) return@detectTransformGestures
                        val oldCount = windowCount.coerceIn(1, bars.size)
                        val fraction = (centroid.x / size.width).coerceIn(0f, 1f)
                        val focalIndex = windowStart + fraction * oldCount
                        val minimumWindow = minOf(20, bars.size).coerceAtLeast(1)
                        val nextCount = (oldCount / zoom).roundToInt()
                            .coerceIn(minimumWindow, bars.size)
                        val panBars = (pan.x / size.width) * nextCount
                        val nextStart = (focalIndex - fraction * nextCount - panBars)
                            .roundToInt().coerceIn(0, (bars.size - nextCount).coerceAtLeast(0))
                        windowStart = nextStart
                        windowCount = nextCount
                        selected = (nextStart + (fraction * nextCount).toInt())
                            .coerceIn(nextStart, (nextStart + nextCount - 1).coerceAtMost(bars.lastIndex))
                    }
                }
        ) {
            if (visibleBars.isEmpty()) return@Canvas
            val low = visibleBars.minOf { it.low }
            val high = visibleBars.maxOf { it.high }
            val spread = (high - low).takeIf { it > 0.0 } ?: 1.0
            val volumeHeight = if (showVolume) size.height * .22f else 0f
            val priceHeight = size.height - volumeHeight - 8f
            val step = size.width / (visibleBars.size - 1).coerceAtLeast(1)
            fun y(value: Double) =
                priceHeight - ((value - low) / spread * (priceHeight - 12f)).toFloat() - 6f

            val pricePath = Path()
            visibleBars.forEachIndexed { index, item ->
                if (index == 0) pricePath.moveTo(0f, y(item.close))
                else pricePath.lineTo(index * step, y(item.close))
            }
            drawPath(pricePath, androidx.compose.ui.graphics.Color(0xFF63A9FF), style = Stroke(2.5f))

            if (showSma20) {
                val average = Path()
                var started = false
                for (globalIndex in safeStart until safeEnd) {
                    val value = sma20Values[globalIndex] ?: continue
                    val x = (globalIndex - safeStart) * step
                    if (!started) { average.moveTo(x, y(value)); started = true }
                    else average.lineTo(x, y(value))
                }
                if (started) drawPath(average, androidx.compose.ui.graphics.Color(0xFF27C58B), style = Stroke(1.8f))
            }
            if (showSma50) {
                val average = Path()
                var started = false
                for (globalIndex in safeStart until safeEnd) {
                    val value = sma50Values[globalIndex] ?: continue
                    val x = (globalIndex - safeStart) * step
                    if (!started) { average.moveTo(x, y(value)); started = true }
                    else average.lineTo(x, y(value))
                }
                if (started) drawPath(average, androidx.compose.ui.graphics.Color(0xFFFF6678), style = Stroke(1.8f))
            }
            if (showVolume) {
                val maxVolume = visibleBars.maxOf { it.volume }.coerceAtLeast(1L).toFloat()
                visibleBars.forEachIndexed { index, item ->
                    val top = size.height - item.volume.toFloat() / maxVolume * volumeHeight
                    drawLine(
                        if (item.close >= item.open) androidx.compose.ui.graphics.Color(0xFF27C58B)
                        else androidx.compose.ui.graphics.Color(0xFFFF6678),
                        Offset(index * step, size.height),
                        Offset(index * step, top),
                        strokeWidth = (size.width / visibleBars.size).coerceAtLeast(1f) * .65f
                    )
                }
            }
            val localSelected = (selected - safeStart).coerceIn(0, visibleBars.lastIndex)
            val crosshairX = localSelected * step
            drawLine(
                androidx.compose.ui.graphics.Color.Gray,
                Offset(crosshairX, 0f),
                Offset(crosshairX, size.height),
                strokeWidth = 1f
            )
        }
        Text(
            "Tap or drag to inspect · pinch to zoom · drag to pan · reset zoom to show all",
            color = Muted,
            fontSize = 9.sp
        )
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
