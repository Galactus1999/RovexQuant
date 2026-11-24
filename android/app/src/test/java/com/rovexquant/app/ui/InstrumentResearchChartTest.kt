package com.rovexquant.app.ui

import com.rovexquant.app.domain.OhlcvBar
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class InstrumentResearchChartTest {
    @Test fun simpleMovingAverageHonoursWarmupAndRollingWindow() {
        val bars = listOf(10.0, 20.0, 30.0, 40.0, 50.0).mapIndexed { index, close ->
            bar(index, close)
        }

        val values = simpleMovingAverage(bars, 3)

        assertEquals(5, values.size)
        assertNull(values[0])
        assertNull(values[1])
        assertEquals(20.0, values[2]!!, 0.000001)
        assertEquals(30.0, values[3]!!, 0.000001)
        assertEquals(40.0, values[4]!!, 0.000001)
    }

    @Test fun simpleMovingAverageReturnsNoValuesBeforeWarmup() {
        val values = simpleMovingAverage(listOf(bar(0, 12.0), bar(1, 18.0)), 3)

        assertEquals(listOf(null, null), values)
    }

    @Test fun simpleMovingAverageRejectsNonPositivePeriod() {
        assertThrows(IllegalArgumentException::class.java) {
            simpleMovingAverage(listOf(bar(0, 12.0)), 0)
        }
    }

    private fun bar(index: Int, close: Double) = OhlcvBar(
        timestamp = OffsetDateTime.parse("2024-01-01T15:30:00+05:30").plusDays(index.toLong()),
        open = close,
        high = close + 1.0,
        low = close - 1.0,
        close = close,
        volume = 1_000L
    )
}
