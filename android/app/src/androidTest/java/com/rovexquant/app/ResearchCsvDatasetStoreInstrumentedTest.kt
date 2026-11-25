package com.rovexquant.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rovexquant.app.data.local.ResearchCsvDatasetStore
import com.rovexquant.app.data.local.TejHqOhlcvStore
import com.rovexquant.app.domain.InstrumentCatalog
import com.rovexquant.app.domain.OhlcvBar
import com.rovexquant.app.domain.OhlcvInterval
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResearchCsvDatasetStoreInstrumentedTest {
    @Test
    fun importedResearchDatasetDoesNotPopulateProviderCache() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrument = InstrumentCatalog.resolve("CSVTESTXYZ", "NSE", OhlcvInterval.DAILY)
        val bars = listOf(
            bar("2026-11-23T15:30:00+05:30", 100.0),
            bar("2026-11-24T15:30:00+05:30", 102.0)
        )
        assertNull(TejHqOhlcvStore.load(context, instrument))
        val metadata = ResearchCsvDatasetStore.save(context, instrument, bars, "NSE_CSVTESTXYZ_1D.csv")
        try {
            assertNull(TejHqOhlcvStore.load(context, instrument))
            assertEquals(bars, ResearchCsvDatasetStore.load(context, instrument))
            assertEquals(1, ResearchCsvDatasetStore.list(context).count { it.label == "NSE:CSVTESTXYZ" })
        } finally {
            ResearchCsvDatasetStore.delete(context, metadata)
        }
        assertNull(ResearchCsvDatasetStore.load(context, instrument))
    }

    private fun bar(timestamp: String, close: Double) = OhlcvBar(
        timestamp = OffsetDateTime.parse(timestamp),
        open = close,
        high = close + 1.0,
        low = close - 1.0,
        close = close,
        volume = 1000L
    )
}
