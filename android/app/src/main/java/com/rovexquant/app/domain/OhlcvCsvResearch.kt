package com.rovexquant.app.domain

import java.time.OffsetDateTime

data class OhlcvBar(
    val timestamp: OffsetDateTime,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long
)

data class OhlcvResearchSummary(
    val rows: Int,
    val firstTimestamp: OffsetDateTime,
    val lastTimestamp: OffsetDateTime,
    val latestClose: Double,
    val latestVolume: Long,
    val sma20: Double?,
    val sma50: Double?,
    val rsi14: Double?,
    val trend: String
)

object OhlcvCsvResearch {
    private val required = listOf("timestamp", "open", "high", "low", "close", "volume")

    /** Strict offline CSV import. Timestamps must be ISO-8601 with an explicit UTC offset. */
    fun parse(content: String, maxRows: Int = 100_000): List<OhlcvBar> {
        require(maxRows > 0) { "maxRows must be positive" }
        val lines = content.lineSequence().filter { it.isNotBlank() }.iterator()
        require(lines.hasNext()) { "CSV is empty" }
        val headers = parseRecord(lines.next()).map { it.trim().lowercase() }
        val indexes = required.associateWith { name ->
            headers.indexOf(name).also { require(it >= 0) { "CSV is missing required column: $name" } }
        }
        val bars = ArrayList<OhlcvBar>()
        var previous: OffsetDateTime? = null
        var rowNumber = 1
        while (lines.hasNext()) {
            val line = lines.next()
            rowNumber++
            require(bars.size < maxRows) { "CSV exceeds the $maxRows row limit" }
            val cells = parseRecord(line)
            require(cells.size == headers.size) { "CSV row $rowNumber has the wrong number of columns" }
            fun value(name: String) = cells[indexes.getValue(name)].trim()
            val timestamp = try {
                OffsetDateTime.parse(value("timestamp"))
            } catch (error: Exception) {
                throw IllegalArgumentException("CSV row $rowNumber timestamp must be ISO-8601 with a timezone offset", error)
            }
            require(previous == null || timestamp.isAfter(previous)) {
                "CSV timestamps must be strictly increasing without duplicates (row $rowNumber)"
            }
            fun price(name: String): Double {
                val parsed = value(name).toDoubleOrNull()
                require(parsed != null && parsed.isFinite() && parsed > 0.0) {
                    "CSV row $rowNumber has invalid $name price"
                }
                return parsed
            }
            val open = price("open")
            val high = price("high")
            val low = price("low")
            val close = price("close")
            val volume = value("volume").toLongOrNull()
            require(volume != null && volume >= 0) { "CSV row $rowNumber has invalid volume" }
            require(high >= maxOf(open, close) && low <= minOf(open, close) && high >= low) {
                "CSV row $rowNumber has invalid OHLC range"
            }
            bars += OhlcvBar(timestamp, open, high, low, close, volume)
            previous = timestamp
        }
        require(bars.isNotEmpty()) { "CSV contains no data rows" }
        return bars
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
        val last = bars.last()
        return OhlcvResearchSummary(
            rows = bars.size,
            firstTimestamp = bars.first().timestamp,
            lastTimestamp = last.timestamp,
            latestClose = latest,
            latestVolume = last.volume,
            sma20 = sma20,
            sma50 = sma50,
            rsi14 = rsi,
            trend = trend
        )
    }

    private fun simpleMovingAverage(values: List<Double>, period: Int): Double? {
        if (values.size < period) return null
        return values.takeLast(period).average()
    }

    private fun relativeStrengthIndex(values: List<Double>, period: Int): Double? {
        if (values.size <= period) return null
        val changes = values.takeLast(period + 1).zipWithNext { a, b -> b - a }
        val gains = changes.filter { it > 0.0 }.sum() / period
        val losses = -changes.filter { it < 0.0 }.sum() / period
        return when {
            gains == 0.0 && losses == 0.0 -> 50.0
            losses == 0.0 -> 100.0
            else -> 100.0 - (100.0 / (1.0 + gains / losses))
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
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    fields += field.toString()
                    field.setLength(0)
                }
                else -> field.append(char)
            }
            index++
        }
        require(!quoted) { "CSV contains an unterminated quoted field" }
        fields += field.toString()
        return fields
    }
}
