package com.rovexquant.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.unit.sp
import com.rovexquant.app.domain.ChartCandle
import com.rovexquant.app.domain.ChartSeriesData
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ChartUp = Color(0xFF27C58B)
private val ChartDown = Color(0xFFFF6678)
private val ChartGrid = Color(0xFF344052)
private val ChartCrosshair = Color(0xFF9AA8B8)
private const val MAX_RENDERED_CHART_CANDLES = 2_000

/** Native Compose candlestick renderer; all input has already passed ChartSeriesMapper. */
@Composable
internal fun ValidatedCandlestickChart(
    data: ChartSeriesData,
    showVolume: Boolean,
    showSma20: Boolean,
    showSma50: Boolean
) {
    val candles = data.candles
    val initialCount = minOf(160, candles.size).coerceAtLeast(1)
    var windowStart by rememberSaveable(data.instrument.cacheIdentity, candles.size) {
        mutableIntStateOf((candles.size - initialCount).coerceAtLeast(0))
    }
    var windowCount by rememberSaveable(data.instrument.cacheIdentity, candles.size) {
        mutableIntStateOf(initialCount)
    }
    var selectedIndex by rememberSaveable(data.instrument.cacheIdentity, candles.size) {
        mutableIntStateOf(candles.lastIndex)
    }

    val renderLimit = minOf(candles.size, MAX_RENDERED_CHART_CANDLES).coerceAtLeast(1)
    val safeCount = windowCount.coerceIn(1, renderLimit)
    val safeStart = windowStart.coerceIn(0, (candles.size - safeCount).coerceAtLeast(0))
    val safeEnd = (safeStart + safeCount).coerceAtMost(candles.size)
    val visible = remember(candles, safeStart, safeEnd) {
        candles.subList(safeStart, safeEnd)
    }
    val averages by produceState<Pair<List<Double?>, List<Double?>>?>(initialValue = null, candles) {
        value = withContext(Dispatchers.Default) {
            movingAverage(candles, 20) to movingAverage(candles, 50)
        }
    }
    val sma20 = averages?.first.orEmpty()
    val sma50 = averages?.second.orEmpty()
    val selected = candles.getOrNull(selectedIndex.coerceIn(candles.indices))

    Column {
        selected?.let { candle ->
            Text(
                "${candle.time}   O ${candle.open.priceText()}  H ${candle.high.priceText()}  L ${candle.low.priceText()}  C ${candle.close.priceText()}",
                color = Color(0xFFE6EDF5),
                fontSize = 10.sp
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                enabled = candles.isNotEmpty() &&
                    (safeStart != (candles.size - initialCount).coerceAtLeast(0) || safeCount != initialCount),
                onClick = {
                    windowCount = initialCount
                    windowStart = (candles.size - initialCount).coerceAtLeast(0)
                    selectedIndex = candles.lastIndex
                }
            ) { Text("Reset view") }
            TextButton(
                enabled = candles.isNotEmpty() && safeCount != renderLimit,
                onClick = {
                    windowCount = renderLimit
                    windowStart = (candles.size - renderLimit).coerceAtLeast(0)
                }
            ) { Text("Max view · 2k") }
        }

        Canvas(
            Modifier.fillMaxWidth()
                .height(if (showVolume && data.volumeAvailable) 250.dp else 215.dp)
                .pointerInput(candles, safeStart, safeEnd) {
                    detectTapGestures { point ->
                        if (size.width > 0 && visible.isNotEmpty()) {
                            val step = size.width / visible.size
                            val local = (point.x / step).toInt().coerceIn(visible.indices)
                            selectedIndex = safeStart + local
                        }
                    }
                }
                .pointerInput(candles.size) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (candles.isEmpty() || size.width <= 0 || zoom <= 0f) {
                            return@detectTransformGestures
                        }
                        val oldCount = windowCount.coerceIn(1, minOf(candles.size, MAX_RENDERED_CHART_CANDLES))
                        val fraction = (centroid.x / size.width).coerceIn(0f, 1f)
                        val focalIndex = windowStart + fraction * oldCount
                        val minimumWindow = minOf(20, candles.size).coerceAtLeast(1)
                        val nextCount = (oldCount / zoom).roundToInt()
                            .coerceIn(minimumWindow, minOf(candles.size, MAX_RENDERED_CHART_CANDLES))
                        val panBars = (pan.x / size.width) * nextCount
                        val nextStart = (focalIndex - fraction * nextCount - panBars)
                            .roundToInt().coerceIn(0, (candles.size - nextCount).coerceAtLeast(0))
                        windowStart = nextStart
                        windowCount = nextCount
                        selectedIndex = (nextStart + (fraction * nextCount).roundToInt())
                            .coerceIn(nextStart, (nextStart + nextCount - 1).coerceAtMost(candles.lastIndex))
                    }
                }
        ) {
            if (visible.isEmpty()) return@Canvas
            val low = visible.minOf { it.low }
            val high = visible.maxOf { it.high }
            val spread = (high - low).takeIf { it > 0.0 } ?: max(high * 0.001, 0.01)
            val hasVolume = showVolume && data.volumeBars != null
            val volumeHeight = if (hasVolume) size.height * 0.22f else 0f
            val priceHeight = (size.height - volumeHeight - 8f).coerceAtLeast(1f)
            val step = size.width / visible.size
            val candleWidth = (step * 0.62f).coerceIn(1.2f, 12f)
            fun y(price: Double): Float =
                priceHeight - ((price - low) / spread * (priceHeight - 12f)).toFloat() - 6f

            for (line in 0..4) {
                val yLine = line * (priceHeight / 4f)
                drawLine(ChartGrid, Offset(0f, yLine), Offset(size.width, yLine), 0.7f)
            }

            visible.forEachIndexed { localIndex, candle ->
                val x = (localIndex + 0.5f) * step
                val rising = candle.close >= candle.open
                val candleColor = if (rising) ChartUp else ChartDown
                drawLine(
                    candleColor,
                    Offset(x, y(candle.high)),
                    Offset(x, y(candle.low)),
                    strokeWidth = 1.2f
                )
                val bodyTop = y(max(candle.open, candle.close))
                val bodyBottom = y(minOf(candle.open, candle.close))
                val bodyHeight = (bodyBottom - bodyTop).coerceAtLeast(1.5f)
                drawRect(
                    color = candleColor,
                    topLeft = Offset(x - candleWidth / 2f, bodyTop),
                    size = androidx.compose.ui.geometry.Size(candleWidth, bodyHeight)
                )
            }

            fun drawOverlay(values: List<Double?>, color: Color) {
                val path = Path()
                var started = false
                values.forEachIndexed { globalIndex, value ->
                    if (globalIndex !in safeStart until safeEnd || value == null) return@forEachIndexed
                    val x = (globalIndex - safeStart + 0.5f) * step
                    if (!started) {
                        path.moveTo(x, y(value))
                        started = true
                    } else {
                        path.lineTo(x, y(value))
                    }
                }
                if (started) drawPath(path, color, style = Stroke(width = 1.8f))
            }
            if (showSma20) drawOverlay(sma20, Color(0xFF55C9F3))
            if (showSma50) drawOverlay(sma50, Color(0xFFFFC857))

            if (hasVolume) {
                val volumes = data.volumeBars!!
                val visibleVolumes = volumes.subList(safeStart, safeEnd)
                val maxVolume = visibleVolumes.maxOfOrNull { it.value }?.coerceAtLeast(1L) ?: 1L
                visible.forEachIndexed { localIndex, candle ->
                    val volume = visibleVolumes[localIndex].value
                    val height = (volume.toDouble() / maxVolume * volumeHeight).toFloat()
                    val x = (localIndex + 0.5f) * step
                    drawRect(
                        color = if (candle.close >= candle.open) ChartUp.copy(alpha = 0.72f)
                            else ChartDown.copy(alpha = 0.72f),
                        topLeft = Offset(x - candleWidth / 2f, size.height - height),
                        size = androidx.compose.ui.geometry.Size(candleWidth, height)
                    )
                }
            }

            val selectedLocal = (selectedIndex - safeStart).coerceIn(0, visible.lastIndex)
            val crosshairX = (selectedLocal + 0.5f) * step
            drawLine(
                ChartCrosshair.copy(alpha = 0.8f),
                Offset(crosshairX, 0f),
                Offset(crosshairX, size.height),
                strokeWidth = 1f
            )
        }

        val first = visible.firstOrNull()?.time.orEmpty()
        val last = visible.lastOrNull()?.time.orEmpty()
        Text(
            "$first  —  $last   ·   ${visible.size} candles",
            color = Color(0xFF9AA8B8),
            fontSize = 9.sp
        )
        Text(
            if (data.volumeAvailable) "Candlesticks · volume bars · max 2,000 visible · pan to older history"
            else "Candlesticks · index volume not published · max 2,000 visible · pan to older history",
            color = Color(0xFF9AA8B8),
            fontSize = 9.sp
        )
    }
}

private fun movingAverage(candles: List<ChartCandle>, period: Int): List<Double?> {
    val result = MutableList<Double?>(candles.size) { null }
    var sum = 0.0
    candles.forEachIndexed { index, candle ->
        sum += candle.close
        if (index >= period) sum -= candles[index - period].close
        if (index >= period - 1) result[index] = sum / period
    }
    return result
}

private fun Double.priceText(): String = String.format(java.util.Locale.US, "%.2f", this)
