package com.rovexquant.app.data.model

data class AiProvider(
    val providerId: String,
    val configured: Boolean,
    val credentialIds: List<String>
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
    val fallbackUsed: Boolean,
    val taskType: String = "chat",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val costUsd: Double? = null,
    val costStatus: String = "UNKNOWN_MODEL_PRICING"
)

data class AiTaskRoute(
    val providerId: String,
    val model: String,
    val fallbackProviders: List<String>
)

data class AiResearchRun(
    val runId: String,
    val taskType: String,
    val providerId: String,
    val model: String,
    val status: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    val costUsd: Double?,
    val costStatus: String,
    val fallbackUsed: Boolean,
    val createdAt: String
)

data class AiBudgetStatus(
    val enabled: Boolean,
    val dailyUsedTokens: Long,
    val dailyLimitTokens: Long?,
    val dailyRemainingTokens: Long?,
    val monthlyUsedTokens: Long,
    val monthlyLimitTokens: Long?,
    val monthlyRemainingTokens: Long?
)

data class AiResearchRunDetail(
    val runId: String,
    val taskType: String,
    val providerId: String,
    val model: String,
    val status: String,
    val requestHash: String,
    val requestPayloadJson: String,
    val responsePayloadJson: String,
    val tokenUsageJson: String,
    val costUsd: Double?,
    val costStatus: String,
    val fallbackUsed: Boolean,
    val sourceRefsJson: String,
    val verificationJson: String,
    val createdAt: String,
    val completedAt: String?
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
