package com.rovexquant.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rovexquant.app.data.local.InstrumentMasterStore
import com.rovexquant.app.data.model.MarketEquityInstrument
import com.rovexquant.app.data.model.MarketIndexInstrument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstrumentMasterStoreTest {
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
