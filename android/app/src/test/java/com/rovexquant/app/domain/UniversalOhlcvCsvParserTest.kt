package com.rovexquant.app.domain

import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class UniversalOhlcvCsvParserTest {
    @Test
    fun detectsSemicolonDelimiterAndQuotedThousandsSeparators() {
        val csv = "Date;Open;High;Low;Close;Volume\n" +
            "23/11/2026;\"1,234.00\";1250;1200;1240;\"1,234,567\""
        val bar = UniversalOhlcvCsvParser.parse(csv).single()
        assertEquals(1234.0, bar.open, 0.0)
        assertEquals(1_234_567L, bar.volume)
        assertEquals(OffsetDateTime.parse("2026-11-23T00:00:00+05:30"), bar.timestamp)
    }

    @Test
    fun detectsTabDelimitedYahooStyleHeadersAndAdjCloseFallback() {
        val csv = "Date\tOpen\tHigh\tLow\tAdj Close\tVolume\n" +
            "2026-11-24\t100\t102\t99\t101\t1000"
        val bar = UniversalOhlcvCsvParser.parse(csv).single()
        assertEquals(101.0, bar.close, 0.0)
    }

    @Test
    fun sortsUnorderedDailyRowsBeforeReturningBars() {
        val csv = "Date,Open,High,Low,Close,Volume\n" +
            "2026-11-25,101,103,100,102,1100\n" +
            "2026-11-24,100,102,99,101,1000"
        val bars = UniversalOhlcvCsvParser.parse(csv)
        assertEquals(2, bars.size)
        assertEquals("2026-11-24", bars.first().timestamp.toLocalDate().toString())
        assertEquals("2026-11-25", bars.last().timestamp.toLocalDate().toString())
    }

    @Test
    fun supportsQuotedMultilineFieldsWithoutShiftingColumns() {
        val csv = "Date,Open,High,Low,Close,Volume,Company\n" +
            "2026-11-24,100,102,99,101,1000,\"Example\nLimited\""
        val bars = UniversalOhlcvCsvParser.parse(csv)
        assertEquals(1, bars.size)
    }

    @Test
    fun rejectsMultiInstrumentFilesInSingleInstrumentParser() {
        val csv = "TradDt,TckrSymb,SctySrs,OpnPric,HghPric,LwPric,ClsPric,TtlTradgVol\n" +
            "2026-11-24,RELIANCE,EQ,100,102,99,101,1000\n" +
            "2026-11-24,TCS,EQ,200,202,199,201,2000"
        val error = assertThrows(IllegalArgumentException::class.java) {
            UniversalOhlcvCsvParser.parse(csv)
        }
        assertNotNull(error.message)
    }

    @Test
    fun splitsNseUdIffBhavcopyIntoPerSymbolDatasetsAndFiltersUnsupportedSeries() {
        val csv = "TradDt,TckrSymb,SctySrs,OpnPric,HghPric,LwPric,ClsPric,TtlTradgVol\n" +
            "2026-11-24,RELIANCE,EQ,100,102,99,101,1000\n" +
            "2026-11-24,TCS,BE,200,202,199,201,2000\n" +
            "2026-11-24,TEST,SM,50,52,49,51,500"
        val grouped = UniversalOhlcvCsvParser.parseExchangeBhavcopy(csv, "NSE")!!
        assertEquals(setOf("RELIANCE", "TCS"), grouped.keys)
        assertEquals(101.0, grouped.getValue("RELIANCE").single().close, 0.0)
        assertEquals(2000L, grouped.getValue("TCS").single().volume)
    }


    @Test
    fun importsNseFullBhavcopyLegacyColumnNamesAsBulkEquityRows() {
        val csv = "SYMBOL,SERIES,DATE1,PREV_CLOSE,OPEN_PRICE,HIGH_PRICE,LOW_PRICE,LAST_PRICE,CLOSE_PRICE,AVG_PRICE,TTL_TRD_QNTY,TURNOVER_LACS,NO_OF_TRADES\n" +
            "RELIANCE,EQ,26-Nov-2026,100,101,105,99,104,103,102,123456,12.5,5000\n" +
            "TCS,BE,26-Nov-2026,200,201,205,199,204,203,202,654321,25.0,6000"
        val grouped = UniversalOhlcvCsvParser.parseExchangeBhavcopy(csv, "NSE")!!
        assertEquals(setOf("RELIANCE", "TCS"), grouped.keys)
        assertEquals(103.0, grouped.getValue("RELIANCE").single().close, 0.0)
        assertEquals(123456L, grouped.getValue("RELIANCE").single().volume)
    }

    @Test
    fun splitsBseUdIffSeriesUsingBseSeriesAllowlist() {
        val csv = "TradDt,TckrSymb,SctySrs,OpnPric,HghPric,LwPric,ClsPric,TtlTradgVol\n" +
            "2026-11-24,RELIANCE,A,100,102,99,101,1000\n" +
            "2026-11-24,TCS,Z,200,202,199,201,2000"
        val grouped = UniversalOhlcvCsvParser.parseExchangeBhavcopy(csv, "BSE")!!
        assertEquals(setOf("RELIANCE"), grouped.keys)
    }


    @Test
    fun prefersNseEqSeriesWhenSameSymbolHasMultipleExchangeSeriesOnOneDate() {
        val csv = "TradDt,TckrSymb,SctySrs,OpnPric,HghPric,LwPric,ClsPric,TtlTradgVol\n" +
            "2026-11-24,RELIANCE,BE,100,102,99,101,1000\n" +
            "2026-11-24,RELIANCE,EQ,101,103,100,102,1100"
        val grouped = UniversalOhlcvCsvParser.parseExchangeBhavcopy(csv, "NSE")!!
        assertEquals(1, grouped.getValue("RELIANCE").size)
        assertEquals(102.0, grouped.getValue("RELIANCE").single().close, 0.0)
        assertEquals(1_100L, grouped.getValue("RELIANCE").single().volume)
    }

    @Test
    fun rejectsDuplicateSymbolAndSessionRowsInExchangeBhavcopy() {
        val csv = "TradDt,TckrSymb,SctySrs,OpnPric,HghPric,LwPric,ClsPric,TtlTradgVol\n" +
            "2026-11-24,RELIANCE,EQ,100,102,99,101,1000\n" +
            "2026-11-24,RELIANCE,EQ,100,102,99,101,1000"
        assertThrows(IllegalArgumentException::class.java) {
            UniversalOhlcvCsvParser.parseExchangeBhavcopy(csv, "NSE")
        }
    }
}
