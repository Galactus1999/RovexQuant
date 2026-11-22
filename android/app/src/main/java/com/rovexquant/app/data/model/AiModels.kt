package com.rovexquant.app.data.model

data class AiProvider(
    val providerId: String,
    val configured: Boolean,
    val credentialIds: List<String>,
    val defaultModel: String? = null
)

data class AiChatMessage(
    val role: String,
    val content: String
)

data class AiCompletion(
    val providerId: String,
    val credentialId: String?,
    val model: String,
    val content: String,
    val fallbackUsed: Boolean
)

data class AiDailyBar(
    val date: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long
)

data class AiStrategyEvidence(
    val latestClose: Double,
    val sma20: Double?,
    val sma50: Double?,
    val rsi14: Double?,
    val atr14: Double?,
    val adx14: Double?,
    val donchianState: String,
    val relativeVolume20: Double?,
    val return21: Double?,
    val return63: Double?,
    val trendContext: String
)

data class AiStrategyBrief(
    val provider: String,
    val dataMode: String,
    val exchange: String,
    val symbol: String,
    val firstTradingDate: String,
    val latestTradingDate: String,
    val receivedAt: String,
    val barCount: Int,
    val evidence: AiStrategyEvidence,
    val bars: List<AiDailyBar>,
    val ai: AiCompletion?,
    val aiError: String? = null,
    val dataRights: String,
    val limitations: List<String>
)
