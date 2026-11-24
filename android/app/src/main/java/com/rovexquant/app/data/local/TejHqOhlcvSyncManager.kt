package com.rovexquant.app.data.local

import android.content.Context
import com.rovexquant.app.data.repository.QuantDataRepository
import com.rovexquant.app.domain.OhlcvBar
import com.rovexquant.app.domain.OhlcvCsvResearch
import java.time.LocalDate
import java.time.ZoneId

/** Coordinates one-symbol incremental refreshes without mixing provider or UI logic. */
object TejHqOhlcvSyncManager {
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
    ): List<OhlcvBar> {
        val normalizedExchange = exchange.trim().lowercase()
        val normalizedSymbol = symbol.trim().uppercase()
        val existing = runCatching { TejHqOhlcvStore.load(context).orEmpty() }.getOrElse { emptyList() }
        val fetchFrom = if (incremental) {
            TejHqOhlcvStore.latestDate(existing)?.minusDays(7)?.coerceAtLeast(from) ?: from
        } else from
        val fetched = repository.fetchTejHqOhlcv(
            normalizedExchange, normalizedSymbol, fetchFrom, to
        )
        val merged = if (incremental) OhlcvCsvResearch.merge(existing, fetched) else fetched
        TejHqOhlcvStore.save(context, merged)
        TejHqOhlcvStore.saveSettings(
            context,
            OhlcvSourceSettings(
                exchange = normalizedExchange,
                symbol = normalizedSymbol,
                fromDate = from.toString(),
                autoSync = TejHqOhlcvStore.settings(context).autoSync
            )
        )
        TejHqOhlcvStore.markSynced(context)
        return merged
    }

    suspend fun refreshIfDue(
        context: Context,
        repository: QuantDataRepository
    ): List<OhlcvBar>? {
        val settings = TejHqOhlcvStore.settings(context)
        if (!settings.autoSync || settings.lastSyncMillis > 0L &&
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
