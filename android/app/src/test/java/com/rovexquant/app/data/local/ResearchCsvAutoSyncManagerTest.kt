package com.rovexquant.app.data.local

import com.rovexquant.app.domain.OhlcvBar
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ResearchCsvAutoSyncManagerTest {
    @Test
    fun mergesProviderCorrectionByIndianTradingDateInsteadOfExactTimestamp() {
        val csvBar = OhlcvBar(
            OffsetDateTime.parse("2026-11-24T15:30:00+05:30"),
            100.0, 105.0, 99.0, 103.0, 1_000L
        )
        val providerBar = OhlcvBar(
            OffsetDateTime.parse("2026-11-24T00:00:00+05:30"),
            101.0, 106.0, 100.0, 104.0, 1_100L
        )
        val merged = ResearchCsvAutoSyncManager.mergeDaily(listOf(csvBar), listOf(providerBar))
        assertEquals(1, merged.size)
        assertEquals(104.0, merged.single().close, 0.0)
        assertEquals(1_100L, merged.single().volume)
    }

    @Test
    fun appendsNewTradingDatesInChronologicalOrder() {
        val existing = OhlcvBar(
            OffsetDateTime.parse("2026-11-24T00:00:00+05:30"),
            100.0, 102.0, 99.0, 101.0, 1_000L
        )
        val next = OhlcvBar(
            OffsetDateTime.parse("2026-11-25T00:00:00+05:30"),
            101.0, 103.0, 100.0, 102.0, 1_100L
        )
        val merged = ResearchCsvAutoSyncManager.mergeDaily(listOf(existing), listOf(next))
        assertEquals(2, merged.size)
        assertEquals("2026-11-24", merged.first().timestamp.toLocalDate().toString())
        assertEquals("2026-11-25", merged.last().timestamp.toLocalDate().toString())
    }

    @Test
    fun rejectsProviderResponseWithDuplicateDailyCandles() {
        val first = OhlcvBar(
            OffsetDateTime.parse("2026-11-24T00:00:00+05:30"),
            100.0, 102.0, 99.0, 101.0, 1_000L
        )
        val duplicate = first.copy(close = 102.0)
        assertThrows(IllegalArgumentException::class.java) {
            ResearchCsvAutoSyncManager.mergeDaily(emptyList(), listOf(first, duplicate))
        }
    }
}
