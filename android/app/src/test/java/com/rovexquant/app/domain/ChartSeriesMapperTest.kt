package com.rovexquant.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.OffsetDateTime

class ChartSeriesMapperTest {
    private fun bar(
        date: String,
        open: Double = 100.0,
        high: Double = 110.0,
        low: Double = 95.0,
        close: Double = 105.0,
        volume: Long = 1_000L
    ) = OhlcvBar(
        timestamp = OffsetDateTime.parse("${date}T15:30:00+05:30"),
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume
    )

    @Test
    fun equityMapsCandlesAndRealVolume() {
        val instrument = InstrumentCatalog.resolve("RELIANCE")
        val mapped = ChartSeriesMapper.map(
            MarketOhlcvSeries(
                instrument = instrument,
                bars = listOf(bar("2025-01-01"), bar("2025-01-02", close = 106.0)),
                volumeAvailable = true,
                adjustmentStatus = "Raw EOD"
            )
        )

        assertEquals("RELIANCE", mapped.instrument.canonicalSymbol)
        assertEquals(2, mapped.candles.size)
        assertEquals("2025-01-01", mapped.candles.first().time)
        assertEquals(105.0, mapped.candles.first().close, 0.0)
        assertEquals(1_000L, mapped.volumeBars?.first()?.value)
        assertTrue(mapped.volumeAvailable)
    }

    @Test
    fun indexNeverProducesVolumeSeries() {
        val instrument = InstrumentCatalog.resolve("NIFTY 50")
        val mapped = ChartSeriesMapper.map(
            MarketOhlcvSeries(
                instrument = instrument,
                bars = listOf(bar("2025-01-01", volume = 0L)),
                volumeAvailable = false,
                adjustmentStatus = "Price Return Index OHLC"
            )
        )

        assertFalse(mapped.volumeAvailable)
        assertNull(mapped.volumeBars)
        assertEquals(1, mapped.candles.size)
    }

    @Test
    fun rejectsIndexWhenProviderClaimsVolume() {
        val instrument = InstrumentCatalog.resolve("NIFTY 50")
        assertThrows(IllegalArgumentException::class.java) {
            ChartSeriesMapper.map(
                MarketOhlcvSeries(
                    instrument = instrument,
                    bars = listOf(bar("2025-01-01")),
                    volumeAvailable = true,
                    adjustmentStatus = "Invalid"
                )
            )
        }
    }

    @Test
    fun rejectsOutOfOrderAndDuplicateDailyBars() {
        val instrument = InstrumentCatalog.resolve("RELIANCE")
        val outOfOrder = listOf(bar("2025-01-02"), bar("2025-01-01"))
        assertThrows(IllegalArgumentException::class.java) {
            ChartSeriesMapper.map(MarketOhlcvSeries(
                instrument = instrument, bars = outOfOrder,
                volumeAvailable = true, adjustmentStatus = "Raw"
            ))
        }

        val duplicateDate = listOf(
            bar("2025-01-01"),
            OhlcvBar(
                OffsetDateTime.parse("2025-01-01T16:00:00+05:30"),
                100.0, 110.0, 95.0, 105.0, 1_000L
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            ChartSeriesMapper.map(MarketOhlcvSeries(
                instrument = instrument, bars = duplicateDate,
                volumeAvailable = true, adjustmentStatus = "Raw"
            ))
        }
    }

    @Test
    fun rejectsInvalidOhlcAndNegativeVolume() {
        val instrument = InstrumentCatalog.resolve("RELIANCE")
        assertThrows(IllegalArgumentException::class.java) {
            ChartSeriesMapper.map(
                MarketOhlcvSeries(
                    instrument = instrument, bars = listOf(bar("2025-01-01", high = 99.0)),
                    volumeAvailable = true, adjustmentStatus = "Raw"
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChartSeriesMapper.map(
                MarketOhlcvSeries(
                    instrument = instrument, bars = listOf(bar("2025-01-01", volume = -1L)),
                    volumeAvailable = true, adjustmentStatus = "Raw"
                )
            )
        }
    }

    @Test
    fun rejectsForgedInstrumentProviderMapping() {
        val forged = InstrumentIdentity(
            exchange = "NSE",
            canonicalSymbol = "NIFTY 50",
            instrumentType = InstrumentType.BROAD_MARKET_INDEX,
            provider = OhlcvProvider.TEJHQ_EOD,
            providerSymbol = "NIFTY 50"
        )
        assertThrows(IllegalArgumentException::class.java) {
            ChartSeriesMapper.map(
                MarketOhlcvSeries(
                    instrument = forged, bars = listOf(bar("2025-01-01")),
                    volumeAvailable = false, adjustmentStatus = "Invalid route"
                )
            )
        }
    }
}
