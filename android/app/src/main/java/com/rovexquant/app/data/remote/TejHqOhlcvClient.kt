package com.rovexquant.app.data.remote

import com.rovexquant.app.domain.OhlcvBar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.io.ByteArrayOutputStream
import okhttp3.ResponseBody
import java.util.concurrent.TimeUnit

/**
 * Direct, keyless TejHQ daily OHLCV client.
 *
 * This deliberately does not route historical bars through the Quant backend:
 * personal research data is downloaded to and retained on this device only.
 */
object TejHqOhlcvClient {
    private const val BASE_URL = "https://api.tejhq.dev/v1/ohlcv"
    private const val MAX_RESPONSE_BYTES = 12 * 1024 * 1024
    private const val MAX_ROWS = 5_000
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun fetch(
        exchange: String,
        symbol: String,
        from: LocalDate,
        to: LocalDate
    ): List<OhlcvBar> {
        val market = exchange.trim().lowercase(Locale.ROOT)
        require(market == "nse" || market == "bse") { "Choose NSE or BSE." }
        val ticker = symbol.trim().uppercase(Locale.ROOT)
        require(ticker.matches(Regex("[A-Z0-9][A-Z0-9&-]{0,29}"))) {
            "Enter a valid exchange trading symbol, without .NS or .BO."
        }
        require(!from.isAfter(to)) { "Start date must be on or before end date." }
        require(java.time.temporal.ChronoUnit.DAYS.between(from, to) <= 6200L) {
            "Choose a date range of 17 years or less."
        }

        val url = "$BASE_URL/$market/$ticker?from=$from&to=$to"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "RovexQuant-PersonalResearch/1.0")
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body ?: throw IllegalStateException("TejHQ returned an empty response.")
            if (!response.isSuccessful) {
                val message = when (response.code) {
                    400, 404 -> "No matching $market history was found for $ticker."
                    429 -> "TejHQ rate limit reached. Wait before retrying."
                    in 500..599 -> "TejHQ is temporarily unavailable (HTTP ${response.code})."
                    else -> "TejHQ request failed (HTTP ${response.code})."
                }
                throw IllegalStateException(message)
            }
            if (body.contentLength() > MAX_RESPONSE_BYTES) {
                throw IllegalStateException("Historical response exceeds the 12 MB safety limit.")
            }
            val json = JSONObject(readBounded(body))
            val meta = json.optJSONObject("meta")
            meta?.optString("symbol")?.takeIf { it.isNotBlank() && it != "null" }?.let {
                require(it.trim().uppercase(Locale.ROOT) == ticker) {
                    "TejHQ response symbol does not match the requested instrument."
                }
            }
            meta?.optString("exchange")?.takeIf { it.isNotBlank() && it != "null" }?.let {
                require(it.trim().lowercase(Locale.ROOT) == market) {
                    "TejHQ response exchange does not match the requested exchange."
                }
            }
            val rows = json.optJSONArray("data")
                ?: throw IllegalStateException("TejHQ response has no OHLCV data array.")
            require(rows.length() <= MAX_ROWS) { "History exceeds the $MAX_ROWS-bar mobile limit." }
            val output = ArrayList<OhlcvBar>(rows.length())
            var previousDate: LocalDate? = null
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index)
                    ?: throw IllegalStateException("TejHQ returned a malformed OHLCV row.")
                val date = runCatching { LocalDate.parse(row.optString("date")) }
                    .getOrElse { throw IllegalStateException("TejHQ returned an invalid trading date.") }
                require(!date.isBefore(from) && !date.isAfter(to)) {
                    "TejHQ returned a trading date outside the requested range."
                }
                require(previousDate == null || date.isAfter(previousDate)) {
                    "TejHQ history contains duplicate or out-of-order trading dates."
                }
                fun price(name: String): Double {
                    val value = row.optDouble(name, Double.NaN)
                    require(value.isFinite() && value > 0.0) { "Invalid $name price for $date." }
                    return value
                }
                val open = price("open")
                val high = price("high")
                val low = price("low")
                val close = price("close")
                val volume = row.optLong("volume", -1L)
                require(volume >= 0L) { "Invalid volume for $date." }
                require(high >= maxOf(open, close) && low <= minOf(open, close) && high >= low) {
                    "Inconsistent OHLC values for $date."
                }
                output += OhlcvBar(
                    timestamp = OffsetDateTime.of(date.year, date.monthValue, date.dayOfMonth, 15, 30, 0, 0, ZoneOffset.ofHoursMinutes(5, 30)),
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = volume
                )
                previousDate = date
            }
            if (output.isEmpty()) throw IllegalStateException("No trading bars were available for this date range.")
            return output
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
                    throw IllegalStateException("Historical response exceeds the 12 MB safety limit.")
                }
                output.write(buffer, 0, count)
            }
        }
        return String(output.toByteArray(), Charsets.UTF_8)
    }
}
