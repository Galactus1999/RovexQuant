package com.rovexquant.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rovexquant.app.data.local.InstrumentMasterStore
import com.rovexquant.app.data.local.TejHqOhlcvStore
import com.rovexquant.app.domain.InstrumentCatalog
import com.rovexquant.app.domain.OhlcvCsvResearch
import java.io.File
import com.rovexquant.app.data.model.MarketEquityInstrument
import com.rovexquant.app.data.model.MarketIndexInstrument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstrumentMasterStoreTest {
    @Test fun clearingManualCsvImportPreservesTypedInstrumentHistory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrument = InstrumentCatalog.resolve("CSVTEST")
        val bars = OhlcvCsvResearch.parse(
            "timestamp,open,high,low,close,volume\n" +
                "2025-01-01T15:30:00+05:30,100,102,99,101,1000"
        )
        val importedFile = File(context.filesDir, OhlcvCsvResearch.STORAGE_FILE_NAME)
        val typedFile = File(context.filesDir, TejHqOhlcvStore.cacheFileName(instrument))
        TejHqOhlcvStore.clearResearchDataset(context)
        typedFile.delete()
        try {
            TejHqOhlcvStore.save(context, bars)
            TejHqOhlcvStore.save(context, instrument, bars)

            TejHqOhlcvStore.clearResearchDataset(context)

            assertFalse(importedFile.exists())
            assertEquals(bars, TejHqOhlcvStore.load(context, instrument))
        } finally {
            importedFile.delete()
            typedFile.delete()
        }
    }

    @Test fun persistsAndRestoresCompanyNamesAndIndexAliasesOnDevice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val equities = listOf(
            MarketEquityInstrument(
                "NSE_EQ|INE002A01018", "RELIANCE INDUSTRIES LIMITED",
                "RELIANCE", "INE002A01018"
            ),
            MarketEquityInstrument(
                "NSE_EQ|INE040A01034", "HDFC BANK LIMITED",
                "HDFCBANK", "INE040A01034"
            )
        )
        val indices = listOf(
            MarketIndexInstrument("NSE_INDEX|Nifty 50", "Nifty 50", "NIFTY 50"),
            MarketIndexInstrument("NSE_INDEX|Nifty Bank", "Nifty Bank", "NIFTY BANK")
        )

        InstrumentMasterStore.saveEquities(context, equities)
        InstrumentMasterStore.saveIndices(context, indices)

        val cachedEquities = InstrumentMasterStore.loadEquities(context)
        val cachedIndices = InstrumentMasterStore.loadIndices(context)
        assertNotNull(cachedEquities)
        assertNotNull(cachedIndices)
        assertEquals(equities, cachedEquities!!.instruments)
        assertEquals(indices, cachedIndices!!.instruments)
        assertTrue(cachedEquities.updatedAtMillis > 0L)
        assertTrue(cachedIndices.updatedAtMillis > 0L)
    }
}
