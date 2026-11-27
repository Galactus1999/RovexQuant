package com.rovexquant.app.data.local

import android.content.Context
import com.rovexquant.app.data.model.MarketHistoryTarget
import com.rovexquant.app.data.repository.QuantDataRepository
import com.rovexquant.app.data.model.QuantSession
import com.rovexquant.app.domain.InstrumentCatalog
import com.rovexquant.app.domain.InstrumentIdentity
import com.rovexquant.app.domain.InstrumentType
import com.rovexquant.app.domain.OhlcvBar
import com.rovexquant.app.domain.OhlcvInterval
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
 * Refreshes imported daily datasets from the persistent EOD Data Hub in exchange
 * batches. NSE indices use the Upstox historical endpoint because the EOD equity
 * store intentionally does not fabricate index bars.
 */
object ResearchCsvAutoSyncManager {
    private val marketZone = ZoneId.of("Asia/Kolkata")
    private val syncMutex = Mutex()
    private const val AUTO_SYNC_INTERVAL_MILLIS = 12L * 60L * 60L * 1000L
    private const val MAX_DATASETS_PER_PASS = 2_000
    private const val MAX_CONCURRENT_INDEX_REQUESTS = 3

    suspend fun sync(
        context: Context,
        repository: QuantDataRepository,
        session: QuantSession,
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
            val results = mutableListOf<DatasetSyncResult>()

            val equityGroups = batch.filter { it.instrumentType == InstrumentType.EQUITY }
                .groupBy { it.exchange }
            for ((exchange, group) in equityGroups) {
                try {
                    results += syncEquityBatch(appContext, repository, session, exchange, group)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    results += group.map {
                        DatasetSyncResult(false, it.label + ": " +
                            (failure.message ?: failure.javaClass.simpleName))
                    }
                }
            }

            val indexDatasets = batch.filter { it.instrumentType != InstrumentType.EQUITY }
            val semaphore = Semaphore(MAX_CONCURRENT_INDEX_REQUESTS)
            results += coroutineScope {
                indexDatasets.map { metadata ->
                    async {
                        semaphore.withPermit {
                            try {
                                syncIndexDataset(appContext, repository, metadata)
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
            return ResearchCsvAutoSyncReport(
                totalDatasets = datasets.size,
                dueDatasets = due.size,
                processedDatasets = results.size,
                updatedDatasets = results.count { it.error == null && it.updated },
                unchangedDatasets = results.count { it.error == null && !it.updated },
                failedDatasets = results.count { it.error != null },
                deferredDatasets = (due.size - batch.size).coerceAtLeast(0),
                errors = errors
            )
        } finally {
            syncMutex.unlock()
        }
    }

    private suspend fun syncEquityBatch(
        context: Context,
        repository: QuantDataRepository,
        session: QuantSession,
        exchange: String,
        datasets: List<ResearchCsvDatasetMetadata>
    ): List<DatasetSyncResult> {
        val toDate = LocalDate.now(marketZone)
        val prepared = mutableListOf<Pair<ResearchCsvDatasetMetadata, List<OhlcvBar>>>()
        val results = mutableListOf<DatasetSyncResult>()
        for (metadata in datasets) {
            if (metadata.priceBasis != "RAW_CLOSE") {
                results += DatasetSyncResult(
                    false, metadata.label + ": adjusted-close CSV cannot be merged with raw EOD prices."
                )
                continue
            }
            try {
                val existing = withContext(Dispatchers.IO) {
                    ResearchCsvDatasetStore.load(context, metadata)
                } ?: throw IllegalStateException("Imported CSV file is missing.")
                require(existing.isNotEmpty()) { "Imported CSV contains no bars." }
                prepared += metadata to existing
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                results += DatasetSyncResult(
                    false, metadata.label + ": " + (failure.message ?: failure.javaClass.simpleName)
                )
            }
        }
        if (prepared.isEmpty()) return results

        val targets = mutableListOf<MarketHistoryTarget>()
        val refreshable = mutableListOf<Pair<ResearchCsvDatasetMetadata, List<OhlcvBar>>>()
        prepared.forEach { (metadata, existing) ->
            val latestDate = existing.maxOf { marketDate(it) }
            if (latestDate.isAfter(toDate)) {
                markIfUnchanged(context, metadata, "EOD Data Hub")
                results += DatasetSyncResult(updated = false)
            } else {
                val fromDate = maxOf(latestDate.minusDays(7), toDate.minusDays(14))
                targets += MarketHistoryTarget(metadata.canonicalSymbol, fromDate)
                refreshable += metadata to existing
            }
        }
        if (targets.isEmpty()) return results
        val response = repository.fetchMarketHistoryBulk(
            session, exchange, targets, toDate
        )
        require(response.barsBySymbol.isNotEmpty()) {
            "The persistent EOD Data Hub has no matching history yet. Wait for a successful scheduled EOD collection."
        }

        refreshable.forEach { (metadata, existing) ->
            try {
                val incoming = response.barsBySymbol[metadata.canonicalSymbol].orEmpty()
                val provider = response.providersBySymbol[metadata.canonicalSymbol] ?: "EOD Data Hub"
                if (incoming.isEmpty()) {
                    markIfUnchanged(context, metadata, provider)
                    results += DatasetSyncResult(updated = false)
                } else {
                    results += persistMerged(context, metadata, existing, incoming, provider)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                results += DatasetSyncResult(
                    false, metadata.label + ": " + (failure.message ?: failure.javaClass.simpleName)
                )
            }
        }
        return results
    }

    private suspend fun syncIndexDataset(
        context: Context,
        repository: QuantDataRepository,
        metadata: ResearchCsvDatasetMetadata
    ): DatasetSyncResult {
        require(metadata.priceBasis == "RAW_CLOSE") {
            "Adjusted-close CSV cannot be merged with raw Upstox history."
        }
        val existing = withContext(Dispatchers.IO) {
            ResearchCsvDatasetStore.load(context, metadata)
        } ?: throw IllegalStateException("Imported CSV file is missing.")
        require(existing.isNotEmpty()) { "Imported CSV contains no bars." }
        val instrument = InstrumentCatalog.resolve(
            metadata.canonicalSymbol, metadata.exchange, OhlcvInterval.DAILY
        )
        require(instrument.instrumentType == metadata.instrumentType) {
            "Imported instrument identity no longer matches the current instrument registry."
        }
        val latestDate = existing.maxOf { marketDate(it) }
        val toDate = LocalDate.now(marketZone)
        val fromDate = maxOf(latestDate.minusDays(7), toDate.minusDays(14))
        if (fromDate.isAfter(toDate)) {
            markIfUnchanged(context, metadata, instrument.provider.sourceLabel)
            return DatasetSyncResult(updated = false)
        }
        val incoming = repository.fetchInstrumentOhlcv(instrument, fromDate, toDate).bars
        if (incoming.isEmpty()) {
            markIfUnchanged(context, metadata, instrument.provider.sourceLabel)
            return DatasetSyncResult(updated = false)
        }
        return persistMerged(
            context, metadata, existing, incoming, instrument.provider.sourceLabel
        )
    }

    private suspend fun persistMerged(
        context: Context,
        metadata: ResearchCsvDatasetMetadata,
        existing: List<OhlcvBar>,
        incoming: List<OhlcvBar>,
        provider: String
    ): DatasetSyncResult {
        val merged = mergeDaily(existing, incoming)
        val changed = merged != existing.sortedBy { marketDate(it) }
        if (!isStillCurrent(context, metadata)) {
            return DatasetSyncResult(updated = false)
        }
        withContext(Dispatchers.IO) {
            if (changed) {
                ResearchCsvDatasetStore.save(
                    context = context,
                    instrument = InstrumentCatalog.resolve(
                        metadata.canonicalSymbol, metadata.exchange, OhlcvInterval.DAILY
                    ),
                    bars = merged,
                    sourceFileName = metadata.sourceFileName,
                    lastSyncProvider = provider,
                    lastSyncedAtMillis = System.currentTimeMillis(),
                    priceBasis = metadata.priceBasis
                )
            } else {
                ResearchCsvDatasetStore.markSynced(context, metadata, provider)
            }
        }
        return DatasetSyncResult(updated = changed)
    }

    private suspend fun markIfUnchanged(
        context: Context,
        metadata: ResearchCsvDatasetMetadata,
        provider: String
    ) {
        if (isStillCurrent(context, metadata)) {
            withContext(Dispatchers.IO) {
                ResearchCsvDatasetStore.markSynced(context, metadata, provider)
            }
        }
    }

    private suspend fun isStillCurrent(
        context: Context,
        metadata: ResearchCsvDatasetMetadata
    ): Boolean = withContext(Dispatchers.IO) {
        ResearchCsvDatasetStore.list(context).firstOrNull {
            it.exchange == metadata.exchange &&
                it.canonicalSymbol == metadata.canonicalSymbol &&
                it.instrumentType == metadata.instrumentType &&
                it.interval == metadata.interval
        }?.updatedAtMillis == metadata.updatedAtMillis
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
}
