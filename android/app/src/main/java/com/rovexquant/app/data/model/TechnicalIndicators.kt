package com.rovexquant.app.data.model

/**
 * Backend-calculated indicator arrays aligned to a bounded contiguous OHLC window.
 * startIndex is the first source-bar index represented by these arrays; warm-up
 * observations remain null and are never forward-filled.
 */
data class TechnicalIndicators(
    val startIndex: Int,
    val barCount: Int,
    val sma: List<Double?>,
    val ema: List<Double?>,
    val rsi: List<Double?>,
    val macdLine: List<Double?>,
    val macdSignal: List<Double?>,
    val macdHistogram: List<Double?>,
    val bollingerUpper: List<Double?>,
    val bollingerMiddle: List<Double?>,
    val bollingerLower: List<Double?>,
    val atr: List<Double?>,
    val adx: List<Double?>
) {
    init {
        require(startIndex >= 0) { "Indicator start index cannot be negative." }
        require(barCount > 0) { "Indicator window must contain at least one bar." }
        require(startIndex <= Int.MAX_VALUE - barCount) { "Indicator source window exceeds the supported index range." }
        val arrays = listOf(
            sma, ema, rsi, macdLine, macdSignal, macdHistogram,
            bollingerUpper, bollingerMiddle, bollingerLower, atr, adx
        )
        require(arrays.all { it.size == barCount }) {
            "Every indicator array must align exactly with the requested OHLC window."
        }
        require(arrays.flatten().filterNotNull().all { it.isFinite() }) {
            "Indicator arrays cannot contain non-finite values."
        }
        require(rsi.filterNotNull().all { it in 0.0..100.0 }) {
            "RSI values must remain within the inclusive 0–100 range."
        }
        require(adx.filterNotNull().all { it in 0.0..100.0 }) {
            "ADX values must remain within the inclusive 0–100 range."
        }
        require(atr.filterNotNull().all { it >= 0.0 }) {
            "ATR values cannot be negative."
        }
        for (index in 0 until barCount) {
            val upper = bollingerUpper[index]
            val middle = bollingerMiddle[index]
            val lower = bollingerLower[index]
            if (upper != null && middle != null && lower != null) {
                require(lower <= middle && middle <= upper) {
                    "Bollinger bands must satisfy lower ≤ middle ≤ upper at bar ${index + 1}."
                }
            }
        }
    }

    val endExclusive: Int get() = startIndex + barCount

    /** Returns the response-array index for a source-bar index, or null outside coverage. */
    fun localIndexForSourceBar(sourceBarIndex: Int): Int? {
        if (sourceBarIndex < startIndex || sourceBarIndex >= endExclusive) return null
        return sourceBarIndex - startIndex
    }
}

data class TechnicalIndicatorPeriods(
    val sma: Int = 20,
    val ema: Int = 20,
    val rsi: Int = 14,
    val bollinger: Int = 20,
    val bollingerDeviations: Double = 2.0,
    val atr: Int = 14,
    val adx: Int = 14
) {
    init {
        require(sma in 1..500 && ema in 1..500) { "Moving-average periods must be between 1 and 500." }
        require(rsi in 2..500 && bollinger in 2..500) { "RSI and Bollinger periods must be between 2 and 500." }
        require(bollingerDeviations.isFinite() && bollingerDeviations > 0.0 && bollingerDeviations <= 10.0) {
            "Bollinger deviation must be finite and between 0 and 10."
        }
        require(atr in 2..500 && adx in 2..500) { "ATR and ADX periods must be between 2 and 500." }
    }
}
