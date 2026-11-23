package com.rovexquant.app.data.repository

import com.rovexquant.app.data.local.QuantSessionStore
import com.rovexquant.app.data.model.AiChatMessage
import com.rovexquant.app.data.model.AiCompletion
import com.rovexquant.app.data.model.AiProvider
import com.rovexquant.app.data.model.AiStrategyBrief
import com.rovexquant.app.data.model.EodMarketSnapshot
import com.rovexquant.app.data.model.IntelligenceFeed
import com.rovexquant.app.data.model.MarketIndexInstrument
import com.rovexquant.app.data.model.MarketEquityInstrument
import com.rovexquant.app.data.model.MarketTick
import com.rovexquant.app.data.model.MacroResearchSummary
import com.rovexquant.app.data.model.EventStudyResult
import com.rovexquant.app.data.model.MarketIngestionStatus
import com.rovexquant.app.data.model.NseMcpCatalog
import com.rovexquant.app.data.model.NseMcpResult
import com.rovexquant.app.data.model.QuantSession

/** Repository contract consumed by feature state holders and UI tests. */
interface QuantDataRepository {
    suspend fun loadSavedConnection(): QuantSessionStore.SavedConnection
    suspend fun loadSession(): QuantSession?
    suspend fun saveSession(session: QuantSession, username: String)
    suspend fun clearSession()
    suspend fun login(baseUrl: String, username: String, password: String): QuantSession
    suspend fun validateSession(session: QuantSession): Boolean
    suspend fun fetchIndexInstruments(session: QuantSession): List<MarketIndexInstrument>
    suspend fun fetchEquityInstruments(session: QuantSession): List<MarketEquityInstrument>
    suspend fun fetchNseMcpTools(session: QuantSession, server: String): List<NseMcpCatalog>
    suspend fun fetchAiProviders(session: QuantSession): List<AiProvider>
    suspend fun saveAiProviderCredential(session: QuantSession, providerId: String, apiKey: String)
    suspend fun deleteAiProviderCredential(session: QuantSession, providerId: String)
    suspend fun saveUpstoxAnalyticsToken(session: QuantSession, token: String)
    suspend fun fetchUpstoxConfigured(session: QuantSession): Boolean
    suspend fun fetchLatestEodMarketSnapshot(session: QuantSession, exchange: String = "nse"): EodMarketSnapshot
    suspend fun requestMarketIngestion(
        session: QuantSession, exchange: String = "nse", force: Boolean = false
    ): String
    suspend fun fetchMarketIngestionStatus(session: QuantSession, exchange: String = "nse"): MarketIngestionStatus
    suspend fun fetchIntelligenceEvents(session: QuantSession, query: String, timespan: String): IntelligenceFeed
    suspend fun fetchMacroResearch(session: QuantSession): MacroResearchSummary
    suspend fun fetchEventStudy(session: QuantSession, symbol: String, eventDate: String, benchmarkSymbol: String?): EventStudyResult
    suspend fun sendAiMessage(
        session: QuantSession,
        providerId: String,
        model: String,
        messages: List<AiChatMessage>,
        credentialId: String? = null,
        fallbackProviders: List<String> = emptyList()
    ): AiCompletion
    suspend fun requestAiStrategyBrief(
        session: QuantSession,
        providerId: String,
        model: String,
        symbol: String,
        exchange: String,
        fromDate: String,
        toDate: String,
        strategyContext: String,
        credentialId: String? = null,
        fallbackProviders: List<String> = emptyList()
    ): AiStrategyBrief
    suspend fun callNseMcpTool(
        session: QuantSession,
        server: String,
        tool: String,
        argumentsJson: String
    ): NseMcpResult
    fun createMarketStream(
        session: QuantSession,
        onStatus: (String) -> Unit,
        onTicks: (List<MarketTick>) -> Unit
    ): MarketStream
}
