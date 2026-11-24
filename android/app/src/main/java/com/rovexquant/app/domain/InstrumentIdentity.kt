package com.rovexquant.app.domain

import java.time.Instant
import java.util.Locale

enum class InstrumentType {
    EQUITY,
    BROAD_MARKET_INDEX,
    SECTOR_INDEX
}

enum class OhlcvInterval(val key: String) {
    DAILY("1d")
}

enum class OhlcvProvider(val sourceLabel: String) {
    TEJHQ_EOD("TejHQ · NSE/BSE EOD"),
    NSE_INDICES_PORTAL("NSE Indices historical portal")
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
        require(canonicalSymbol.none { it == '/' || it == '\\\\' }) { "Invalid instrument symbol." }
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
        "NIFTY 50",
        "NIFTY BANK"
    )

    private val sectorIndices = setOf(
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

    private val equityPattern = Regex("^[A-Z0-9][A-Z0-9&-]{0,29}$")

    fun resolve(symbol: String, exchange: String = "NSE"): InstrumentIdentity {
        val market = exchange.trim().uppercase(Locale.ROOT)
        require(market == "NSE" || market == "BSE") { "Only NSE and BSE instruments are supported." }
        val canonical = symbol.trim().uppercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        require(canonical.isNotEmpty()) { "Enter an instrument symbol." }

        if (market == "NSE" && canonical in broadMarketIndices) {
            return InstrumentIdentity(
                exchange = market,
                canonicalSymbol = canonical,
                instrumentType = InstrumentType.BROAD_MARKET_INDEX,
                provider = OhlcvProvider.NSE_INDICES_PORTAL,
                providerSymbol = canonical
            )
        }
        if (market == "NSE" && canonical in sectorIndices) {
            return InstrumentIdentity(
                exchange = market,
                canonicalSymbol = canonical,
                instrumentType = InstrumentType.SECTOR_INDEX,
                provider = OhlcvProvider.NSE_INDICES_PORTAL,
                providerSymbol = canonical
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
            provider = OhlcvProvider.TEJHQ_EOD,
            providerSymbol = canonical
        )
    }
}
