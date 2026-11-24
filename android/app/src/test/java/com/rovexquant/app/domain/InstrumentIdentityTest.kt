package com.rovexquant.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InstrumentIdentityTest {
    @Test fun nifty50RoutesToBroadMarketIndexProvider() {
        val identity = InstrumentCatalog.resolve(" nifty 50 ")
        assertEquals(InstrumentType.BROAD_MARKET_INDEX, identity.instrumentType)
        assertEquals(OhlcvProvider.NSE_INDICES_PORTAL, identity.provider)
        assertEquals("NIFTY 50", identity.providerSymbol)
    }

    @Test fun niftyBankRoutesToSectorIndexProvider() {
        val identity = InstrumentCatalog.resolve("NIFTY BANK")
        assertEquals(InstrumentType.SECTOR_INDEX, identity.instrumentType)
        assertEquals(OhlcvProvider.NSE_INDICES_PORTAL, identity.provider)
    }

    @Test fun sectorIndexRoutesToNseIndicesProvider() {
        val identity = InstrumentCatalog.resolve("NIFTY IT")
        assertEquals(InstrumentType.SECTOR_INDEX, identity.instrumentType)
        assertEquals(OhlcvProvider.NSE_INDICES_PORTAL, identity.provider)
    }

    @Test fun ordinaryEquityRemainsStrictAndUsesTejHq() {
        val identity = InstrumentCatalog.resolve("RELIANCE")
        assertEquals(InstrumentType.EQUITY, identity.instrumentType)
        assertEquals(OhlcvProvider.TEJHQ_EOD, identity.provider)
        assertEquals("RELIANCE", identity.providerSymbol)
    }

    @Test fun unknownNiftyIndexCannotFallThroughToEquity() {
        assertThrows(IllegalArgumentException::class.java) {
            InstrumentCatalog.resolve("NIFTY UNKNOWN")
        }
    }

    @Test fun equitySuffixAndWhitespaceRemainInvalid() {
        assertThrows(IllegalArgumentException::class.java) { InstrumentCatalog.resolve("RELIANCE.NS") }
        assertThrows(IllegalArgumentException::class.java) { InstrumentCatalog.resolve("NIFTY 100") }
    }

    @Test fun bseEquityUsesEquityProvider() {
        val identity = InstrumentCatalog.resolve("TCS", "BSE")
        assertEquals("BSE", identity.exchange)
        assertEquals(InstrumentType.EQUITY, identity.instrumentType)
        assertEquals(OhlcvProvider.TEJHQ_EOD, identity.provider)
    }

    @Test fun typedCacheIdentitySeparatesInstrumentTypeAndInterval() {
        val equity = InstrumentCatalog.resolve("RELIANCE")
        val index = InstrumentCatalog.resolve("NIFTY 50")
        assertEquals(false, equity.cacheIdentity == index.cacheIdentity)
        assertEquals("NSE_BROAD_MARKET_INDEX_1d_NIFTY 50", index.cacheIdentity)
    }
}
