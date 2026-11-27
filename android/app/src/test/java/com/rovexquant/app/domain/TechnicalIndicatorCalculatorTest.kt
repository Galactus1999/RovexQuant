package com.rovexquant.app.domain

import com.rovexquant.app.data.model.TechnicalIndicatorPeriods
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TechnicalIndicatorCalculatorTest {
    @Test fun rsiUsesWilderSmoothingAndHandlesFlatUpAndDownSeries() {
        val up = TechnicalIndicatorCalculator.calculate(bars(List(60) { 100.0 + it }), 120)
        val down = TechnicalIndicatorCalculator.calculate(bars(List(60) { 200.0 - it }), 120)
        val flat = TechnicalIndicatorCalculator.calculate(bars(List(60) { 150.0 }), 120)

        assertEquals(100.0, up.rsi[14]!!, 1e-9)
        assertEquals(0.0, down.rsi[14]!!, 1e-9)
        assertEquals(50.0, flat.rsi[14]!!, 1e-9)
        assertNull(up.rsi[13])
        assertEquals(120, up.startIndex)
    }

    @Test fun macdSignalAndHistogramAreAlignedToSourceBars() {
        val result = TechnicalIndicatorCalculator.calculate(
            bars(List(100) { 100.0 + it * 0.7 + kotlin.math.sin(it / 4.0) }), 40
        )

        assertNull(result.macdLine[24])
        assertNotNull(result.macdLine[25])
        assertNull(result.macdSignal[32])
        assertNotNull(result.macdSignal[33])
        for (index in 33 until result.barCount) {
            val line = result.macdLine[index]
            val signal = result.macdSignal[index]
            val histogram = result.macdHistogram[index]
            if (line != null && signal != null && histogram != null) {
                assertEquals(line - signal, histogram, 1e-8)
            }
        }
    }

    @Test fun atrAndAdxRespectWarmupAndExpectedBounds() {
        val result = TechnicalIndicatorCalculator.calculate(
            bars(List(100) { 100.0 + it }), 0
        )

        assertNull(result.atr[13])
        assertEquals(2.0, result.atr[14]!!, 1e-9)
        assertNull(result.adx[26])
        assertNotNull(result.adx[27])
        assertTrue(result.adx.filterNotNull().all { it in 0.0..100.0 })
    }

    @Test fun bollingerBandsRemainOrderedAndWarmupIsNull() {
        val result = TechnicalIndicatorCalculator.calculate(
            bars(List(80) { 100.0 + it + kotlin.math.sin(it.toDouble()) }), 0
        )

        assertNull(result.bollingerMiddle[18])
        for (index in 19 until result.barCount) {
            assertTrue(result.bollingerLower[index]!! <= result.bollingerMiddle[index]!!)
            assertTrue(result.bollingerMiddle[index]!! <= result.bollingerUpper[index]!!)
        }
    }

    private fun bars(closes: List<Double>) = closes.mapIndexed { index, close ->
        OhlcvBar(
            timestamp = OffsetDateTime.parse("2024-01-01T15:30:00+05:30").plusDays(index.toLong()),
            open = close,
            high = close + 1.0,
            low = close - 1.0,
            close = close,
            volume = 1_000L
        )
    }
}
