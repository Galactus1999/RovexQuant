package com.rovexquant.app.data.remote

import com.rovexquant.app.data.model.MarketTick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketStreamStatusTest {
    private fun tick(
        quality: String,
        key: String = "NSE_INDEX|Nifty 50",
        price: Double = 25_000.0
    ) = MarketTick(
        instrumentKey = key,
        lastPrice = price,
        previousClose = 24_900.0,
        changePercent = 0.40,
        exchangeTimestamp = "2026-11-23T10:30:00Z",
        receivedAt = "2026-11-23T17:59:42Z",
        quality = quality
    )

    @Test fun staleSnapshotNeverClaimsLiveData() {
        assertEquals(
            "Connected · stale market snapshot",
            marketStreamStatusForQuotes(listOf(tick("STALE")))
        )
    }

    @Test fun validQuoteCanMarkStreamLive() {
        assertEquals(
            "Live · Upstox V3",
            marketStreamStatusForQuotes(listOf(tick("VALID")))
        )
    }

    @Test fun emptySnapshotRemainsWaitingForQuotes() {
        assertEquals(
            "Connected · waiting for verified quotes",
            marketStreamStatusForQuotes(emptyList())
        )
    }

    @Test fun latestTickWinsWhenDashboardBatchIsCoalesced() {
        val merged = linkedMapOf<String, MarketTick>()
        mergeLatestMarketTicks(merged, listOf(tick("VALID"), tick("VALID", "NSE_EQ|RELIANCE", 1_250.0)))
        mergeLatestMarketTicks(merged, listOf(tick("VALID", price = 25_010.0)))

        assertEquals(2, merged.size)
        assertEquals(25_010.0, merged["NSE_INDEX|Nifty 50"]?.lastPrice ?: 0.0, 0.0)
        assertEquals(1_250.0, merged["NSE_EQ|RELIANCE"]?.lastPrice ?: 0.0, 0.0)
    }

    @Test fun coalescingRetainsEveryInstrumentButOnlyItsLatestSnapshot() {
        val merged = linkedMapOf<String, MarketTick>()
        mergeLatestMarketTicks(
            merged,
            listOf(tick("VALID"), tick("VALID", "NSE_EQ|RELIANCE", 1_250.0), tick("VALID", price = 25_005.0))
        )

        assertEquals(setOf("NSE_INDEX|Nifty 50", "NSE_EQ|RELIANCE"), merged.keys)
        assertTrue(merged.values.all { it.quality == "VALID" })
    }
}
