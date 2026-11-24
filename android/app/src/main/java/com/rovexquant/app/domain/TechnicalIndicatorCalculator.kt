package com.rovexquant.app.domain

import com.rovexquant.app.data.model.TechnicalIndicatorPeriods
import com.rovexquant.app.data.model.TechnicalIndicators
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Deterministic offline indicator engine. Formulas and warm-up indexing mirror the
 * backend indicator contract so a temporary network failure never disables chart tools.
 */
object TechnicalIndicatorCalculator {
    fun calculate(
        bars: List<OhlcvBar>,
        startIndex: Int,
        periods: TechnicalIndicatorPeriods = TechnicalIndicatorPeriods()
    ): TechnicalIndicators {
        require(bars.isNotEmpty() && bars.size <= 10_000) {
            "Indicator calculations require between 1 and 10,000 OHLC bars."
        }
        require(startIndex >= 0) { "Indicator source offset cannot be negative." }
        bars.forEachIndexed { index, bar ->
            require(
                bar.open.isFinite() && bar.open > 0.0 &&
                    bar.high.isFinite() && bar.high > 0.0 &&
                    bar.low.isFinite() && bar.low > 0.0 &&
                    bar.close.isFinite() && bar.close > 0.0 &&
                    bar.high >= maxOf(bar.open, bar.close) &&
                    bar.low <= minOf(bar.open, bar.close) &&
                    bar.high >= bar.low
            ) { "OHLC bar ${index + 1} is invalid for indicator calculation." }
        }

        val close = bars.map { it.close }
        val high = bars.map { it.high }
        val low = bars.map { it.low }
        val macd = macd(close, 12, 26, 9)
        val bands = bollinger(close, periods.bollinger, periods.bollingerDeviations)
        return TechnicalIndicators(
            startIndex = startIndex,
            barCount = bars.size,
            sma = sma(close, periods.sma),
            ema = ema(close, periods.ema),
            rsi = rsi(close, periods.rsi),
            macdLine = macd.first,
            macdSignal = macd.second,
            macdHistogram = macd.third,
            bollingerUpper = bands.first,
            bollingerMiddle = bands.second,
            bollingerLower = bands.third,
            atr = atr(high, low, close, periods.atr),
            adx = adx(high, low, close, periods.adx)
        )
    }

    private fun sma(values: List<Double>, period: Int): List<Double?> {
        val out = MutableList<Double?>(values.size) { null }
        var total = 0.0
        values.forEachIndexed { index, value ->
            total += value
            if (index >= period) total -= values[index - period]
            if (index >= period - 1) out[index] = total / period
        }
        return out
    }

    private fun ema(values: List<Double>, period: Int): List<Double?> {
        val out = MutableList<Double?>(values.size) { null }
        if (values.size < period) return out
        val alpha = 2.0 / (period + 1.0)
        var previous = values.take(period).average()
        out[period - 1] = previous
        for (index in period until values.size) {
            previous = alpha * values[index] + (1.0 - alpha) * previous
            out[index] = previous
        }
        return out
    }

    private fun rsi(closes: List<Double>, period: Int): List<Double?> {
        val out = MutableList<Double?>(closes.size) { null }
        if (closes.size <= period) return out
        val gains = (1 until closes.size).map { maxOf(closes[it] - closes[it - 1], 0.0) }
        val losses = (1 until closes.size).map { maxOf(closes[it - 1] - closes[it], 0.0) }
        var averageGain = gains.take(period).average()
        var averageLoss = losses.take(period).average()
        fun value(gain: Double, loss: Double): Double = when {
            loss == 0.0 && gain > 0.0 -> 100.0
            loss == 0.0 -> 50.0
            gain == 0.0 -> 0.0
            else -> 100.0 - 100.0 / (1.0 + gain / loss)
        }
        out[period] = value(averageGain, averageLoss)
        for (index in period + 1 until closes.size) {
            val changeIndex = index - 1
            averageGain = ((period - 1) * averageGain + gains[changeIndex]) / period
            averageLoss = ((period - 1) * averageLoss + losses[changeIndex]) / period
            out[index] = value(averageGain, averageLoss)
        }
        return out
    }

    private fun macd(
        closes: List<Double>, fast: Int, slow: Int, signal: Int
    ): Triple<List<Double?>, List<Double?>, List<Double?>> {
        val fastEma = ema(closes, fast)
        val slowEma = ema(closes, slow)
        val line = MutableList<Double?>(closes.size) { null }
        val validIndices = mutableListOf<Int>()
        val validValues = mutableListOf<Double>()
        closes.indices.forEach { index ->
            val fastValue = fastEma[index]
            val slowValue = slowEma[index]
            if (fastValue != null && slowValue != null) {
                val value = fastValue - slowValue
                line[index] = value
                validIndices += index
                validValues += value
            }
        }
        val signalValues = ema(validValues, signal)
        val signalLine = MutableList<Double?>(closes.size) { null }
        val histogram = MutableList<Double?>(closes.size) { null }
        validIndices.forEachIndexed { index, sourceIndex ->
            val value = signalValues[index]
            signalLine[sourceIndex] = value
            if (value != null) histogram[sourceIndex] = line[sourceIndex]!! - value
        }
        return Triple(line, signalLine, histogram)
    }

    private fun bollinger(
        closes: List<Double>, period: Int, deviations: Double
    ): Triple<List<Double?>, List<Double?>, List<Double?>> {
        val upper = MutableList<Double?>(closes.size) { null }
        val middle = MutableList<Double?>(closes.size) { null }
        val lower = MutableList<Double?>(closes.size) { null }
        for (index in period - 1 until closes.size) {
            val window = closes.subList(index - period + 1, index + 1)
            val mean = window.average()
            val variance = window.sumOf { (it - mean) * (it - mean) } / period
            val width = deviations * sqrt(variance)
            middle[index] = mean
            upper[index] = mean + width
            lower[index] = mean - width
        }
        return Triple(upper, middle, lower)
    }

    private fun trueRanges(
        highs: List<Double>, lows: List<Double>, closes: List<Double>
    ): List<Double?> = List(closes.size) { index ->
        if (index == 0) null else maxOf(
            highs[index] - lows[index],
            abs(highs[index] - closes[index - 1]),
            abs(lows[index] - closes[index - 1])
        )
    }

    private fun atr(
        highs: List<Double>, lows: List<Double>, closes: List<Double>, period: Int
    ): List<Double?> {
        val ranges = trueRanges(highs, lows, closes)
        val out = MutableList<Double?>(closes.size) { null }
        val valid = ranges.drop(1).filterNotNull()
        if (valid.size < period) return out
        var previous = valid.take(period).average()
        out[period] = previous
        for (index in period + 1 until closes.size) {
            previous = ((period - 1) * previous + ranges[index]!!) / period
            out[index] = previous
        }
        return out
    }

    private fun adx(
        highs: List<Double>, lows: List<Double>, closes: List<Double>, period: Int
    ): List<Double?> {
        val size = closes.size
        val out = MutableList<Double?>(size) { null }
        if (size <= 2 * period) return out
        val ranges = trueRanges(highs, lows, closes)
        val plusDm = MutableList(size) { 0.0 }
        val minusDm = MutableList(size) { 0.0 }
        for (index in 1 until size) {
            val up = highs[index] - highs[index - 1]
            val down = lows[index - 1] - lows[index]
            plusDm[index] = if (up > down && up > 0.0) up else 0.0
            minusDm[index] = if (down > up && down > 0.0) down else 0.0
        }
        var trSum = (1..period).sumOf { ranges[it] ?: 0.0 }
        var plusSum = (1..period).sumOf { plusDm[it] }
        var minusSum = (1..period).sumOf { minusDm[it] }
        val dxValues = mutableListOf<Double>()
        val dxIndices = mutableListOf<Int>()
        for (index in period until size) {
            if (index > period) {
                trSum = trSum - trSum / period + (ranges[index] ?: 0.0)
                plusSum = plusSum - plusSum / period + plusDm[index]
                minusSum = minusSum - minusSum / period + minusDm[index]
            }
            val plusDi = if (trSum != 0.0) 100.0 * plusSum / trSum else 0.0
            val minusDi = if (trSum != 0.0) 100.0 * minusSum / trSum else 0.0
            val denominator = plusDi + minusDi
            dxValues += if (denominator != 0.0) 100.0 * abs(plusDi - minusDi) / denominator else 0.0
            dxIndices += index
        }
        if (dxValues.size < period) return out
        var current = dxValues.take(period).average()
        out[dxIndices[period - 1]] = current
        for (index in period until dxValues.size) {
            current = ((period - 1) * current + dxValues[index]) / period
            out[dxIndices[index]] = current
        }
        return out
    }
}
