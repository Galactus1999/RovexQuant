package com.rovexquant.app.data.repository

import com.rovexquant.app.data.local.QuantSessionStore
import com.rovexquant.app.data.local.InstrumentMasterCache
import com.rovexquant.app.domain.OhlcvBar
import com.rovexquant.app.domain.InstrumentIdentity
import com.rovexquant.app.domain.MarketOhlcvSeries
import com.rovexquant.app.domain.OhlcvProvider
import java.time.LocalDate
import com.rovexquant.app.data.model.AiChatMessage
import com.rovexquant.app.data.model.AiCompletion
import com.rovexquant.app.data.model.AiProvider
import com.rovexquant.app.data.model.AiResearchRun
import com.rovexquant.app.data.model.AiBudgetStatus
import com.rovexquant.app.data.model.AiResearchRunDetail
import com.rovexquant.app.data.model.AiTaskRoute
import com.rovexquant.app.data.model.AiStrategyBrief
import com.rovexquant.app.data.model.EodMarketSnapshot
import com.rovexquant.app.data.model.IntelligenceFeed
import com.rovexquant.app.data.model.MarketIndexInstrument
import com.rovexquant.app.data.model.MarketEquityInstrument
import com.rovexquant.app.data.model.MarketTick
import com.rovexquant.app.data.model.InstrumentQuoteSnapshot
import com.rovexquant.app.data.model.MacroResearchSummary
import com.rovexquant.app.data.model.MarketIntegrityStatus
import com.rovexquant.app.data.model.MarketDataHealth
import com.rovexquant.app.data.model.EventStudyResult
import com.rovexquant.app.data.model.MarketIngestionStatus
import com.rovexquant.app.data.model.NseMcpCatalog
import com.rovexquant.app.data.model.NseMcpResult
import com.rovexquant.app.data.model.QuantSession
import com.rovexquant.app.data.model.TechnicalIndicators
import com.rovexquant.app.data.model.TechnicalIndicatorPeriods

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
    suspend fun loadCachedIndexInstruments(): InstrumentMasterCache<MarketIndexInstrument>? = null
    suspend fun loadCachedEquityInstruments(): InstrumentMasterCache<MarketEquityInstrument>? = null
    suspend fun saveCachedIndexInstruments(instruments: List<MarketIndexInstrument>) = Unit
    suspend fun saveCachedEquityInstruments(instruments: List<MarketEquityInstrument>) = Unit
    suspend fun fetchNseMcpTools(session: QuantSession, server: String): List<NseMcpCatalog>
    suspend fun fetchAiProviders(session: QuantSession): List<AiProvider>
    suspend fun fetchAiTaskRoutes(session: QuantSession): Map<String, AiTaskRoute>
    suspend fun fetchAiResearchRuns(session: QuantSession, limit: Int = 20): List<AiResearchRun>
    suspend fun fetchAiBudgetStatus(session: QuantSession): AiBudgetStatus
    suspend fun fetchAiResearchRun(session: QuantSession, runId: String): AiResearchRunDetail
    suspend fun saveAiProviderCredential(session: QuantSession, providerId: String, apiKey: String)
    suspend fun deleteAiProviderCredential(session: QuantSession, providerId: String)
    suspend fun setAiProviderEnabled(providerId: String, enabled: Boolean) = Unit
    fun isAiProviderEnabled(providerId: String): Boolean = true
    suspend fun saveUpstoxAnalyticsToken(session: QuantSession, token: String)
    suspend fun fetchUpstoxConfigured(session: QuantSession): Boolean
    suspend fun fetchLatestEodMarketSnapshot(session: QuantSession, exchange: String = "nse"): EodMarketSnapshot
    suspend fun requestMarketIngestion(
        session: QuantSession, exchange: String = "nse", force: Boolean = false
    ): String
    suspend fun fetchMarketIngestionStatus(session: QuantSession, exchange: String = "nse"): MarketIngestionStatus
    suspend fun fetchInstrumentQuote(session: QuantSession, symbol: String): InstrumentQuoteSnapshot? = null
    suspend fun fetchIntelligenceEvents(session: QuantSession, query: String, timespan: String): IntelligenceFeed
    suspend fun fetchMacroResearch(session: QuantSession): MacroResearchSummary
    suspend fun fetchMarketIntegrityStatus(session: QuantSession, exchange: String = "NSE"): MarketIntegrityStatus
    suspend fun fetchMarketDataHealth(session: QuantSession, exchange: String = "NSE"): MarketDataHealth
    suspend fun fetchTejHqOhlcv(exchange: String, symbol: String, from: LocalDate, to: LocalDate): List<OhlcvBar>
    suspend fun fetchInstrumentOhlcv(
        instrument: InstrumentIdentity,
        from: LocalDate,
        to: LocalDate
    ): MarketOhlcvSeries {
        require(instrument.provider == OhlcvProvider.TEJHQ_EOD) {
            "This repository implementation does not provide the selected instrument's history."
        }
        return MarketOhlcvSeries(
            instrument = instrument,
            bars = fetchTejHqOhlcv(instrument.exchange, instrument.providerSymbol, from, to),
            volumeAvailable = true,
            adjustmentStatus = "TejHQ raw end-of-day bhavcopy OHLCV"
        )
    }
    suspend fun fetchTechnicalIndicators(
        bars: List<OhlcvBar>, startIndex: Int, periods: TechnicalIndicatorPeriods = TechnicalIndicatorPeriods()
    ): TechnicalIndicators
    suspend fun fetchEventStudy(session: QuantSession, symbol: String, eventDate: String, benchmarkSymbol: String?): EventStudyResult
    suspend fun sendAiMessage(
        session: QuantSession,
        providerId: String?,
        model: String?,
        messages: List<AiChatMessage>,
        credentialId: String? = null,
        fallbackProviders: List<String> = emptyList(),
        taskType: String = "chat"
    ): AiCompletion
    suspend fun requestAiStrategyBrief(
        session: QuantSession,
        providerId: String?,
        model: String?,
        symbol: String,
        exchange: String,
        fromDate: String,
        toDate: String,
        strategyContext: String,
        credentialId: String? = null,
        fallbackProviders: List<String> = emptyList(),
        automaticTaskRouting: Boolean = false
    ): AiStrategyBrief
    suspend fun callNseMcpTool(
        session: QuantSession,
        server: String,
        tool: String,
        argumentsJson: String,
    ): NseMcpResult
    fun createMarketStream(
        session: QuantSession,
        onStatus: (String) -> Unit,
        onTicks: (List<MarketTick>) -> Unit
    ): MarketStream
}
