package com.rovexquant.app.data.local

import android.content.Context
import com.rovexquant.app.data.repository.QuantDataRepository
import com.rovexquant.app.domain.InstrumentCatalog
import com.rovexquant.app.domain.InstrumentIdentity
import com.rovexquant.app.domain.InstrumentType
import com.rovexquant.app.domain.OhlcvBar
import com.rovexquant.app.domain.OhlcvInterval
import com.rovexquant.app.domain.OhlcvProvider
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

data class ResearchCsvAutoSyncReport(
    val totalDatasets: Int,
    val dueDatasets: Int,
    val processedDatasets: Int,
    val updatedDatasets: Int,
    val unchangedDatasets: Int,
    val failedDatasets: Int,
    val deferredDatasets: Int,
    val errors: List<String>,
    val busy: Boolean = false
) {
    val summary: String
        get() = when {
            busy -> "An imported-dataset refresh is already running."
            totalDatasets == 0 -> "No instrument-mapped CSV datasets are available to refresh."
            dueDatasets == 0 -> "All " + totalDatasets + " imported datasets are within the 12-hour refresh window."
            else -> "Checked " + processedDatasets + "/" + dueDatasets +
                " due datasets · updated " + updatedDatasets +
                " · unchanged " + unchangedDatasets +
                " · failed " + failedDatasets +
                if (deferredDatasets > 0) " · " + deferredDatasets + " deferred to the next pass" else ""
        }
}

private data class DatasetSyncResult(
    val updated: Boolean,
    val error: String? = null
)

/**
 * Refreshes only user-imported, instrument-mapped daily datasets. Provider bars
 * are merged by Indian trading date (not exact timestamp), preserving one daily
 * candle when a CSV uses 15:30 and the provider uses midnight timestamps.
 */
object ResearchCsvAutoSyncManager {
    private val marketZone = ZoneId.of("Asia/Kolkata")
    private val syncMutex = Mutex()
    private const val AUTO_SYNC_INTERVAL_MILLIS = 12L * 60L * 60L * 1000L
    private const val MAX_DATASETS_PER_PASS = 25
    private const val MAX_CONCURRENT_REQUESTS = 3

    suspend fun sync(
        context: Context,
        repository: QuantDataRepository,
        force: Boolean = false
    ): ResearchCsvAutoSyncReport {
        if (!syncMutex.tryLock()) {
            return ResearchCsvAutoSyncReport(
                totalDatasets = 0, dueDatasets = 0, processedDatasets = 0,
                updatedDatasets = 0, unchangedDatasets = 0, failedDatasets = 0,
                deferredDatasets = 0, errors = emptyList(), busy = true
            )
        }
        try {
            val appContext = context.applicationContext
            val datasets = withContext(Dispatchers.IO) { ResearchCsvDatasetStore.list(appContext) }
            val now = System.currentTimeMillis()
            val due = datasets.filter {
                force || it.lastSyncedAtMillis <= 0L ||
                    now - it.lastSyncedAtMillis >= AUTO_SYNC_INTERVAL_MILLIS
            }.sortedBy { it.lastSyncedAtMillis }
            val batch = due.take(MAX_DATASETS_PER_PASS)
            val semaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
            val results = coroutineScope {
                batch.map { metadata ->
                    async {
                        semaphore.withPermit {
                            try {
                                syncOne(appContext, repository, metadata)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                DatasetSyncResult(
                                    updated = false,
                                    error = metadata.label + ": " +
                                        (failure.message ?: failure.javaClass.simpleName)
                                )
                            }
                        }
                    }
                }.awaitAll()
            }
            val errors = results.mapNotNull { it.error }.take(8)
            val updated = results.count { it.error == null && it.updated }
            val unchanged = results.count { it.error == null && !it.updated }
            return ResearchCsvAutoSyncReport(
                totalDatasets = datasets.size,
                dueDatasets = due.size,
                processedDatasets = results.size,
                updatedDatasets = updated,
                unchangedDatasets = unchanged,
                failedDatasets = results.count { it.error != null },
                deferredDatasets = (due.size - batch.size).coerceAtLeast(0),
                errors = errors
            )
        } finally {
            syncMutex.unlock()
        }
    }

    private suspend fun syncOne(
        context: Context,
        repository: QuantDataRepository,
        metadata: ResearchCsvDatasetMetadata
    ): DatasetSyncResult {
        val existing = withContext(Dispatchers.IO) {
            ResearchCsvDatasetStore.load(context, metadata)
        } ?: throw IllegalStateException("Imported CSV file is missing.")
        require(existing.isNotEmpty()) { "Imported CSV contains no bars." }
        val instrument = resolveInstrument(metadata)
        val latestDate = existing.maxOf { marketDate(it) }
        val from = latestDate.minusDays(7)
        val to = LocalDate.now(marketZone)
        if (from.isAfter(to)) {
            withContext(Dispatchers.IO) {
                ResearchCsvDatasetStore.markSynced(context, metadata, instrument.provider.sourceLabel)
            }
            return DatasetSyncResult(updated = false)
        }

        val fetched = repository.fetchInstrumentOhlcv(instrument, from, to).bars
        if (fetched.isEmpty()) {
            withContext(Dispatchers.IO) {
                ResearchCsvDatasetStore.markSynced(context, metadata, instrument.provider.sourceLabel)
            }
            return DatasetSyncResult(updated = false)
        }
        val merged = mergeDaily(existing, fetched)
        val changed = merged != existing.sortedBy { marketDate(it) }

        // Do not overwrite a newer CSV import that completed while the network request was running.
        val current = withContext(Dispatchers.IO) { ResearchCsvDatasetStore.list(context) }
            .firstOrNull { sameDataset(it, metadata) }
        if (current == null || current.updatedAtMillis != metadata.updatedAtMillis) {
            return DatasetSyncResult(updated = false)
        }
        withContext(Dispatchers.IO) {
            if (changed) {
                ResearchCsvDatasetStore.save(
                    context = context,
                    instrument = instrument,
                    bars = merged,
                    sourceFileName = metadata.sourceFileName,
                    lastSyncProvider = instrument.provider.sourceLabel,
                    lastSyncedAtMillis = System.currentTimeMillis()
                )
            } else {
                ResearchCsvDatasetStore.markSynced(
                    context, metadata, instrument.provider.sourceLabel
                )
            }
        }
        return DatasetSyncResult(updated = changed)
    }

    private fun resolveInstrument(metadata: ResearchCsvDatasetMetadata): InstrumentIdentity {
        if (metadata.exchange == "NSE" && metadata.instrumentType == InstrumentType.EQUITY) {
            // Upstox is the primary NSE equity historical provider; TejHQ remains the
            // BSE equity fallback because this integration's BSE candle route is unsupported.
            return InstrumentIdentity(
                exchange = metadata.exchange,
                canonicalSymbol = metadata.canonicalSymbol,
                instrumentType = metadata.instrumentType,
                provider = OhlcvProvider.UPSTOX_V3_HISTORICAL,
                providerSymbol = metadata.canonicalSymbol,
                interval = OhlcvInterval.DAILY
            )
        }
        val resolved = InstrumentCatalog.resolve(
            metadata.canonicalSymbol, metadata.exchange, OhlcvInterval.DAILY
        )
        require(resolved.instrumentType == metadata.instrumentType) {
            "Imported instrument identity no longer matches the current instrument registry."
        }
        return resolved
    }

    internal fun mergeDaily(existing: List<OhlcvBar>, incoming: List<OhlcvBar>): List<OhlcvBar> {
        require(existing.size + incoming.size <= 100_000) {
            "Merged daily dataset exceeds the 100,000-row safety limit."
        }
        val byDate = TreeMap<LocalDate, OhlcvBar>()
        existing.forEach { bar -> byDate[marketDate(bar)] = bar }
        val incomingDates = HashSet<LocalDate>()
        incoming.forEach { bar ->
            val date = marketDate(bar)
            require(incomingDates.add(date)) {
                "Provider returned more than one daily candle for " + date
            }
            byDate[date] = bar
        }
        return byDate.values.toList()
    }

    private fun marketDate(bar: OhlcvBar): LocalDate =
        bar.timestamp.atZoneSameInstant(marketZone).toLocalDate()

    private fun sameDataset(
        first: ResearchCsvDatasetMetadata,
        second: ResearchCsvDatasetMetadata
    ): Boolean =
        first.exchange == second.exchange &&
            first.canonicalSymbol == second.canonicalSymbol &&
            first.instrumentType == second.instrumentType &&
            first.interval == second.interval
}
