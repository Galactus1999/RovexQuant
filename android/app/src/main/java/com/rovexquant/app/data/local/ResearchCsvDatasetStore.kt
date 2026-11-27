package com.rovexquant.app.data.local

import android.content.Context
import com.rovexquant.app.domain.InstrumentIdentity
import com.rovexquant.app.domain.InstrumentType
import com.rovexquant.app.domain.OhlcvBar
import com.rovexquant.app.domain.OhlcvCsvResearch
import com.rovexquant.app.domain.OhlcvInterval
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

data class ResearchCsvDatasetMetadata(
    val exchange: String,
    val canonicalSymbol: String,
    val instrumentType: InstrumentType,
    val interval: OhlcvInterval,
    val rowCount: Int,
    val firstTimestamp: String,
    val lastTimestamp: String,
    val updatedAtMillis: Long,
    val sourceFileName: String,
    val fileName: String,
    val lastSyncedAtMillis: Long = 0L,
    val lastSyncProvider: String? = null
) {
    val label: String get() = "$exchange:$canonicalSymbol"
}

/** Separate user CSV research data; never mixed with provider-owned OHLCV caches. */
object ResearchCsvDatasetStore {
    private const val PREFS = "research_csv_datasets"
    private const val ENTRY_PREFIX = "dataset_"
    private val filePattern = Regex("^research_import_[a-f0-9]{64}\\.csv$")

    fun list(context: Context): List<ResearchCsvDatasetMetadata> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.all.entries.mapNotNull { (key, value) ->
            if (!key.startsWith(ENTRY_PREFIX)) return@mapNotNull null
            val raw = value as? String ?: return@mapNotNull null
            val metadata = runCatching { decode(raw) }.getOrNull() ?: return@mapNotNull null
            if (!filePattern.matches(metadata.fileName)) return@mapNotNull null
            if (!File(context.filesDir, metadata.fileName).isFile) return@mapNotNull null
            metadata
        }.sortedByDescending { it.updatedAtMillis }
    }

    fun load(context: Context, metadata: ResearchCsvDatasetMetadata): List<OhlcvBar>? {
        if (!filePattern.matches(metadata.fileName)) return null
        val file = File(context.filesDir, metadata.fileName)
        if (!file.isFile) return null
        return OhlcvCsvResearch.parse(file.readText(Charsets.UTF_8))
    }

    fun load(context: Context, instrument: InstrumentIdentity): List<OhlcvBar>? {
        val metadata = list(context).firstOrNull {
            it.exchange == instrument.exchange &&
                it.canonicalSymbol == instrument.canonicalSymbol &&
                it.instrumentType == instrument.instrumentType &&
                it.interval == instrument.interval
        } ?: return null
        return load(context, metadata)
    }

    fun save(
        context: Context,
        instrument: InstrumentIdentity,
        bars: List<OhlcvBar>,
        sourceFileName: String,
        lastSyncProvider: String? = null,
        lastSyncedAtMillis: Long = 0L
    ): ResearchCsvDatasetMetadata {
        require(instrument.interval == OhlcvInterval.DAILY) {
            "Automatic folder import currently supports daily OHLCV datasets only."
        }
        require(bars.isNotEmpty()) { "No CSV bars to save." }
        val ordered = bars.sortedBy { it.timestamp.toInstant() }
        require(ordered.size <= 100_000) { "Research dataset exceeds the 100,000-row limit." }
        val identityKey = instrument.exchange + "|" + instrument.instrumentType.name + "|" +
            instrument.canonicalSymbol + "|" + instrument.interval.key
        val key = sha256(identityKey.toByteArray(Charsets.UTF_8))
        val fileName = "research_import_" + key + ".csv"
        val destination = File(context.filesDir, fileName)
        val temporary = File(context.filesDir, fileName + ".tmp")
        temporary.writeText(OhlcvCsvResearch.toCsv(ordered), Charsets.UTF_8)
        try {
            Files.move(
                temporary.toPath(), destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        val metadata = ResearchCsvDatasetMetadata(
            exchange = instrument.exchange,
            canonicalSymbol = instrument.canonicalSymbol,
            instrumentType = instrument.instrumentType,
            interval = instrument.interval,
            rowCount = ordered.size,
            firstTimestamp = ordered.first().timestamp.toString(),
            lastTimestamp = ordered.last().timestamp.toString(),
            updatedAtMillis = System.currentTimeMillis(),
            sourceFileName = sourceFileName.take(120),
            fileName = fileName,
            lastSyncedAtMillis = lastSyncedAtMillis,
            lastSyncProvider = lastSyncProvider
        )
        val persisted = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ENTRY_PREFIX + key, encode(metadata))
            .commit()
        check(persisted) { "Research dataset metadata could not be persisted." }
        return metadata
    }

    fun markSynced(
        context: Context,
        metadata: ResearchCsvDatasetMetadata,
        provider: String,
        syncedAtMillis: Long = System.currentTimeMillis()
    ): ResearchCsvDatasetMetadata {
        val updated = metadata.copy(
            updatedAtMillis = syncedAtMillis,
            lastSyncedAtMillis = syncedAtMillis,
            lastSyncProvider = provider.take(80)
        )
        val identityKey = updated.exchange + "|" + updated.instrumentType.name + "|" +
            updated.canonicalSymbol + "|" + updated.interval.key
        val key = sha256(identityKey.toByteArray(Charsets.UTF_8))
        val persisted = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ENTRY_PREFIX + key, encode(updated))
            .commit()
        check(persisted) { "Research dataset sync metadata could not be persisted." }
        return updated
    }

    fun delete(context: Context, metadata: ResearchCsvDatasetMetadata) {
        if (!filePattern.matches(metadata.fileName)) return
        val identityKey = metadata.exchange + "|" + metadata.instrumentType.name + "|" +
            metadata.canonicalSymbol + "|" + metadata.interval.key
        val key = sha256(identityKey.toByteArray(Charsets.UTF_8))
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getString(ENTRY_PREFIX + key, null)
            ?.let { runCatching { decode(it) }.getOrNull() }
        if (current?.fileName == metadata.fileName) {
            prefs.edit().remove(ENTRY_PREFIX + key).commit()
        }
        File(context.filesDir, metadata.fileName).delete()
    }

    private fun encode(metadata: ResearchCsvDatasetMetadata): String = JSONObject()
        .put("exchange", metadata.exchange)
        .put("canonicalSymbol", metadata.canonicalSymbol)
        .put("instrumentType", metadata.instrumentType.name)
        .put("intervalKey", metadata.interval.key)
        .put("rowCount", metadata.rowCount)
        .put("firstTimestamp", metadata.firstTimestamp)
        .put("lastTimestamp", metadata.lastTimestamp)
        .put("updatedAtMillis", metadata.updatedAtMillis)
        .put("sourceFileName", metadata.sourceFileName)
        .put("fileName", metadata.fileName)
        .put("lastSyncedAtMillis", metadata.lastSyncedAtMillis)
        .put("lastSyncProvider", metadata.lastSyncProvider)
        .toString()

    private fun decode(value: String): ResearchCsvDatasetMetadata {
        val json = JSONObject(value)
        val intervalKey = json.getString("intervalKey")
        val interval = OhlcvInterval.values().firstOrNull { it.key == intervalKey }
            ?: throw IllegalArgumentException("Unknown imported interval.")
        return ResearchCsvDatasetMetadata(
            exchange = json.getString("exchange").uppercase(Locale.ROOT),
            canonicalSymbol = json.getString("canonicalSymbol").uppercase(Locale.ROOT),
            instrumentType = InstrumentType.valueOf(json.getString("instrumentType")),
            interval = interval,
            rowCount = json.getInt("rowCount"),
            firstTimestamp = json.getString("firstTimestamp"),
            lastTimestamp = json.getString("lastTimestamp"),
            updatedAtMillis = json.getLong("updatedAtMillis"),
            sourceFileName = json.optString("sourceFileName", ""),
            fileName = json.getString("fileName"),
            lastSyncedAtMillis = json.optLong("lastSyncedAtMillis", 0L),
            lastSyncProvider = json.optString("lastSyncProvider").takeIf { it.isNotBlank() }
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
