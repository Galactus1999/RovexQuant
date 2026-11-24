package com.rovexquant.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.sp
import com.rovexquant.app.domain.OhlcvBar
import kotlin.math.roundToInt

private data class CandleBucket(
    val firstIndex: Int,
    val lastIndex: Int,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
    val sma20: Double?,
    val sma50: Double?
)

/**
 * Price chart with true OHLC candle bodies/wicks. Dense histories are aggregated
 * into pixel-sized OHLC buckets instead of drawing every source row; the aggregation
 * preserves each bucket's first open, high, low, last close and summed volume.
 */
@Composable
internal fun CandlestickPriceChart(
    bars: List<OhlcvBar>,
    showVolume: Boolean,
    showSma20: Boolean,
    showSma50: Boolean
) {
    var selected by remember(bars) { mutableIntStateOf((bars.size - 1).coerceAtLeast(0)) }
    var windowStart by remember(bars) { mutableIntStateOf(0) }
    var windowCount by remember(bars) { mutableIntStateOf(bars.size.coerceAtLeast(1)) }
    val safeStart = windowStart.coerceIn(0, (bars.size - windowCount).coerceAtLeast(0))
    val safeEnd = (safeStart + windowCount).coerceAtMost(bars.size)
    val visibleBars = remember(bars, safeStart, safeEnd) {
        if (safeStart < safeEnd) bars.subList(safeStart, safeEnd) else emptyList()
    }
    val indicatorValues by produceState<Pair<List<Double?>, List<Double?>>?>(initialValue = null, bars) {
        value = withContext(Dispatchers.Default) {
            simpleMovingAverage(bars, 20) to simpleMovingAverage(bars, 50)
        }
    }
    val sma20 = indicatorValues?.first.orEmpty()
    val sma50 = indicatorValues?.second.orEmpty()
    val selectedBar = bars.getOrNull(selected)

    Column {
        selectedBar?.let { item ->
            Text(
                "${item.timestamp.toLocalDate()}  O ${"%.2f".format(item.open)}  H ${"%.2f".format(item.high)}  L ${"%.2f".format(item.low)}  C ${"%.2f".format(item.close)}",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 10.sp
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val maxCandles = (maxWidth / 5.dp).roundToInt().coerceIn(1, 1_500)
            val buckets by produceState<List<CandleBucket>>(initialValue = emptyList(), visibleBars, safeStart, maxCandles, indicatorValues) {
                value = withContext(Dispatchers.Default) {
                if (visibleBars.isEmpty() || indicatorValues == null) emptyList() else {
                    val count = minOf(maxCandles, visibleBars.size)
                    List(count) { bucketIndex ->
                        val from = bucketIndex * visibleBars.size / count
                        val until = ((bucketIndex + 1) * visibleBars.size / count)
                            .coerceAtLeast(from + 1)
                        val chunk = visibleBars.subList(from, until)
                        val firstGlobal = safeStart + from
                        val lastGlobal = safeStart + until - 1
                        CandleBucket(
                            firstIndex = firstGlobal,
                            lastIndex = lastGlobal,
                            open = chunk.first().open,
                            high = chunk.maxOf { it.high },
                            low = chunk.minOf { it.low },
                            close = chunk.last().close,
                            volume = chunk.sumOf { it.volume },
                            sma20 = sma20[lastGlobal],
                            sma50 = sma50[lastGlobal]
                        )
                    }
                }
                }
            }
            Row {
                TextButton(
                    enabled = bars.isNotEmpty() && (windowStart != 0 || windowCount != bars.size),
                    onClick = {
                        windowStart = 0
                        windowCount = bars.size.coerceAtLeast(1)
                        selected = bars.lastIndex.coerceAtLeast(0)
                    }
                ) { Text("Reset zoom") }
            }
            Canvas(
                Modifier.fillMaxWidth().height(if (showVolume) 250.dp else 205.dp)
                    .pointerInput(bars.size, windowStart, windowCount, buckets.size) {
                        detectTapGestures { point ->
                            if (buckets.isNotEmpty() && size.width > 0) {
                                val bucket = ((point.x / size.width) * buckets.size)
                                    .toInt().coerceIn(buckets.indices)
                                selected = buckets[bucket].lastIndex
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
                if (buckets.isEmpty()) return@Canvas
                val low = buckets.minOf { it.low }
                val high = buckets.maxOf { it.high }
                val spread = (high - low).takeIf { it > 0.0 } ?: 1.0
                val volumeHeight = if (showVolume) size.height * 0.20f else 0f
                val priceHeight = size.height - volumeHeight - 8f
                val step = size.width / buckets.size.coerceAtLeast(1)
                val bodyWidth = (step * 0.62f).coerceIn(1f, 9f)
                fun y(value: Double) =
                    priceHeight - ((value - low) / spread * (priceHeight - 12f)).toFloat() - 6f

                buckets.forEachIndexed { index, candle ->
                    val x = (index + 0.5f) * step
                    val rising = candle.close >= candle.open
                    val candleColor = if (rising) Color(0xFF27C58B) else Color(0xFFFF6678)
                    drawLine(candleColor, Offset(x, y(candle.high)), Offset(x, y(candle.low)), 1.2f)
                    val top = y(maxOf(candle.open, candle.close))
                    val bottom = y(minOf(candle.open, candle.close))
                    val bodyHeight = (bottom - top).coerceAtLeast(1.5f)
                    drawRect(
                        color = candleColor,
                        topLeft = Offset(x - bodyWidth / 2f, top),
                        size = androidx.compose.ui.geometry.Size(bodyWidth, bodyHeight)
                    )
                }

                fun drawAverage(selector: (CandleBucket) -> Double?, color: Color) {
                    val path = Path()
                    var started = false
                    buckets.forEachIndexed { index, candle ->
                        val value = selector(candle) ?: return@forEachIndexed
                        val x = (index + 0.5f) * step
                        if (!started) {
                            path.moveTo(x, y(value))
                            started = true
                        } else path.lineTo(x, y(value))
                    }
                    if (started) drawPath(path, color, style = Stroke(1.7f))
                }
                if (showSma20) drawAverage({ it.sma20 }, Color(0xFF8BD5FF))
                if (showSma50) drawAverage({ it.sma50 }, Color(0xFFFFD166))

                if (showVolume) {
                    val maxVolume = buckets.maxOf { it.volume }.coerceAtLeast(1L).toDouble()
                    buckets.forEachIndexed { index, candle ->
                        val x = (index + 0.5f) * step
                        val top = size.height - (candle.volume / maxVolume * volumeHeight).toFloat()
                        drawLine(
                            if (candle.close >= candle.open) Color(0xFF27C58B) else Color(0xFFFF6678),
                            Offset(x, size.height),
                            Offset(x, top),
                            strokeWidth = (step * 0.65f).coerceAtLeast(1f)
                        )
                    }
                }
                val selectedBucket = buckets.indexOfFirst { selected in it.firstIndex..it.lastIndex }
                    .takeIf { it >= 0 } ?: buckets.lastIndex
                val crosshairX = (selectedBucket + 0.5f) * step
                drawLine(Color.Gray, Offset(crosshairX, 0f), Offset(crosshairX, size.height), 1f)
            }
        }
        Text(
            "Tap to inspect OHLC · pinch to zoom · drag to pan · use Reset zoom to restore the full range",
            color = Muted,
            fontSize = 9.sp
        )
    }
}
