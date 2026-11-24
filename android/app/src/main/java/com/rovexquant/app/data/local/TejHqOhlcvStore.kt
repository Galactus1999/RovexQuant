package com.rovexquant.app.data.local

import android.content.Context
import com.rovexquant.app.domain.OhlcvBar
import com.rovexquant.app.domain.OhlcvCsvResearch
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate

data class OhlcvSourceSettings(
    val exchange: String = "nse",
    val symbol: String = "RELIANCE",
    val fromDate: String = "2010-01-04",
    val autoSync: Boolean = true,
    val lastSyncMillis: Long = 0L
)

/** Device-private OHLCV cache and small sync preferences; never uploads bars to Quant backend. */
object TejHqOhlcvStore {
    private const val PREFS = "tejhq_ohlcv_sync"
    private const val KEY_EXCHANGE = "exchange"
    private const val KEY_SYMBOL = "symbol"
    private const val KEY_FROM = "from"
    private const val KEY_AUTO = "auto_sync"
    private const val KEY_LAST_SYNC = "last_sync"
    private const val FILE_NAME = OhlcvCsvResearch.STORAGE_FILE_NAME

    fun load(context: Context): List<OhlcvBar>? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return null
        return OhlcvCsvResearch.parse(file.readText(Charsets.UTF_8))
    }

    fun save(context: Context, bars: List<OhlcvBar>) {
        val destination = File(context.filesDir, FILE_NAME)
        val temporary = File(context.filesDir, "$FILE_NAME.tmp")
        temporary.writeText(OhlcvCsvResearch.toCsv(bars), Charsets.UTF_8)
        try {
            Files.move(temporary.toPath(), destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

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
            .putString(KEY_EXCHANGE, settings.exchange.lowercase())
            .putString(KEY_SYMBOL, settings.symbol.uppercase())
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
