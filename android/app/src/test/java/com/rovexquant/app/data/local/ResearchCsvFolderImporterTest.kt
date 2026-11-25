package com.rovexquant.app.data.local

import com.rovexquant.app.domain.InstrumentType
import com.rovexquant.app.domain.OhlcvBar
import com.rovexquant.app.domain.OhlcvProvider
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ResearchCsvFolderImporterTest {
    @Test
    fun resolvesNseEquityFilenameWithoutTouchingProviderCache() {
        val identity = ResearchCsvFolderImporter.resolveInstrumentFromFileName("NSE_RELIANCE.csv")
        assertEquals("NSE", identity.exchange)
        assertEquals("RELIANCE", identity.canonicalSymbol)
        assertEquals(InstrumentType.EQUITY, identity.instrumentType)
        assertEquals(OhlcvProvider.TEJHQ_EOD, identity.provider)
    }

    @Test
    fun resolvesBseEquityFilename() {
        val identity = ResearchCsvFolderImporter.resolveInstrumentFromFileName("BSE_TCS_DAILY.csv")
        assertEquals("BSE", identity.exchange)
        assertEquals("TCS", identity.canonicalSymbol)
    }

    @Test
    fun resolvesNiftySectorFilenameToOfficialDailyProvider() {
        val identity = ResearchCsvFolderImporter.resolveInstrumentFromFileName("NSE_NIFTY_BANK_1D.csv")
        assertEquals("NIFTY BANK", identity.canonicalSymbol)
        assertEquals(OhlcvProvider.UPSTOX_V3_HISTORICAL, identity.provider)
    }

    @Test
    fun rejectsIntradayBarsInDailyFolderImport() {
        val bars = listOf(
            bar("2026-11-24T09:15:00+05:30"),
            bar("2026-11-24T09:20:00+05:30")
        )
        assertThrows(IllegalArgumentException::class.java) {
            ResearchCsvFolderImporter.validateDailyBars(bars)
        }
    }

    @Test
    fun acceptsOneBarPerTradingDate() {
        ResearchCsvFolderImporter.validateDailyBars(
            listOf(bar("2026-11-23T15:30:00+05:30"), bar("2026-11-24T15:30:00+05:30"))
        )
    }

    private fun bar(timestamp: String) = OhlcvBar(
        timestamp = OffsetDateTime.parse(timestamp),
        open = 100.0,
        high = 102.0,
        low = 99.0,
        close = 101.0,
        volume = 1000L
    )
}
