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
