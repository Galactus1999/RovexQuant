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
    fun normalizesTimestampsToIndianExchangeTradingDate() {
        val instrument = InstrumentCatalog.resolve("NIFTY 50")
        val utcTimestamp = OhlcvBar(
            OffsetDateTime.parse("2025-01-01T20:00:00Z"),
            100.0, 110.0, 95.0, 105.0, 0L
        )
        val mapped = ChartSeriesMapper.map(
            MarketOhlcvSeries(
                instrument = instrument,
                bars = listOf(utcTimestamp),
                volumeAvailable = false,
                adjustmentStatus = "Price Return Index OHLC"
            )
        )
        assertEquals("2025-01-02", mapped.candles.single().time)
    }

    @Test
    fun rejectsNonZeroVolumeWhenSourceMarksVolumeUnavailable() {
        val instrument = InstrumentCatalog.resolve("NIFTY 50")
        assertThrows(IllegalArgumentException::class.java) {
            ChartSeriesMapper.map(
                MarketOhlcvSeries(
                    instrument = instrument,
                    bars = listOf(bar("2025-01-01", volume = 50L)),
                    volumeAvailable = false,
                    adjustmentStatus = "Price Return Index OHLC"
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

    @Test
    fun intradayEquityPreservesMultipleCandlesWithinOneTradingDate() {
        val interval = OhlcvInterval.MINUTE_5
        val instrument = InstrumentCatalog.resolve("RELIANCE", interval = interval)
        val first = OhlcvBar(
            OffsetDateTime.parse("2026-11-24T09:15:00+05:30"),
            100.0, 102.0, 99.0, 101.0, 500L
        )
        val second = OhlcvBar(
            OffsetDateTime.parse("2026-11-24T09:20:00+05:30"),
            101.0, 103.0, 100.0, 102.0, 700L
        )

        val mapped = ChartSeriesMapper.map(MarketOhlcvSeries(
            instrument = instrument,
            bars = listOf(first, second),
            volumeAvailable = true,
            adjustmentStatus = "Raw intraday OHLCV"
        ))

        assertEquals(OhlcvProvider.UPSTOX_V3_INTRADAY, mapped.instrument.provider)
        assertEquals("2026-11-24T09:15+05:30", mapped.candles[0].time)
        assertEquals("2026-11-24T09:20+05:30", mapped.candles[1].time)
        assertEquals(2, mapped.volumeBars?.size)
    }

    @Test
    fun intradayIndexAllowsRepeatedDatesButNeverCreatesVolume() {
        val instrument = InstrumentCatalog.resolve("NIFTY BANK", interval = OhlcvInterval.HOUR_1)
        val first = OhlcvBar(
            OffsetDateTime.parse("2026-11-24T09:15:00+05:30"),
            50_000.0, 50_100.0, 49_900.0, 50_050.0, 0L
        )
        val second = OhlcvBar(
            OffsetDateTime.parse("2026-11-24T10:15:00+05:30"),
            50_050.0, 50_200.0, 50_000.0, 50_150.0, 0L
        )
        val mapped = ChartSeriesMapper.map(MarketOhlcvSeries(
            instrument = instrument,
            bars = listOf(first, second),
            volumeAvailable = false,
            adjustmentStatus = "Index OHLC"
        ))

        assertEquals(2, mapped.candles.size)
        assertNull(mapped.volumeBars)
    }
}
