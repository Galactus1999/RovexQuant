package com.rovexquant.app.domain

import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OhlcvCsvResearchTest {
    private fun risingCsv(rows: Int = 55): String {
        val start = OffsetDateTime.parse("2025-01-01T09:15:00+05:30")
        return buildString {
            append("timestamp,open,high,low,close,volume\n")
            repeat(rows) { index ->
                val close = 100.0 + index
                append(start.plusMinutes(index.toLong()).toString()).append(',')
                append(close - 0.5).append(',').append(close + 1.0).append(',')
                append(close - 1.0).append(',').append(close).append(",1000\n")
            }
        }
    }

    @Test fun parsesAndSummarizesTimezoneAwareOhlcv() {
        val summary = OhlcvCsvResearch.summarize(OhlcvCsvResearch.parse(risingCsv()))
        assertEquals(55, summary.rows)
        assertEquals(154.0, summary.latestClose, 0.0001)
        assertEquals(144.5, summary.sma20!!, 0.0001)
        assertEquals(129.5, summary.sma50!!, 0.0001)
        assertEquals(100.0, summary.rsi14!!, 0.0001)
        assertEquals("Bullish trend alignment", summary.trend)
        assertEquals("No 20-bar breakout", summary.donchian20)
        assertEquals(1.0, summary.relativeVolume20!!, 0.0001)
    }

    @Test fun detectsDonchianBreakoutAndRelativeVolumeExpansion() {
        val start = OffsetDateTime.parse("2025-01-01T09:15:00Z")
        val csv = buildString {
            append("timestamp,open,high,low,close,volume\n")
            repeat(20) { index ->
                append(start.plusMinutes(index.toLong()).toString()).append(",100,110,90,100,1000\n")
            }
            append(start.plusMinutes(20).toString()).append(",110,122,109,121,3000")
        }
        val summary = OhlcvCsvResearch.summarize(OhlcvCsvResearch.parse(csv))
        assertEquals("Bullish breakout", summary.donchian20)
        assertEquals(3.0, summary.relativeVolume20!!, 0.0001)
    }

    @Test fun rejectsTimezoneNaiveTimestamps() {
        val csv = "timestamp,open,high,low,close,volume\n2025-01-01T09:15:00,100,102,99,101,1000"
        assertTrue(runCatching { OhlcvCsvResearch.parse(csv) }.exceptionOrNull()?.message!!.contains("timezone"))
    }

    @Test fun rejectsInvalidOhlcAndDuplicateTimestamps() {
        val invalid = "timestamp,open,high,low,close,volume\n2025-01-01T09:15:00Z,100,98,99,101,1000"
        assertTrue(runCatching { OhlcvCsvResearch.parse(invalid) }.isFailure)
        val duplicate = "timestamp,open,high,low,close,volume\n2025-01-01T09:15:00Z,100,102,99,101,1000\n2025-01-01T09:15:00Z,101,103,100,102,900"
        assertTrue(runCatching { OhlcvCsvResearch.parse(duplicate) }.isFailure)
    }

    @Test fun indicatorWarmupIsExplicitForShortDatasets() {
        val csv = "timestamp,open,high,low,close,volume\n2025-01-01T09:15:00Z,100,102,99,101,1000"
        val summary = OhlcvCsvResearch.summarize(OhlcvCsvResearch.parse(csv))
        assertNull(summary.sma20)
        assertNull(summary.sma50)
        assertNull(summary.rsi14)
        assertEquals("Warming up", summary.donchian20)
        assertNull(summary.relativeVolume20)
    }
}
