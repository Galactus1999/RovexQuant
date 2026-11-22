package com.rovexquant.app.data.model

data class QuantSession(val baseUrl: String, val accessToken: String)
data class MarketIndexInstrument(val instrumentKey: String, val name: String, val tradingSymbol: String)
data class MarketEquityInstrument(val instrumentKey: String, val name: String, val tradingSymbol: String, val isin: String)
data class NseMcpTool(val name: String, val description: String, val inputSchema: org.json.JSONObject, val readOnly: Boolean)
data class NseMcpCatalog(val server: String, val tools: List<NseMcpTool>, val failure: String? = null)
data class NseMcpResult(val provider: String, val dataMode: String, val expectedDelay: String?, val sourceTimestamp: String?, val resultJson: String)

data class MarketTick(
    val instrumentKey: String,
    val lastPrice: Double,
    val previousClose: Double?,
    val changePercent: Double?,
    val exchangeTimestamp: String?,
    val receivedAt: String,
    val quality: String
)


data class EodMarketSnapshot(
    val provider: String,
    val dataMode: String,
    val exchange: String,
    val marketScope: String,
    val tradingDate: String,
    val totalRows: Int,
    val equitySeriesRows: Int,
    val pricedSymbols: Int,
    val advances: Int,
    val declines: Int,
    val unchanged: Int,
    val topGainers: List<EodStockMove>,
    val topLosers: List<EodStockMove>,
    val mostActiveByVolume: List<EodStockMove>
)

data class EodStockMove(
    val symbol: String,
    val name: String,
    val series: String?,
    val close: Double?,
    val previousClose: Double?,
    val change: Double?,
    val changePercent: Double?,
    val volume: Long,
    val turnover: Double?
)


/** Latest ingestion attempt and most recent successful persisted EOD batch. */
data class MarketIngestionStatus(
    val exchange: String,
    val status: String,
    val provider: String? = null,
    val tradingDate: String? = null,
    val rowsReceived: Int = 0,
    val rowsSkipped: Int = 0,
    val rowsUpserted: Int = 0,
    val startedAt: String? = null,
    val completedAt: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val lastSuccessfulTradingDate: String? = null,
    val lastSuccessfulProvider: String? = null,
    val lastSuccessfulRows: Int = 0,
    val lastSuccessfulAt: String? = null
)
