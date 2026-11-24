package com.rovexquant.app.domain

import java.time.Instant
import java.util.Locale

enum class InstrumentType {
    EQUITY,
    BROAD_MARKET_INDEX,
    SECTOR_INDEX
}

enum class OhlcvInterval(
    val key: String,
    val apiUnit: String?,
    val apiInterval: Int?,
    val maximumHistoryDays: Long,
    val isIntraday: Boolean,
    val displayLabel: String
) {
    DAILY("1d", null, null, 6_200L, false, "1D"),
    MINUTE_1("1m", "minutes", 1, 30L, true, "1m"),
    MINUTE_5("5m", "minutes", 5, 30L, true, "5m"),
    MINUTE_10("10m", "minutes", 10, 30L, true, "10m"),
    MINUTE_15("15m", "minutes", 15, 30L, true, "15m"),
    MINUTE_30("30m", "minutes", 30, 90L, true, "30m"),
    HOUR_1("1h", "hours", 1, 90L, true, "1h"),
    HOUR_4("4h", "hours", 4, 90L, true, "4h");

    companion object {
        val intraday: List<OhlcvInterval> = entries.filter { it.isIntraday }
    }
}

enum class OhlcvProvider(val sourceLabel: String) {
    TEJHQ_EOD("TejHQ · NSE/BSE EOD"),
    NSE_INDICES_PORTAL("NSE Indices historical portal"),
    UPSTOX_V3_INTRADAY("Upstox V3 intraday candles")
}

data class InstrumentIdentity(
    val exchange: String,
    val canonicalSymbol: String,
    val instrumentType: InstrumentType,
    val provider: OhlcvProvider,
    val providerSymbol: String,
    val interval: OhlcvInterval = OhlcvInterval.DAILY
) {
    init {
        require(exchange.matches(Regex("^[A-Z]{2,8}$"))) { "Invalid exchange identity." }
        require(canonicalSymbol.isNotBlank() && canonicalSymbol.length <= 48) { "Invalid canonical instrument symbol." }
        require(providerSymbol.isNotBlank() && providerSymbol.length <= 64) { "Invalid provider symbol." }
        require(canonicalSymbol.none { it.code == 92 || it.isISOControl() }) { "Invalid instrument symbol." }
    }

    val cacheIdentity: String
        get() = "${exchange}_${instrumentType.name}_${interval.key}_${canonicalSymbol}"
}

data class MarketOhlcvSeries(
    val instrument: InstrumentIdentity,
    val bars: List<OhlcvBar>,
    val retrievedAt: Instant? = null,
    val volumeAvailable: Boolean,
    val adjustmentStatus: String,
    val providerNote: String? = null
) {
    val sourceLabel: String get() = instrument.provider.sourceLabel
}

/**
 * Explicit instrument registry. Index names are an allowlist sourced from the
 * official NSE Indices historical-data catalogue; unknown NIFTY-like strings
 * are rejected rather than being passed to the equity-symbol endpoint.
 */
object InstrumentCatalog {
    private val broadMarketIndices = setOf(
        "NIFTY 50"
    )

    private val sectorIndices = setOf(
        "NIFTY BANK",
        "NIFTY AUTO",
        "NIFTY CAPITAL GOODS",
        "NIFTY CEMENT",
        "NIFTY CHEMICALS",
        "NIFTY COMMERCIAL & TRANSPORT SERVICES",
        "NIFTY CONSTRUCTION",
        "NIFTY CONSUMER DURABLES",
        "NIFTY CONSUMER SERVICES",
        "NIFTY FINANCIAL SERVICES",
        "NIFTY FINANCIAL SERVICES 25/50",
        "NIFTY FINANCIAL SERVICES EX BANK",
        "NIFTY FMCG",
        "NIFTY HEALTHCARE",
        "NIFTY HOSPITALS",
        "NIFTY HOUSING FINANCE",
        "NIFTY INSURANCE",
        "NIFTY IT",
        "NIFTY MEDIA",
        "NIFTY METAL",
        "NIFTY NBFC",
        "NIFTY OIL & GAS",
        "NIFTY OIL AND GAS INDEX",
        "NIFTY PHARMA",
        "NIFTY POWER",
        "NIFTY PRIVATE BANK",
        "NIFTY PSU BANK",
        "NIFTY REALTY",
        "NIFTY REITS & REALTY",
        "NIFTY RETAIL",
        "NIFTY TELECOMMUNICATIONS"
    )

    private val indexProviderAliases = mapOf(
        "NIFTY OIL & GAS" to "NIFTY OIL AND GAS INDEX"
    )

    private val equityPattern = Regex("^[A-Z0-9][A-Z0-9&-]{0,29}$")

    fun resolve(
        symbol: String,
        exchange: String = "NSE",
        interval: OhlcvInterval = OhlcvInterval.DAILY
    ): InstrumentIdentity {
        val market = exchange.trim().uppercase(Locale.ROOT)
        require(market == "NSE" || market == "BSE") { "Only NSE and BSE instruments are supported." }
        val canonical = symbol.trim().uppercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        require(canonical.isNotEmpty()) { "Enter an instrument symbol." }
        if (interval.isIntraday) {
            require(market == "NSE") { "Upstox V3 intraday candles are currently supported for NSE only." }
        }

        if (market == "NSE" && canonical in broadMarketIndices) {
            return InstrumentIdentity(
                exchange = market,
                canonicalSymbol = canonical,
                instrumentType = InstrumentType.BROAD_MARKET_INDEX,
                provider = if (interval.isIntraday) OhlcvProvider.UPSTOX_V3_INTRADAY else OhlcvProvider.NSE_INDICES_PORTAL,
                providerSymbol = canonical,
                interval = interval
            )
        }
        if (market == "NSE" && canonical in sectorIndices) {
            return InstrumentIdentity(
                exchange = market,
                canonicalSymbol = canonical,
                instrumentType = InstrumentType.SECTOR_INDEX,
                provider = if (interval.isIntraday) OhlcvProvider.UPSTOX_V3_INTRADAY else OhlcvProvider.NSE_INDICES_PORTAL,
                providerSymbol = indexProviderAliases[canonical] ?: canonical,
                interval = interval
            )
        }
        require(!canonical.startsWith("NIFTY") && !canonical.startsWith("BANKNIFTY")) {
            "Unsupported NSE index: $canonical. It cannot be queried as an equity."
        }
        require(equityPattern.matches(canonical)) {
            "Enter a valid $market equity trading symbol, without an exchange suffix."
        }
        return InstrumentIdentity(
            exchange = market,
            canonicalSymbol = canonical,
            instrumentType = InstrumentType.EQUITY,
            provider = if (interval.isIntraday) OhlcvProvider.UPSTOX_V3_INTRADAY else OhlcvProvider.TEJHQ_EOD,
            providerSymbol = canonical,
            interval = interval
        )
    }

    fun isRegisteredIndex(instrument: InstrumentIdentity): Boolean {
        if (instrument.exchange != "NSE" || instrument.provider != OhlcvProvider.NSE_INDICES_PORTAL) return false
        val expectedProviderSymbol = when {
            instrument.canonicalSymbol in broadMarketIndices -> instrument.canonicalSymbol
            instrument.canonicalSymbol in sectorIndices ->
                indexProviderAliases[instrument.canonicalSymbol] ?: instrument.canonicalSymbol
            else -> return false
        }
        val expectedType = if (instrument.canonicalSymbol in broadMarketIndices) {
            InstrumentType.BROAD_MARKET_INDEX
        } else {
            InstrumentType.SECTOR_INDEX
        }
        return instrument.instrumentType == expectedType && instrument.providerSymbol == expectedProviderSymbol
    }
}
