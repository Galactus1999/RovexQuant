package com.rovexquant.app.data.remote

import com.rovexquant.app.data.model.MarketTick
import org.junit.Assert.assertEquals
import org.junit.Test

class MarketStreamStatusTest {
    private fun tick(quality: String) = MarketTick(
        instrumentKey = "NSE_INDEX|Nifty 50",
        lastPrice = 25_000.0,
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
}
