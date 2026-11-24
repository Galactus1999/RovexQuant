package com.rovexquant.app.data.local

import android.content.Context
import com.rovexquant.app.data.model.MarketEquityInstrument
import com.rovexquant.app.data.model.MarketIndexInstrument
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

data class InstrumentMasterCache<T : Any>(
    val instruments: List<T>,
    val updatedAtMillis: Long
)

/**
 * Device-private, atomic cache of the official Upstox/NSE BOD name library.
 * Search remains available after the first successful master download, including offline.
 */
object InstrumentMasterStore {
    private const val VERSION = 1
    private const val MAX_INSTRUMENTS = 20_000
    private val equityKey = Regex("^NSE_EQ\\|IN[A-Z0-9]{10}$")
    private val indexKey = Regex("^NSE_INDEX\\|[A-Za-z0-9 _&()./-]{1,80}$")
    private val symbol = Regex("^[A-Z0-9][A-Z0-9&._-]{0,39}$")

    fun loadEquities(context: Context): InstrumentMasterCache<MarketEquityInstrument>? =
        read(context, "nse-equities.json") { json ->
            val key = json.optString("instrument_key")
            val name = json.optString("name").trim()
            val tradingSymbol = json.optString("trading_symbol").trim().uppercase()
            val isin = json.optString("isin").trim().uppercase()
            if (!equityKey.matches(key) || name.isBlank() || !symbol.matches(tradingSymbol) ||
                !isin.matches(Regex("^INE[A-Z0-9]{9}$"))
            ) null else MarketEquityInstrument(key, name, tradingSymbol, isin)
        }

    fun loadIndices(context: Context): InstrumentMasterCache<MarketIndexInstrument>? =
        read(context, "nse-indices.json") { json ->
            val key = json.optString("instrument_key")
            val name = json.optString("name").trim()
            val tradingSymbol = json.optString("trading_symbol").trim()
            if (!indexKey.matches(key) || name.isBlank() || tradingSymbol.isBlank()) {
                null
            } else MarketIndexInstrument(key, name, tradingSymbol)
        }

    fun saveEquities(context: Context, instruments: List<MarketEquityInstrument>) {
        require(instruments.isNotEmpty() && instruments.size <= MAX_INSTRUMENTS)
        val unique = instruments.distinctBy { it.instrumentKey }
        val payload = JSONArray().apply {
            unique.forEach { item ->
                put(JSONObject()
                    .put("instrument_key", item.instrumentKey)
                    .put("name", item.name)
                    .put("trading_symbol", item.tradingSymbol)
                    .put("isin", item.isin))
            }
        }
        write(context, "nse-equities.json", payload)
    }

    fun saveIndices(context: Context, instruments: List<MarketIndexInstrument>) {
        require(instruments.isNotEmpty() && instruments.size <= MAX_INSTRUMENTS)
        val unique = instruments.distinctBy { it.instrumentKey }
        val payload = JSONArray().apply {
            unique.forEach { item ->
                put(JSONObject()
                    .put("instrument_key", item.instrumentKey)
                    .put("name", item.name)
                    .put("trading_symbol", item.tradingSymbol))
            }
        }
        write(context, "nse-indices.json", payload)
    }

    private fun <T : Any> read(
        context: Context,
        fileName: String,
        map: (JSONObject) -> T?
    ): InstrumentMasterCache<T>? = runCatching {
        val file = File(File(context.filesDir, "instrument-master"), fileName)
        if (!file.isFile || file.length() !in 1..(8L * 1024L * 1024L)) return null
        val root = JSONObject(file.readText(Charsets.UTF_8))
        if (root.optInt("version") != VERSION) return null
        val updatedAt = root.optLong("updated_at_epoch_ms", 0L)
        if (updatedAt <= 0L) return null
        val array = root.optJSONArray("instruments") ?: return null
        if (array.length() !in 1..MAX_INSTRUMENTS) return null
        val items = buildList<T> {
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                map(row)?.let { add(it) }
            }
        }.distinctBy { it.toString() }
        if (items.isEmpty()) return null
        InstrumentMasterCache(items, updatedAt)
    }.getOrNull()

    private fun write(context: Context, fileName: String, instruments: JSONArray) {
        val directory = File(context.filesDir, "instrument-master").apply { mkdirs() }
        val destination = File(directory, fileName)
        val temporary = File(directory, "$fileName.tmp")
        val root = JSONObject()
            .put("version", VERSION)
            .put("updated_at_epoch_ms", System.currentTimeMillis())
            .put("instruments", instruments)
        temporary.writeText(root.toString(), Charsets.UTF_8)
        try {
            Files.move(
                temporary.toPath(), destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
