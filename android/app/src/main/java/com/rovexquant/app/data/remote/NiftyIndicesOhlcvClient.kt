package com.rovexquant.app.data.remote

import com.rovexquant.app.domain.InstrumentIdentity
import com.rovexquant.app.domain.InstrumentCatalog
import com.rovexquant.app.domain.OhlcvBar
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Website-backed adapter for the historical OHLC form on niftyindices.com.
 *
 * The portal's underlying POST contract is not published as a supported public
 * API. This adapter is deliberately isolated, allowlisted, rate-limited and
 * fail-closed. It does not bypass bot challenges or invent index volume.
 */
object NiftyIndicesOhlcvClient {
    private const val PORTAL_URL = "https://www.niftyindices.com/reports/historical-data"
    private const val HISTORY_URL = "https://www.niftyindices.com/Backpage.aspx/getHistoricaldatatabletoString"
    private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    private const val MAX_ROWS = 5_000
    private const val MAX_CHUNK_DAYS = 364L
    private val requestMediaType = "application/json; charset=UTF-8".toMediaType()
    private val requestDate = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)
    private val responseDateFormats = listOf(
        DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)
    )
    private val cookies = PortalCookieJar()
    private val http = OkHttpClient.Builder()
        .cookieJar(cookies)
        .connectTimeout(java.time.Duration.ofSeconds(15))
        .readTimeout(java.time.Duration.ofSeconds(45))
        .callTimeout(java.time.Duration.ofSeconds(60))
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    @Synchronized
    fun fetch(instrument: InstrumentIdentity, from: LocalDate, to: LocalDate): List<OhlcvBar> {
        require(InstrumentCatalog.isRegisteredIndex(instrument)) {
            "Index provider mapping is not registered."
        }
        require(instrument.exchange == "NSE") { "NSE Indices history is available only for NSE indices." }
        require(instrument.instrumentType != com.rovexquant.app.domain.InstrumentType.EQUITY) {
            "Equity instruments must use the equity OHLCV provider."
        }
        require(!from.isAfter(to)) { "Start date must be on or before end date." }
        require(java.time.temporal.ChronoUnit.DAYS.between(from, to) <= 6200L) {
            "Choose a date range of 17 years or less."
        }

        warmPortalSession()
        val result = ArrayList<OhlcvBar>()
        var chunkStart = from
        while (!chunkStart.isAfter(to)) {
            val chunkEnd = minOf(chunkStart.plusDays(MAX_CHUNK_DAYS), to)
            result += fetchChunk(instrument.providerSymbol, chunkStart, chunkEnd)
            chunkStart = chunkEnd.plusDays(1)
            if (!chunkStart.isAfter(to)) {
                try {
                    Thread.sleep(1_100L)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Index history request was interrupted.", interrupted)
                }
            }
        }
        val ordered = result.sortedBy { it.timestamp.toInstant() }
        require(ordered.isNotEmpty()) { "NSE Indices returned no history for ${instrument.canonicalSymbol}." }
        require(ordered.size <= MAX_ROWS) { "Index history exceeds the $MAX_ROWS-bar safety limit." }
        require(ordered.zipWithNext().all { (a, b) -> a.timestamp.toInstant().isBefore(b.timestamp.toInstant()) }) {
            "NSE Indices history contains duplicate trading dates."
        }
        return ordered
    }

    private fun warmPortalSession() {
        val request = Request.Builder()
            .url(PORTAL_URL)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("User-Agent", "RovexQuant-PersonalResearch/1.0")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("NSE Indices historical portal is unavailable (HTTP ${response.code}).")
            }
            response.body?.close()
        }
    }

    private fun fetchChunk(indexName: String, from: LocalDate, to: LocalDate): List<OhlcvBar> {
        val dateFrom = requestDate.format(from)
        val dateTo = requestDate.format(to)
        // This is the form payload observed in the official portal's historical-data page.
        // Index names are registry-controlled; no user-provided text is interpolated.
        val cinfo = "{'name':'$indexName','startDate':'$dateFrom','endDate':'$dateTo','indexName':'$indexName'}"
        val payload = JSONObject().put("cinfo", cinfo).toString()
            .toRequestBody(requestMediaType)
        val request = Request.Builder()
            .url(HISTORY_URL)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Content-Type", "application/json; charset=UTF-8")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", PORTAL_URL)
            .header("User-Agent", "RovexQuant-PersonalResearch/1.0")
            .post(payload)
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body ?: throw IOException("NSE Indices returned an empty response.")
            if (!response.isSuccessful) {
                throw IOException(
                    when (response.code) {
                        403 -> "NSE Indices denied the historical request (HTTP 403); no access-control bypass was attempted."
                        429 -> "NSE Indices rate-limited the historical request."
                        in 500..599 -> "NSE Indices historical service is temporarily unavailable (HTTP ${response.code})."
                        else -> "NSE Indices historical request failed (HTTP ${response.code})."
                    }
                )
            }
            if (body.contentLength() > MAX_RESPONSE_BYTES) {
                throw IOException("NSE Indices response exceeded the 4 MB safety limit.")
            }
            val envelope = JSONObject(readBounded(body))
            val encodedRows = envelope.optString("d")
            if (encodedRows.isBlank() || encodedRows == "false" || encodedRows == "null") {
                return emptyList()
            }
            val rows = try {
                JSONArray(encodedRows)
            } catch (failure: Exception) {
                throw IOException("NSE Indices returned an unrecognised historical-data response.", failure)
            }
            if (rows.length() > MAX_ROWS) throw IOException("Index response exceeded the $MAX_ROWS-row limit.")
            val parsed = ArrayList<OhlcvBar>(rows.length())
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index)
                    ?: throw IOException("NSE Indices returned a malformed OHLC row.")
                val dateText = firstValue(row, "HistoricalDate", "DATE", "date")
                val date = parseDate(dateText)
                if (date.isBefore(from) || date.isAfter(to)) {
                    throw IOException("NSE Indices returned a date outside the requested range.")
                }
                val rowName = listOf("indexName", "INDEX_NAME", "Index Name")
                    .map { row.optString(it).trim() }
                    .firstOrNull { it.isNotEmpty() && it != "null" }
                if (rowName != null && !rowName.equals(indexName, ignoreCase = true)) {
                    throw IOException("NSE Indices response contained a different index than requested.")
                }
                fun price(vararg keys: String): Double {
                    val value = firstValue(row, *keys).replace(",", "").toDoubleOrNull()
                    if (value == null || !value.isFinite() || value <= 0.0) {
                        throw IOException("NSE Indices returned an invalid OHLC value for $date.")
                    }
                    return value
                }
                val open = price("OPEN", "Open", "open")
                val high = price("HIGH", "High", "high")
                val low = price("LOW", "Low", "low")
                val close = price("CLOSE", "Close", "close")
                if (high < maxOf(open, close) || low > minOf(open, close) || high < low) {
                    throw IOException("NSE Indices returned an inconsistent OHLC range for $date.")
                }
                parsed += OhlcvBar(
                    timestamp = OffsetDateTime.of(
                        date.year, date.monthValue, date.dayOfMonth, 15, 30, 0, 0,
                        ZoneOffset.ofHoursMinutes(5, 30)
                    ),
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    // Index history supplies OHLC, not traded volume. Zero is an internal
                    // sentinel only; MarketOhlcvSeries.volumeAvailable remains false.
                    volume = 0L
                )
            }
            return parsed
        }
    }

    private fun readBounded(body: ResponseBody): String {
        val output = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_RESPONSE_BYTES) {
                    throw IOException("NSE Indices response exceeded the 4 MB safety limit.")
                }
                output.write(buffer, 0, count)
            }
        }
        return String(output.toByteArray(), Charsets.UTF_8)
    }

    private fun firstValue(row: JSONObject, vararg keys: String): String {
        for (key in keys) {
            if (row.has(key) && !row.isNull(key)) {
                val value = row.optString(key).trim()
                if (value.isNotEmpty() && value != "null") return value
            }
        }
        throw IOException("NSE Indices historical row is missing ${keys.first()} data.")
    }

    private fun parseDate(value: String): LocalDate {
        responseDateFormats.forEach { formatter ->
            try {
                return LocalDate.parse(value.trim(), formatter)
            } catch (_: Exception) {
                // Try the next portal date representation.
            }
        }
        throw IOException("NSE Indices returned an invalid trading date.")
    }

    private class PortalCookieJar : CookieJar {
        private val stored = mutableListOf<Cookie>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { incoming ->
                stored.removeAll { it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path }
                if (incoming.expiresAt > System.currentTimeMillis()) stored += incoming
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            stored.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }
    }
}
