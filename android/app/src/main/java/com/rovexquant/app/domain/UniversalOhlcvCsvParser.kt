package com.rovexquant.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

/**
 * Format-tolerant OHLCV CSV reader. It normalizes common broker/exchange headers,
 * detects the delimiter, honours quoted fields and interprets timezone-naive
 * Indian exchange dates in Asia/Kolkata. It never guesses instrument identity.
 */
object UniversalOhlcvCsvParser {
    private val marketZone = ZoneId.of("Asia/Kolkata")
    private val dateFormats = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH)
    )
    private val equitySeries = mapOf(
        "NSE" to setOf("EQ", "BE", "BZ"),
        "BSE" to setOf("A", "B", "T")
    )

    fun parse(content: String, maxRows: Int = 100_000, volumeRequired: Boolean = true): List<OhlcvBar> {
        require(maxRows > 0) { "maxRows must be positive" }
        val table = readTable(content)
        val columns = columns(table.headers)
        val symbolIndex = columns.first("symbol", "tickersymbol", "tradingsymbol", "scname")
        val dateIndex = columns.required("timestamp", "datetime", "date", "date1", "time", "traddt", "tradedate")
        val openIndex = columns.required("open", "opnpric", "openingprice", "openprice")
        val highIndex = columns.required("high", "hghpric", "highprice")
        val lowIndex = columns.required("low", "lwpric", "lowprice")
        val closeIndex = columns.required("close", "clspric", "closingprice", "closeprice", "lastprice", "ltp", "adjclose")
        val volumeIndex = columns.first("volume", "vol", "ttltradgvol", "ttltrdqnty", "tottrdqty", "totaltradedqty", "tradedqty", "qty")
        require(!volumeRequired || volumeIndex >= 0) { "CSV is missing a Volume column" }
        if (symbolIndex >= 0) {
            val symbols = table.rows.mapNotNull { it.getOrNull(symbolIndex)?.trim()?.takeIf(String::isNotEmpty) }.distinct()
            require(symbols.size <= 1) {
                "This CSV contains multiple instruments. Import an exchange Bhavcopy through the bulk-import path."
            }
        }
        require(table.rows.size <= maxRows) { "CSV exceeds the " + maxRows + " row limit" }
        val bars = table.rows.mapIndexedNotNull { index, row ->
            if (row.all(String::isBlank)) return@mapIndexedNotNull null
            val rowNumber = index + 2
            require(row.size == table.headers.size) { "CSV row " + rowNumber + " has the wrong number of columns" }
            val timestamp = parseTimestamp(row[dateIndex], rowNumber)
            val open = number(row[openIndex], "open", rowNumber)
            val high = number(row[highIndex], "high", rowNumber)
            val low = number(row[lowIndex], "low", rowNumber)
            val close = number(row[closeIndex], "close", rowNumber)
            val volume = if (volumeIndex < 0 || row[volumeIndex].isBlank()) 0L
                else integer(row[volumeIndex], "volume", rowNumber)
            require(volume >= 0L) { "CSV row " + rowNumber + " has negative volume" }
            require(high >= max(open, close) && low <= minOf(open, close) && high >= low) {
                "CSV row " + rowNumber + " has invalid OHLC range"
            }
            OhlcvBar(timestamp, open, high, low, close, volume)
        }
        require(bars.isNotEmpty()) { "CSV contains no data rows" }
        return sortAndValidate(bars)
    }

    /**
     * Parse a multi-security exchange Bhavcopy when it has an explicit symbol and
     * exchange-series column. Returns null when the file is not that dataset type.
     */
    fun parseExchangeBhavcopy(content: String, exchange: String): Map<String, List<OhlcvBar>>? {
        val market = exchange.trim().uppercase(Locale.ROOT)
        val allowedSeries = equitySeries[market] ?: throw IllegalArgumentException("Exchange must be NSE or BSE")
        val table = readTable(content)
        val columns = columns(table.headers)
        val symbolIndex = columns.first("tckrsymb", "tickersymbol", "symbol", "tradingsymbol")
        val seriesIndex = columns.first("sctysrs", "series", "securityseries")
        if (symbolIndex < 0 || seriesIndex < 0) return null

        val dateIndex = columns.required("traddt", "tradedate", "timestamp", "date", "date1")
        val openIndex = columns.required("opnpric", "open", "openingprice", "openprice")
        val highIndex = columns.required("hghpric", "high", "highprice")
        val lowIndex = columns.required("lwpric", "low", "lowprice")
        val closeIndex = columns.required("clspric", "close", "closingprice", "closeprice", "lastprice")
        val volumeIndex = columns.required("ttltradgvol", "ttltrdqnty", "tottrdqty", "totaltradedqty", "volume", "vol")
        require(table.rows.size <= 1_000_000) { "Bhavcopy exceeds the 1,000,000 row safety limit" }

        val grouped = linkedMapOf<String, MutableList<OhlcvBar>>()
        table.rows.forEachIndexed { index, row ->
            if (row.all(String::isBlank)) return@forEachIndexed
            val rowNumber = index + 2
            require(row.size == table.headers.size) { "Bhavcopy row " + rowNumber + " has the wrong number of columns" }
            val series = row[seriesIndex].trim().uppercase(Locale.ROOT)
            if (series !in allowedSeries) return@forEachIndexed
            val symbol = row[symbolIndex].trim().uppercase(Locale.ROOT)
            require(symbol.matches(Regex("^[A-Z0-9][A-Z0-9&._-]{0,39}$"))) {
                "Bhavcopy row " + rowNumber + " has an invalid trading symbol"
            }
            val timestamp = parseTimestamp(row[dateIndex], rowNumber)
            val open = number(row[openIndex], "open", rowNumber)
            val high = number(row[highIndex], "high", rowNumber)
            val low = number(row[lowIndex], "low", rowNumber)
            val close = number(row[closeIndex], "close", rowNumber)
            val volume = integer(row[volumeIndex], "volume", rowNumber)
            require(volume >= 0L) { "Bhavcopy row " + rowNumber + " has negative volume" }
            require(high >= max(open, close) && low <= minOf(open, close) && high >= low) {
                "Bhavcopy row " + rowNumber + " has invalid OHLC range"
            }
            grouped.getOrPut(symbol) { mutableListOf() }.add(OhlcvBar(timestamp, open, high, low, close, volume))
        }
        require(grouped.isNotEmpty()) { "Bhavcopy contains no supported " + market + " equity rows" }
        return grouped.mapValues { (_, bars) -> sortAndValidate(bars) }
    }

    private fun sortAndValidate(bars: List<OhlcvBar>): List<OhlcvBar> {
        val sorted = bars.sortedBy { it.timestamp.toInstant() }
        require(sorted.zipWithNext().none { (a, b) -> a.timestamp.toInstant() == b.timestamp.toInstant() }) {
            "CSV contains duplicate timestamps"
        }
        return sorted
    }

    private fun parseTimestamp(raw: String, row: Int): OffsetDateTime {
        val value = raw.trim()
        runCatching { OffsetDateTime.parse(value) }.getOrNull()?.let { return it }
        runCatching { Instant.parse(value) }.getOrNull()?.let {
            return it.atZone(marketZone).toOffsetDateTime()
        }
        runCatching { LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME) }
            .getOrNull()?.let { return it.atZone(marketZone).toOffsetDateTime() }
        dateFormats.forEach { format ->
            val date = runCatching { LocalDate.parse(value, format) }.getOrNull()
            if (date != null) return date.atStartOfDay(marketZone).toOffsetDateTime()
        }
        throw IllegalArgumentException("CSV row " + row + " has an unsupported date/time: " + value)
    }

    private fun number(raw: String, field: String, row: Int): Double {
        val normalized = raw.trim().replace(",", "").replace("\u00a0", "").replace(" ", "")
        val value = normalized.toDoubleOrNull()
        require(value != null && value.isFinite() && value > 0.0) {
            "CSV row " + row + " has invalid " + field + " price"
        }
        return value
    }

    private fun integer(raw: String, field: String, row: Int): Long {
        val normalized = raw.trim().replace(",", "").replace("\u00a0", "").replace(" ", "")
        val value = normalized.toLongOrNull()
        require(value != null) { "CSV row " + row + " has invalid " + field }
        return value
    }

    private data class CsvTable(val headers: List<String>, val rows: List<List<String>>)
    private data class Columns(val values: Map<String, Int>) {
        fun first(vararg aliases: String): Int = aliases.firstNotNullOfOrNull { values[canonical(it)] } ?: -1
        fun required(vararg aliases: String): Int = first(*aliases).also {
            require(it >= 0) { "CSV is missing required column: " + aliases.first() }
        }
    }

    private fun columns(headers: List<String>) = Columns(
        headers.mapIndexed { index, header -> canonical(header) to index }.toMap()
    )

    private fun canonical(value: String): String =
        value.removePrefix("\uFEFF").lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private fun readTable(content: String): CsvTable {
        val text = content.removePrefix("\uFEFF")
        require(text.isNotBlank()) { "CSV is empty" }
        val delimiter = detectDelimiter(text)
        val records = parseRecords(text, delimiter).filter { row -> row.any { it.isNotBlank() } }
        require(records.size >= 2) { "CSV must contain a header and at least one data row" }
        val headers = records.first().map(::canonical)
        require(headers.isNotEmpty() && headers.none(String::isBlank)) { "CSV contains an empty column name" }
        return CsvTable(headers, records.drop(1))
    }

    private fun detectDelimiter(text: String): Char {
        val candidates = listOf(',', ';', '\t', '|')
        val counts = candidates.associateWith { candidate ->
            var quoted = false
            var count = 0
            var index = 0
            while (index < text.length && text[index] != '\n' && text[index] != '\r') {
                val char = text[index]
                if (char == '"' && quoted && index + 1 < text.length && text[index + 1] == '"') {
                    index++
                } else if (char == '"') {
                    quoted = !quoted
                } else if (char == candidate && !quoted) {
                    count++
                }
                index++
            }
            count
        }
        return counts.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key
            ?: throw IllegalArgumentException("Could not detect CSV delimiter")
    }

    private fun parseRecords(text: String, delimiter: Char): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' && quoted && index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == delimiter && !quoted -> {
                    row.add(field.toString())
                    field.setLength(0)
                }
                (char == '\n' || char == '\r') && !quoted -> {
                    row.add(field.toString())
                    field.setLength(0)
                    if (row.any { it.isNotBlank() }) records.add(row)
                    row = mutableListOf()
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                }
                else -> field.append(char)
            }
            index++
        }
        require(!quoted) { "CSV contains an unterminated quoted field" }
        row.add(field.toString())
        if (row.any { it.isNotBlank() }) records.add(row)
        return records
    }
}
