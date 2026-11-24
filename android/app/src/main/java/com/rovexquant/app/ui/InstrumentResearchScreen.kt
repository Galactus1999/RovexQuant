package com.rovexquant.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
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
    var loading by remember(symbol) { mutableStateOf(true) }
    var refreshing by remember(symbol) { mutableStateOf(false) }
    var error by remember(symbol) { mutableStateOf<String?>(null) }
    var range by rememberSaveable(symbol) { mutableStateOf("1Y") }
    var volume by rememberSaveable(symbol) { mutableStateOf(true) }
    var sma20 by rememberSaveable(symbol) { mutableStateOf(false) }
    var sma50 by rememberSaveable(symbol) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refreshSeries() {
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
                    from = today.minusYears(3),
                    to = today,
                    incremental = true
                )
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
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("1M", "3M", "6M", "1Y", "3Y", "ALL").forEach { period ->
                FilterChip(
                    selected = range == period,
                    onClick = { range = period },
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
                InteractivePriceChart(
                    bars = visible,
                    showVolume = volume && series?.volumeAvailable == true,
                    showSma20 = sma20,
                    showSma50 = sma50
                )
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
    val bar = bars.getOrNull(selected)
    Column {
        bar?.let {
            Text(
                "${it.timestamp.toLocalDate()}  O ${"%.2f".format(it.open)}  H ${"%.2f".format(it.high)}  L ${"%.2f".format(it.low)}  C ${"%.2f".format(it.close)}",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 10.sp
            )
        }
        Canvas(
            Modifier.fillMaxWidth().height(if (showVolume) 230.dp else 180.dp)
                .pointerInput(bars) {
                    detectTapGestures { point ->
                        if (size.width > 0 && bars.isNotEmpty()) {
                            selected = ((point.x / size.width) * bars.size).toInt().coerceIn(bars.indices)
                        }
                    }
                }
        ) {
            if (bars.isEmpty()) return@Canvas
            val low = bars.minOf { it.low }
            val high = bars.maxOf { it.high }
            val spread = (high - low).takeIf { it > 0.0 } ?: 1.0
            val volumeHeight = if (showVolume) size.height * .22f else 0f
            val priceHeight = size.height - volumeHeight - 8f
            val step = size.width / (bars.size - 1).coerceAtLeast(1)
            fun y(value: Double) =
                priceHeight - ((value - low) / spread * (priceHeight - 12f)).toFloat() - 6f
            val path = Path()
            bars.forEachIndexed { index, item ->
                if (index == 0) path.moveTo(0f, y(item.close))
                else path.lineTo(index * step, y(item.close))
            }
            drawPath(path, androidx.compose.ui.graphics.Color(0xFF63A9FF), style = Stroke(2.5f))
            if (showSma20 && bars.size >= 20) {
                val average = Path()
                for (index in 19 until bars.size) {
                    val value = bars.subList(index - 19, index + 1).map { it.close }.average()
                    if (index == 19) average.moveTo(index * step, y(value))
                    else average.lineTo(index * step, y(value))
                }
                drawPath(average, androidx.compose.ui.graphics.Color(0xFF27C58B), style = Stroke(1.8f))
            }
            if (showSma50 && bars.size >= 50) {
                val average = Path()
                for (index in 49 until bars.size) {
                    val value = bars.subList(index - 49, index + 1).map { it.close }.average()
                    if (index == 49) average.moveTo(index * step, y(value))
                    else average.lineTo(index * step, y(value))
                }
                drawPath(average, androidx.compose.ui.graphics.Color(0xFFFF6678), style = Stroke(1.8f))
            }
            if (showVolume) {
                val maxVolume = bars.maxOf { it.volume }.coerceAtLeast(1L).toFloat()
                bars.forEachIndexed { index, item ->
                    val top = size.height - item.volume.toFloat() / maxVolume * volumeHeight
                    drawLine(
                        if (item.close >= item.open) androidx.compose.ui.graphics.Color(0xFF27C58B)
                        else androidx.compose.ui.graphics.Color(0xFFFF6678),
                        Offset(index * step, size.height),
                        Offset(index * step, top),
                        strokeWidth = (size.width / bars.size).coerceAtLeast(1f) * .65f
                    )
                }
            }
            val x = selected * step
            drawLine(androidx.compose.ui.graphics.Color.Gray, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        }
        Text("Tap chart to inspect individual OHLC bars", color = Muted, fontSize = 9.sp)
    }
}
