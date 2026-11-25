package com.rovexquant.app.data.remote

import com.rovexquant.app.data.model.TechnicalIndicators
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Parses the deterministic backend indicator contract without network or Android UI state.
 * JSON nulls are retained as warm-up gaps; numeric strings and non-finite values are rejected.
 */
internal fun parseTechnicalIndicatorsResponse(
    body: String,
    startIndex: Int,
    barCount: Int
): TechnicalIndicators {
    require(startIndex >= 0) { "Indicator source offset cannot be negative." }
    require(barCount in 1..10_000) { "Indicator response bar count must be between 1 and 10,000." }
    require(body.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) {
        "Indicator response exceeds the 2 MB mobile safety limit."
    }
    val json = try {
        JSONObject(body)
    } catch (failure: JSONException) {
        throw IllegalArgumentException("Backend indicator response is not valid JSON.", failure)
    }

    fun parseSeries(array: JSONArray?, label: String): List<Double?> {
        require(array != null && array.length() == barCount) {
            "Backend indicator '$label' is missing or misaligned with the requested OHLC bars."
        }
        return List(barCount) { index ->
            if (array.isNull(index)) {
                null
            } else {
                val raw = array.get(index)
                val number = raw as? Number
                    ?: throw IllegalArgumentException("Backend indicator '$label' must contain JSON numbers or null.")
                val value = number.toDouble()
                require(value.isFinite()) {
                    "Backend indicator '$label' contains a non-finite value at bar ${index + 1}."
                }
                value
            }
        }
    }

    val macd = json.optJSONArray("macd")
        ?: throw IllegalArgumentException("Backend indicator response is missing MACD arrays.")
    require(macd.length() == 3) {
        "Backend MACD response must contain line, signal and histogram arrays."
    }
    val bands = json.optJSONObject("bollinger_bands")
        ?: throw IllegalArgumentException("Backend indicator response is missing Bollinger bands.")

    return TechnicalIndicators(
        startIndex = startIndex,
        barCount = barCount,
        sma = parseSeries(json.optJSONArray("sma"), "SMA"),
        ema = parseSeries(json.optJSONArray("ema"), "EMA"),
        rsi = parseSeries(json.optJSONArray("rsi"), "RSI"),
        macdLine = parseSeries(macd.optJSONArray(0), "MACD line"),
        macdSignal = parseSeries(macd.optJSONArray(1), "MACD signal"),
        macdHistogram = parseSeries(macd.optJSONArray(2), "MACD histogram"),
        bollingerUpper = parseSeries(bands.optJSONArray("upper"), "Bollinger upper"),
        bollingerMiddle = parseSeries(bands.optJSONArray("middle"), "Bollinger middle"),
        bollingerLower = parseSeries(bands.optJSONArray("lower"), "Bollinger lower"),
        atr = parseSeries(json.optJSONArray("atr"), "ATR"),
        adx = parseSeries(json.optJSONArray("adx"), "ADX")
    )
}
