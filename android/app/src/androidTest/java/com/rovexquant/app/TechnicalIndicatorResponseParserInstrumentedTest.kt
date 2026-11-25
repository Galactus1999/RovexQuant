package com.rovexquant.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rovexquant.app.data.remote.parseTechnicalIndicatorsResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TechnicalIndicatorResponseParserInstrumentedTest {
    private fun payload(
        sma: String = "[null,2.0]",
        rsi: String = "[null,50.0]",
        macd: String = "[[null,1.0],[null,0.5],[null,0.5]]",
        upper: String = "[null,4.0]",
        middle: String = "[null,3.0]",
        lower: String = "[null,2.0]",
        atr: String = "[null,1.0]",
        adx: String = "[null,25.0]"
    ) = """{
        "sma":$sma,"ema":[null,2.0],"rsi":$rsi,
        "macd":$macd,
        "bollinger_bands":{"upper":$upper,"middle":$middle,"lower":$lower},
        "atr":$atr,"adx":$adx
    }"""

    @Test
    fun preservesNullWarmupsAndAbsoluteSourceOffset() {
        val result = parseTechnicalIndicatorsResponse(payload(), startIndex = 250, barCount = 2)
        assertEquals(250, result.startIndex)
        assertEquals(252, result.endExclusive)
        assertEquals(null, result.rsi[0])
        assertEquals(50.0, result.rsi[1]!!, 0.0)
        assertEquals(0, result.localIndexForSourceBar(250))
        assertEquals(1, result.localIndexForSourceBar(251))
        assertEquals(null, result.localIndexForSourceBar(249))
    }

    @Test
    fun rejectsMissingOrMisalignedSeries() {
        assertThrows(IllegalArgumentException::class.java) {
            parseTechnicalIndicatorsResponse(payload(sma = "[null]"), 0, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseTechnicalIndicatorsResponse(payload().replace("\"ema\":[null,2.0],", ""), 0, 2)
        }
    }

    @Test
    fun rejectsMalformedMacdAndMissingBands() {
        assertThrows(IllegalArgumentException::class.java) {
            parseTechnicalIndicatorsResponse(payload(macd = "[[null,1.0],[null,0.5]]"), 0, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseTechnicalIndicatorsResponse(payload().replace("\"bollinger_bands\":{\"upper\":[null,4.0],\"middle\":[null,3.0],\"lower\":[null,2.0]},", ""), 0, 2)
        }
    }

    @Test
    fun rejectsNonNumericAndNonFiniteValues() {
        assertThrows(IllegalArgumentException::class.java) {
            parseTechnicalIndicatorsResponse(payload(sma = "[null,\"2.0\"]"), 0, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseTechnicalIndicatorsResponse(payload(sma = "[null,1e999]"), 0, 2)
        }
    }

    @Test
    fun rejectsSemanticallyInvalidIndicatorRanges() {
        assertThrows(IllegalArgumentException::class.java) {
            parseTechnicalIndicatorsResponse(payload(rsi = "[null,101.0]"), 0, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseTechnicalIndicatorsResponse(payload(upper = "[null,2.0]", middle = "[null,3.0]", lower = "[null,1.0]"), 0, 2)
        }
    }
}
