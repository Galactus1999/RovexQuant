package com.rovexquant.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InstrumentIdentityTest {
    @Test fun nifty50RoutesToBroadMarketIndexProvider() {
        val identity = InstrumentCatalog.resolve(" nifty 50 ")
        assertEquals(InstrumentType.BROAD_MARKET_INDEX, identity.instrumentType)
        assertEquals(OhlcvProvider.UPSTOX_V3_HISTORICAL, identity.provider)
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

    @Test fun dashboardOilAndGasAliasMapsToOfficialIndexName() {
        val identity = InstrumentCatalog.resolve("NIFTY OIL & GAS")
        assertEquals(InstrumentType.SECTOR_INDEX, identity.instrumentType)
        assertEquals("NIFTY OIL AND GAS INDEX", identity.providerSymbol)
        assertEquals(true, InstrumentCatalog.isRegisteredIndex(identity))
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

    @Test fun slashBearingOfficialIndexNameRemainsSearchable() {
        val identity = InstrumentCatalog.resolve("NIFTY FINANCIAL SERVICES 25/50")
        assertEquals(InstrumentType.SECTOR_INDEX, identity.instrumentType)
        assertEquals("NIFTY FINANCIAL SERVICES 25/50", identity.canonicalSymbol)
    }

    @Test fun intradayEquityAndIndexUseUpstoxV3WithDistinctCacheKeys() {
        val daily = InstrumentCatalog.resolve("RELIANCE")
        val minute = InstrumentCatalog.resolve("RELIANCE", interval = OhlcvInterval.MINUTE_1)
        val fourHourIndex = InstrumentCatalog.resolve("NIFTY BANK", interval = OhlcvInterval.HOUR_4)

        assertEquals(OhlcvProvider.UPSTOX_V3_INTRADAY, minute.provider)
        assertEquals("minutes", minute.interval.apiUnit)
        assertEquals(1, minute.interval.apiInterval)
        assertEquals(OhlcvProvider.UPSTOX_V3_INTRADAY, fourHourIndex.provider)
        assertEquals("hours", fourHourIndex.interval.apiUnit)
        assertEquals(4, fourHourIndex.interval.apiInterval)
        assertEquals(false, minute.cacheIdentity == daily.cacheIdentity)
    }
}
