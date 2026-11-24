package com.rovexquant.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TechnicalIndicatorsTest {
    private fun fixture(
        barCount: Int = 3,
        sma: List<Double?> = listOf(null, 2.0, 3.0)
    ) = TechnicalIndicators(
        startIndex = 7,
        barCount = barCount,
        sma = sma,
        ema = listOf(null, 2.0, 3.0),
        rsi = listOf(null, 50.0, 55.0),
        macdLine = listOf(null, null, 0.5),
        macdSignal = listOf(null, null, 0.4),
        macdHistogram = listOf(null, null, 0.1),
        bollingerUpper = listOf(null, null, 4.0),
        bollingerMiddle = listOf(null, null, 3.0),
        bollingerLower = listOf(null, null, 2.0),
        atr = listOf(null, 1.0, 1.1),
        adx = listOf(null, null, 25.0)
    )

    @Test
    fun preservesWarmupNullsAndSourceAlignment() {
        val result = fixture()
        assertEquals(7, result.startIndex)
        assertEquals(10, result.endExclusive)
        assertEquals(listOf(null, 2.0, 3.0), result.sma)
        assertEquals(null, result.macdLine[1])
    }

    @Test
    fun rejectsMisalignedIndicatorArrays() {
        assertThrows(IllegalArgumentException::class.java) {
            fixture(sma = listOf(null, 2.0))
        }
    }

    @Test
    fun rejectsNonFiniteIndicatorValues() {
        assertThrows(IllegalArgumentException::class.java) {
            fixture(sma = listOf(null, Double.NaN, 3.0))
        }
    }

    @Test
    fun validatesIndicatorPeriods() {
        assertThrows(IllegalArgumentException::class.java) {
            TechnicalIndicatorPeriods(rsi = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TechnicalIndicatorPeriods(bollingerDeviations = Double.POSITIVE_INFINITY)
        }
    }
}
