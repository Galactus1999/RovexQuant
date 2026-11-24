package com.rovexquant.app.data.local

import android.content.Context
import com.rovexquant.app.data.repository.QuantDataRepository
import com.rovexquant.app.domain.OhlcvBar
import com.rovexquant.app.domain.OhlcvCsvResearch
import com.rovexquant.app.domain.InstrumentIdentity
import com.rovexquant.app.domain.MarketOhlcvSeries
import com.rovexquant.app.domain.ChartSeriesMapper
import com.rovexquant.app.domain.OhlcvProvider
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Coordinates one-symbol incremental refreshes without mixing provider or UI logic. */
object TejHqOhlcvSyncManager {
    suspend fun fetchAndSave(
        context: Context,
        repository: QuantDataRepository,
        instrument: InstrumentIdentity,
        from: LocalDate,
        to: LocalDate,
        incremental: Boolean
    ): MarketOhlcvSeries = withContext(Dispatchers.IO) {
        val existing = runCatching {
            val cached = TejHqOhlcvStore.load(context, instrument).orEmpty()
            if (cached.isNotEmpty()) {
                ChartSeriesMapper.map(
                    MarketOhlcvSeries(
                        instrument = instrument,
                        bars = cached,
                        volumeAvailable = instrument.instrumentType == com.rovexquant.app.domain.InstrumentType.EQUITY,
                        adjustmentStatus = when (instrument.provider) {
                            OhlcvProvider.TEJHQ_EOD -> "Raw end-of-day bhavcopy prices; corporate-action adjustment not applied"
                            OhlcvProvider.NSE_INDICES_PORTAL -> "Price Return Index OHLC; dividends are not included"
                            OhlcvProvider.UPSTOX_V3_INTRADAY -> "Raw intraday OHLCV; corporate-action adjustment not applied"
                        }
                    )
                )
            }
            cached
        }.getOrElse { emptyList() }
        val fetchFrom = if (incremental) {
            TejHqOhlcvStore.latestDate(existing)?.minusDays(7)?.coerceAtLeast(from) ?: from
        } else from
        val fetched = repository.fetchInstrumentOhlcv(instrument, fetchFrom, to)
        if (fetched.bars.isNotEmpty()) ChartSeriesMapper.map(fetched)
        val merged = if (incremental && existing.isNotEmpty()) {
            OhlcvCsvResearch.merge(existing, fetched.bars)
        } else fetched.bars
        require(merged.isNotEmpty()) { "No valid historical bars are available to save." }
        ChartSeriesMapper.map(fetched.copy(bars = merged))
        TejHqOhlcvStore.save(context, instrument, merged)
        TejHqOhlcvStore.markSynced(context, instrument)
        fetched.copy(bars = merged, retrievedAt = Instant.now())
    }

    private val marketZone = ZoneId.of("Asia/Kolkata")
    private const val AUTO_SYNC_INTERVAL_MILLIS = 12L * 60L * 60L * 1000L

    suspend fun fetchAndSave(
        context: Context,
        repository: QuantDataRepository,
        exchange: String,
        symbol: String,
        from: LocalDate,
        to: LocalDate,
        incremental: Boolean
    ): List<OhlcvBar> = withContext(Dispatchers.IO) {
        val normalizedExchange = exchange.trim().lowercase()
        val normalizedSymbol = symbol.trim().uppercase()
        // Cache identity is the requested exchange+symbol, not the last-edited UI settings.
        // This allows incremental refresh of any previously imported instrument without
        // accidentally merging another symbol's bars.
        val existing = runCatching {
            TejHqOhlcvStore.load(context, normalizedExchange, normalizedSymbol).orEmpty()
        }.getOrElse { emptyList() }
        val fetchFrom = if (incremental) {
            TejHqOhlcvStore.latestDate(existing)?.minusDays(7)?.coerceAtLeast(from) ?: from
        } else from
        val fetched = repository.fetchTejHqOhlcv(
            normalizedExchange, normalizedSymbol, fetchFrom, to
        )
        val merged = if (incremental) OhlcvCsvResearch.merge(existing, fetched) else fetched
        TejHqOhlcvStore.save(context, normalizedExchange, normalizedSymbol, merged)
        TejHqOhlcvStore.saveSettings(
            context,
            OhlcvSourceSettings(
                exchange = normalizedExchange,
                symbol = normalizedSymbol,
                fromDate = from.toString(),
                autoSync = TejHqOhlcvStore.settings(context).autoSync
            )
        )
        TejHqOhlcvStore.markSynced(context, normalizedExchange, normalizedSymbol)
        merged
    }

    suspend fun refreshIfDue(
        context: Context,
        repository: QuantDataRepository
    ): List<OhlcvBar>? {
        val settings = TejHqOhlcvStore.settings(context)
        if (!settings.autoSync || settings.lastSyncMillis <= 0L ||
            System.currentTimeMillis() - settings.lastSyncMillis < AUTO_SYNC_INTERVAL_MILLIS
        ) return null

        val from = runCatching { LocalDate.parse(settings.fromDate) }.getOrElse { return null }
        val to = LocalDate.now(marketZone)
        if (from.isAfter(to)) return null
        return fetchAndSave(
            context, repository, settings.exchange, settings.symbol, from, to, incremental = true
        )
    }
}
