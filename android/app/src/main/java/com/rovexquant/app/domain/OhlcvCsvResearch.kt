package com.rovexquant.app.domain

import java.time.OffsetDateTime
import kotlin.math.max

data class OhlcvBar(val timestamp: OffsetDateTime, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Long)
data class OhlcvResearchSummary(
    val rows: Int, val firstTimestamp: OffsetDateTime, val lastTimestamp: OffsetDateTime,
    val latestClose: Double, val latestVolume: Long, val sma20: Double?, val sma50: Double?,
    val rsi14: Double?, val trend: String, val donchian20: String, val relativeVolume20: Double?
)

object OhlcvCsvResearch {
    const val STORAGE_FILE_NAME = "quant_local_research.csv"
    private val required = listOf("timestamp", "open", "high", "low", "close", "volume")

    /** Strict offline CSV import. Timestamps must be ISO-8601 with an explicit UTC offset. */
    fun parse(content: String, maxRows: Int = 100_000): List<OhlcvBar> =
        UniversalOhlcvCsvParser.parse(content, maxRows = maxRows, volumeRequired = true)

    /** Merge bars by exact instant; incoming rows replace overlaps without collapsing intraday candles. */
    fun merge(existing: List<OhlcvBar>, incoming: List<OhlcvBar>): List<OhlcvBar> {
        require(existing.size + incoming.size <= 100_000) { "Merged dataset exceeds the 100,000-row safety limit" }
        val byInstant = java.util.TreeMap<java.time.Instant, OhlcvBar>()
        (existing + incoming).forEach { bar -> byInstant[bar.timestamp.toInstant()] = bar }
        return byInstant.values.toList()
    }

    /** Serialize validated bars into the same strict local CSV format used by the scanner. */
    fun toCsv(bars: List<OhlcvBar>): String {
        require(bars.isNotEmpty()) { "No OHLCV bars to save" }
        val ordered = bars.sortedBy { it.timestamp }
        require(ordered.size <= 100_000) { "Dataset exceeds the 100,000-row safety limit" }
        return buildString {
            append("timestamp,open,high,low,close,volume\n")
            ordered.forEach { bar ->
                append(bar.timestamp).append(',')
                    .append(bar.open).append(',')
                    .append(bar.high).append(',')
                    .append(bar.low).append(',')
                    .append(bar.close).append(',')
                    .append(bar.volume).append('\n')
            }
        }
    }

    fun summarize(bars: List<OhlcvBar>): OhlcvResearchSummary {
        require(bars.isNotEmpty()) { "No OHLCV bars to analyse" }
        val closes = bars.map { it.close }
        val latest = closes.last()
        val sma20 = simpleMovingAverage(closes, 20)
        val sma50 = simpleMovingAverage(closes, 50)
        val rsi = relativeStrengthIndex(closes, 14)
        val trend = when {
            sma20 != null && sma50 != null && latest > sma20 && sma20 > sma50 -> "Bullish trend alignment"
            sma20 != null && sma50 != null && latest < sma20 && sma20 < sma50 -> "Bearish trend alignment"
            else -> "Mixed / insufficient trend alignment"
        }
        val prior = bars.dropLast(1).takeLast(20)
        val donchian = if (prior.size < 20) "Warming up" else when {
            bars.last().close > prior.maxOf { it.high } -> "Bullish breakout"
            bars.last().close < prior.minOf { it.low } -> "Bearish breakdown"
            else -> "No 20-bar breakout"
        }
        val averageVolume = prior.takeIf { it.size == 20 }?.map { it.volume.toDouble() }?.average()
        val relativeVolume = averageVolume?.takeIf { it > 0.0 }?.let { bars.last().volume / it }
        val last = bars.last()
        return OhlcvResearchSummary(
            rows = bars.size, firstTimestamp = bars.first().timestamp, lastTimestamp = last.timestamp,
            latestClose = latest, latestVolume = last.volume, sma20 = sma20, sma50 = sma50,
            rsi14 = rsi, trend = trend, donchian20 = donchian, relativeVolume20 = relativeVolume
        )
    }

    private fun simpleMovingAverage(values: List<Double>, period: Int): Double? =
        if (values.size < period) null else values.takeLast(period).average()

    /** Wilder-smoothed RSI, seeded with the first full period of gains and losses. */
    private fun relativeStrengthIndex(values: List<Double>, period: Int): Double? {
        if (values.size <= period) return null
        val changes = values.zipWithNext { a, b -> b - a }
        var averageGain = changes.take(period).sumOf { max(it, 0.0) } / period
        var averageLoss = changes.take(period).sumOf { max(-it, 0.0) } / period
        for (index in period until changes.size) {
            averageGain = ((averageGain * (period - 1)) + max(changes[index], 0.0)) / period
            averageLoss = ((averageLoss * (period - 1)) + max(-changes[index], 0.0)) / period
        }
        return when {
            averageGain == 0.0 && averageLoss == 0.0 -> 50.0
            averageLoss == 0.0 -> 100.0
            else -> 100.0 - (100.0 / (1.0 + averageGain / averageLoss))
        }
    }

    private fun parseRecord(line: String): List<String> {
        val fields = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> { field.append('"'); index++ }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> { fields += field.toString(); field.setLength(0) }
                else -> field.append(char)
            }
            index++
        }
        require(!quoted) { "CSV contains an unterminated quoted field" }
        fields += field.toString()
        return fields
    }
}
