package com.rovexquant.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OhlcvCacheIdentityTest {
    @Test fun cacheIdentitySeparatesExchangeAndSymbol() {
        val relianceNse = TejHqOhlcvStore.cacheFileName("nse", "reliance")
        val relianceBse = TejHqOhlcvStore.cacheFileName("bse", "reliance")
        val tcsNse = TejHqOhlcvStore.cacheFileName("nse", "tcs")

        assertEquals("ohlcv_NSE_RELIANCE.csv", relianceNse)
        assertNotEquals(relianceNse, relianceBse)
        assertNotEquals(relianceNse, tcsNse)
    }

    @Test fun cacheIdentityNormalizesCaseAndWhitespace() {
        assertEquals(
            TejHqOhlcvStore.cacheFileName("NSE", "RELIANCE"),
            TejHqOhlcvStore.cacheFileName(" nse ", " reliance ")
        )
    }

    @Test fun typedCacheIdentityIncludesInstrumentTypeAndInterval() {
        val equity = com.rovexquant.app.domain.InstrumentCatalog.resolve("RELIANCE")
        val index = com.rovexquant.app.domain.InstrumentCatalog.resolve("NIFTY 50")
        val equityFile = TejHqOhlcvStore.cacheFileName(equity)
        val indexFile = TejHqOhlcvStore.cacheFileName(index)

        assertEquals("ohlcv_NSE_EQUITY_1d_RELIANCE.csv", equityFile)
        assertEquals("ohlcv_NSE_BROAD_MARKET_INDEX_1d_NIFTY_50.csv", indexFile)
        assertNotEquals(equityFile, indexFile)
    }

    @Test fun cacheIdentityRejectsPathTraversalAndInvalidSymbols() {
        listOf("../NSE", "NSE/../../cache", "").forEach { exchange ->
            assert(org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                TejHqOhlcvStore.cacheFileName(exchange, "RELIANCE")
            } != null)
        }
        listOf("../secret", "NIFTY 50", "").forEach { symbol ->
            assert(org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                TejHqOhlcvStore.cacheFileName("NSE", symbol)
            } != null)
        }
    }
}
