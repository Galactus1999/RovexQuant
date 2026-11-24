package com.rovexquant.app.data.local

import android.content.Context
import com.rovexquant.app.domain.OhlcvBar
import com.rovexquant.app.domain.OhlcvCsvResearch
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.util.Locale

data class OhlcvSourceSettings(
    val exchange: String = "nse",
    val symbol: String = "RELIANCE",
    val fromDate: String = "2010-01-04",
    val autoSync: Boolean = true,
    val lastSyncMillis: Long = 0L
)

/**
 * Device-private OHLCV cache. Each market series has an isolated file keyed by
 * exchange and canonical symbol; the legacy single-file CSV remains supported
 * for manually imported research datasets.
 */
object TejHqOhlcvStore {
    private const val PREFS = "tejhq_ohlcv_sync"
    private const val KEY_EXCHANGE = "exchange"
    private const val KEY_SYMBOL = "symbol"
    private const val KEY_FROM = "from"
    private const val KEY_AUTO = "auto_sync"
    private const val KEY_LAST_SYNC = "last_sync"
    private const val FILE_NAME = OhlcvCsvResearch.STORAGE_FILE_NAME
    private val exchangePattern = Regex("^[A-Z0-9]{2,8}$")
    private val symbolPattern = Regex("^[A-Z0-9][A-Z0-9&._-]{0,39}$")

    fun load(context: Context): List<OhlcvBar>? {
        val current = settings(context)
        return load(context, current.exchange, current.symbol)
            ?: File(context.filesDir, FILE_NAME).takeIf { it.isFile }
                ?.readText(Charsets.UTF_8)?.let(OhlcvCsvResearch::parse)
    }

    fun load(context: Context, exchange: String, symbol: String): List<OhlcvBar>? {
        val file = seriesFile(context, exchange, symbol)
        if (file.isFile) return OhlcvCsvResearch.parse(file.readText(Charsets.UTF_8))
        // Adopt the pre-multi-series cache only for the identity recorded when it was saved.
        val current = settings(context)
        if (current.exchange.equals(exchange, true) && current.symbol.equals(symbol, true)) {
            val legacy = File(context.filesDir, FILE_NAME)
            if (legacy.isFile) return OhlcvCsvResearch.parse(legacy.readText(Charsets.UTF_8))
        }
        return null
    }

    /** Legacy single-dataset writer used by manual CSV import. */
    fun save(context: Context, bars: List<OhlcvBar>) {
        writeAtomically(File(context.filesDir, FILE_NAME), bars)
    }

    fun save(context: Context, exchange: String, symbol: String, bars: List<OhlcvBar>) {
        writeAtomically(seriesFile(context, exchange, symbol), bars)
    }

    private fun seriesFile(context: Context, exchange: String, symbol: String): File {
        val normalizedExchange = exchange.trim().uppercase(Locale.ROOT)
        val normalizedSymbol = symbol.trim().uppercase(Locale.ROOT)
        require(exchangePattern.matches(normalizedExchange)) { "Invalid exchange identifier." }
        require(symbolPattern.matches(normalizedSymbol)) { "Invalid trading symbol." }
        return File(context.filesDir, "ohlcv_${normalizedExchange}_${normalizedSymbol}.csv")
    }

    private fun writeAtomically(destination: File, bars: List<OhlcvBar>) {
        val temporary = File(contextDirectory(destination), destination.name + ".tmp")
        temporary.writeText(OhlcvCsvResearch.toCsv(bars), Charsets.UTF_8)
        try {
            Files.move(temporary.toPath(), destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun contextDirectory(file: File): File = file.parentFile
        ?: throw IllegalStateException("OHLCV cache directory is unavailable.")

    fun settings(context: Context): OhlcvSourceSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return OhlcvSourceSettings(
            exchange = prefs.getString(KEY_EXCHANGE, "nse") ?: "nse",
            symbol = prefs.getString(KEY_SYMBOL, "RELIANCE") ?: "RELIANCE",
            fromDate = prefs.getString(KEY_FROM, "2010-01-04") ?: "2010-01-04",
            autoSync = prefs.getBoolean(KEY_AUTO, true),
            lastSyncMillis = prefs.getLong(KEY_LAST_SYNC, 0L)
        )
    }

    fun saveSettings(context: Context, settings: OhlcvSourceSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_EXCHANGE, settings.exchange.lowercase(Locale.ROOT))
            .putString(KEY_SYMBOL, settings.symbol.uppercase(Locale.ROOT))
            .putString(KEY_FROM, settings.fromDate)
            .putBoolean(KEY_AUTO, settings.autoSync)
            .apply()
    }

    fun markSynced(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
            .apply()
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
        File(context.filesDir, "$FILE_NAME.tmp").delete()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_LAST_SYNC).apply()
    }

    fun latestDate(bars: List<OhlcvBar>): LocalDate? =
        bars.maxOfOrNull { it.timestamp.toLocalDate() }
}
