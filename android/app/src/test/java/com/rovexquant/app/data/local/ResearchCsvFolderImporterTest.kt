package com.rovexquant.app.data.local

import com.rovexquant.app.domain.InstrumentType
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.rovexquant.app.domain.OhlcvBar
import com.rovexquant.app.domain.OhlcvProvider
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ResearchCsvFolderImporterTest {
    @Test
    fun resolvesNseEquityFilenameWithoutTouchingProviderCache() {
        val identity = ResearchCsvFolderImporter.resolveInstrumentFromFileName("NSE_RELIANCE.csv")
        assertEquals("NSE", identity.exchange)
        assertEquals("RELIANCE", identity.canonicalSymbol)
        assertEquals(InstrumentType.EQUITY, identity.instrumentType)
        assertEquals(OhlcvProvider.TEJHQ_EOD, identity.provider)
    }

    @Test
    fun resolvesBseEquityFilename() {
        val identity = ResearchCsvFolderImporter.resolveInstrumentFromFileName("BSE_TCS_DAILY.csv")
        assertEquals("BSE", identity.exchange)
        assertEquals("TCS", identity.canonicalSymbol)
    }

    @Test
    fun resolvesNiftySectorFilenameToOfficialDailyProvider() {
        val identity = ResearchCsvFolderImporter.resolveInstrumentFromFileName("NSE_NIFTY_BANK_1D.csv")
        assertEquals("NIFTY BANK", identity.canonicalSymbol)
        assertEquals(OhlcvProvider.UPSTOX_V3_HISTORICAL, identity.provider)
    }

    @Test
    fun rejectsIntradayBarsInDailyFolderImport() {
        val bars = listOf(
            bar("2026-11-24T09:15:00+05:30"),
            bar("2026-11-24T09:20:00+05:30")
        )
        assertThrows(IllegalArgumentException::class.java) {
            ResearchCsvFolderImporter.validateDailyBars(bars)
        }
    }

    @Test
    fun acceptsOneBarPerTradingDate() {
        ResearchCsvFolderImporter.validateDailyBars(
            listOf(bar("2026-11-23T15:30:00+05:30"), bar("2026-11-24T15:30:00+05:30"))
        )
    }


    @Test
    fun parsesYahooStyleDailyCsvWithDateAndQuotedThousandsSeparators() {
        val bars = ResearchCsvFolderImporter.parseFolderCsv(
            "Date,Open,High,Low,Close,Adj Close,Volume\n" +
                "2026-11-23,\"1,234.00\",1250,1200,1240,1240,\"1,234,567\"",
            volumeRequired = true
        )
        assertEquals(1, bars.size)
        assertEquals(1234.0, bars.single().open, 0.0)
        assertEquals(1_234_567L, bars.single().volume)
        assertEquals("2026-11-23T00:00+05:30", bars.single().timestamp.toString())
    }

    @Test
    fun parsesIndianDateFormatsAndTimezoneNaiveLocalDateTimes() {
        val dateBars = ResearchCsvFolderImporter.parseFolderCsv(
            "Date,Open,High,Low,Close,Volume\n23/11/2026,100,102,99,101,1000",
            volumeRequired = true
        )
        val timeBars = ResearchCsvFolderImporter.parseFolderCsv(
            "DateTime,Open,High,Low,Close,Volume\n2026-11-24T15:30:00,100,102,99,101,1000",
            volumeRequired = true
        )
        assertEquals("2026-11-23T00:00+05:30", dateBars.single().timestamp.toString())
        assertEquals("2026-11-24T15:30+05:30", timeBars.single().timestamp.toString())
    }

    @Test
    fun allowsMissingVolumeForIndexDailyCsv() {
        val bars = ResearchCsvFolderImporter.parseFolderCsv(
            "Date,Open,High,Low,Close\n2026-11-24,100,102,99,101",
            volumeRequired = false
        )
        assertEquals(0L, bars.single().volume)
    }

    @Test
    fun requiresVolumeForEquityDailyCsv() {
        assertThrows(IllegalArgumentException::class.java) {
            ResearchCsvFolderImporter.parseFolderCsv(
                "Date,Open,High,Low,Close\n2026-11-24,100,102,99,101",
                volumeRequired = true
            )
        }
    }

    @Test
    fun rejectsTwoTimestampsInSameIndianMarketSessionAcrossOffsets() {
        val bars = listOf(
            bar("2026-11-23T15:30:00+05:30"),
            bar("2026-11-23T10:00:00Z")
        )
        assertThrows(IllegalArgumentException::class.java) {
            ResearchCsvFolderImporter.validateDailyBars(bars)
        }
    }


    @Test
    fun decodesUtf16LittleEndianExchangeCsv() {
        val text = "Date,Open,High,Low,Close,Volume\n2026-11-24,100,102,99,101,1000"
        val payload = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val decoded = ResearchCsvFolderImporter.decodeCsvBytes(payload)
        val bars = ResearchCsvFolderImporter.parseFolderCsv(decoded, volumeRequired = true)
        assertEquals(1, bars.size)
        assertEquals(101.0, bars.single().close, 0.0)
    }

    @Test
    fun extractsSingleCsvFromNseStyleZipArchive() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("BhavCopy_NSE_CM.csv"))
            zip.write("TradDt,TckrSymb,SctySrs,OpnPric,HghPric,LwPric,ClsPric,TtlTradgVol\n2026-11-24,RELIANCE,EQ,100,102,99,101,1000".toByteArray())
            zip.closeEntry()
        }
        val csv = ResearchCsvFolderImporter.decodeCsvPayload("BhavCopy_NSE_CM.zip", output.toByteArray())
        val grouped = com.rovexquant.app.domain.UniversalOhlcvCsvParser.parseExchangeBhavcopy(csv, "NSE")!!
        assertEquals(setOf("RELIANCE"), grouped.keys)
    }

    @Test
    fun rejectsAmbiguousZipWithMultipleCsvFiles() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            listOf("one.csv", "two.csv").forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write("a,b\n1,2".toByteArray())
                zip.closeEntry()
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResearchCsvFolderImporter.decodeCsvPayload("reports.zip", output.toByteArray())
        }
    }

    private fun bar(timestamp: String) = OhlcvBar(
        timestamp = OffsetDateTime.parse(timestamp),
        open = 100.0,
        high = 102.0,
        low = 99.0,
        close = 101.0,
        volume = 1000L
    )
}
