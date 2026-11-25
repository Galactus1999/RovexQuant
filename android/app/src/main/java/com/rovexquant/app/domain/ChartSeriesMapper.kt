package com.rovexquant.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Renderer-neutral daily candle; time is an exchange trading date (ISO-8601). */
data class ChartCandle(
    val time: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
)

/** Volume is a separate optional series and is never synthesized from price data. */
data class ChartVolumeBar(
    val time: String,
    val value: Long
)

/**
 * Validated chart payload. A null volumeBars means volume is unavailable, not zero.
 * The renderer must not create a volume pane when this value is null.
 */
data class ChartSeriesData(
    val instrument: InstrumentIdentity,
    val candles: List<ChartCandle>,
    val volumeBars: List<ChartVolumeBar>?,
    val sourceLabel: String,
    val retrievedAt: Instant?,
    val adjustmentStatus: String,
    val providerNote: String?
) {
    val volumeAvailable: Boolean get() = volumeBars != null
}

/**
 * Converts the P1 provider-neutral OHLCV contract to a renderer-neutral chart model.
 * This is intentionally limited to the currently supported DAILY interval.
 */
object ChartSeriesMapper {
    const val MAX_CHART_BARS = 100_000
    private val exchangeZone: ZoneId = ZoneId.of("Asia/Kolkata")

    fun map(series: MarketOhlcvSeries): ChartSeriesData {
        val instrument = series.instrument
        require(instrument.interval == OhlcvInterval.DAILY) {
            "Chart mapping currently supports daily candles only."
        }
        require(InstrumentCatalog.resolve(instrument.canonicalSymbol, instrument.exchange) == instrument) {
            "Instrument identity does not match the canonical P1 provider route."
        }
        if (instrument.instrumentType != InstrumentType.EQUITY) {
            require(!series.volumeAvailable) {
                "Volume is unavailable for this index provider; an index volume series cannot be displayed."
            }
        } else {
            require(instrument.provider == OhlcvProvider.TEJHQ_EOD && series.volumeAvailable) {
                "Equity chart volume requires the validated TejHQ OHLCV source."
            }
        }
        require(series.bars.isNotEmpty()) { "No historical bars are available for this chart." }
        if (!series.volumeAvailable) {
            require(series.bars.all { it.volume == 0L }) {
                "A source marked as volume-unavailable must not carry non-zero volume values."
            }
        }
        require(series.bars.size <= MAX_CHART_BARS) {
            "Chart history exceeds the ${MAX_CHART_BARS} bar safety limit."
        }

        val candles = ArrayList<ChartCandle>(series.bars.size)
        val volumes = if (series.volumeAvailable) ArrayList<ChartVolumeBar>(series.bars.size) else null
        var previousInstant: Instant? = null
        var previousTradingDate: LocalDate? = null

        series.bars.forEachIndexed { index, bar ->
            val instant = bar.timestamp.toInstant()
            require(previousInstant?.let { instant.isAfter(it) } ?: true) {
                "Chart bars must be strictly ordered by timestamp without duplicates (bar ${index + 1})."
            }
            val tradingDate = bar.timestamp.atZoneSameInstant(exchangeZone).toLocalDate()
            require(previousTradingDate?.let { tradingDate.isAfter(it) } ?: true) {
                "Daily chart bars must have unique, increasing exchange trading dates (bar ${index + 1})."
            }
            require(bar.open.isFinite() && bar.open > 0.0 &&
                bar.high.isFinite() && bar.high > 0.0 &&
                bar.low.isFinite() && bar.low > 0.0 &&
                bar.close.isFinite() && bar.close > 0.0
            ) { "Chart bar ${index + 1} contains a non-finite or non-positive OHLC value." }
            require(bar.high >= maxOf(bar.open, bar.close) &&
                bar.low <= minOf(bar.open, bar.close) &&
                bar.high >= bar.low
            ) { "Chart bar ${index + 1} contains an invalid OHLC range." }
            require(bar.volume >= 0L) { "Chart bar ${index + 1} contains negative volume." }

            val time = tradingDate.toString()
            candles += ChartCandle(time, bar.open, bar.high, bar.low, bar.close)
            volumes?.add(ChartVolumeBar(time, bar.volume))
            previousInstant = instant
            previousTradingDate = tradingDate
        }

        return ChartSeriesData(
            instrument = instrument,
            candles = candles,
            volumeBars = volumes,
            sourceLabel = series.sourceLabel,
            retrievedAt = series.retrievedAt,
            adjustmentStatus = series.adjustmentStatus,
            providerNote = series.providerNote
        )
    }
}
