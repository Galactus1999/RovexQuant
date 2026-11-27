package com.rovexquant.app.data.repository

import android.content.Context
import com.rovexquant.app.data.local.QuantSessionStore
import com.rovexquant.app.data.local.InstrumentMasterStore
import com.rovexquant.app.data.local.InstrumentMasterCache
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
import com.rovexquant.app.data.model.MarketIngestionStatus
import com.rovexquant.app.data.model.MacroResearchSummary
import com.rovexquant.app.data.model.MarketIntegrityStatus
import com.rovexquant.app.data.model.MarketDataHealth
import com.rovexquant.app.data.model.EventStudyResult
import com.rovexquant.app.data.model.NseMcpCatalog
import com.rovexquant.app.data.model.NseMcpResult
import com.rovexquant.app.data.model.QuantSession
import com.rovexquant.app.data.model.TechnicalIndicators
import com.rovexquant.app.data.model.TechnicalIndicatorPeriods
import com.rovexquant.app.data.remote.MarketStreamController
import com.rovexquant.app.data.remote.QuantApi
import com.rovexquant.app.data.remote.TejHqOhlcvClient
import com.rovexquant.app.domain.OhlcvBar
import com.rovexquant.app.domain.InstrumentIdentity
import com.rovexquant.app.domain.MarketOhlcvSeries
import com.rovexquant.app.domain.OhlcvProvider
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.withContext

/** Coordinates remote and local data sources; presentation code never calls transport directly. */
private val AI_PROVIDER_IDS = listOf("openai", "gemini", "deepseek", "groq", "openrouter")

class QuantRepository(context: Context) : QuantDataRepository {
    private val appContext = context.applicationContext

    override suspend fun loadSavedConnection(): QuantSessionStore.SavedConnection =
        withContext(Dispatchers.IO) { QuantSessionStore.loadConnection(appContext) }

    override suspend fun loadSession(): QuantSession? =
        withContext(Dispatchers.IO) { QuantSessionStore.load(appContext) }

    override suspend fun saveSession(session: QuantSession, username: String) =
        withContext(Dispatchers.IO) { QuantSessionStore.save(appContext, session, username) }

    override suspend fun clearSession() =
        withContext(Dispatchers.IO) { QuantSessionStore.clearSession(appContext) }

    override suspend fun login(baseUrl: String, username: String, password: String): QuantSession =
        withContext(Dispatchers.IO) { QuantApi.login(baseUrl, username, password) }

    override suspend fun validateSession(session: QuantSession): Boolean =
        withContext(Dispatchers.IO) { QuantApi.validateSession(session) }

    override suspend fun fetchIndexInstruments(session: QuantSession): List<MarketIndexInstrument> =
        withContext(Dispatchers.IO) { QuantApi.fetchIndexInstruments(session) }

    override suspend fun fetchEquityInstruments(session: QuantSession): List<MarketEquityInstrument> =
        withContext(Dispatchers.IO) { QuantApi.fetchEquityInstruments(session) }

    override suspend fun loadCachedIndexInstruments(): InstrumentMasterCache<MarketIndexInstrument>? =
        withContext(Dispatchers.IO) { InstrumentMasterStore.loadIndices(appContext) }

    override suspend fun loadCachedEquityInstruments(): InstrumentMasterCache<MarketEquityInstrument>? =
        withContext(Dispatchers.IO) { InstrumentMasterStore.loadEquities(appContext) }

    override suspend fun saveCachedIndexInstruments(instruments: List<MarketIndexInstrument>) =
        withContext(Dispatchers.IO) { InstrumentMasterStore.saveIndices(appContext, instruments) }

    override suspend fun saveCachedEquityInstruments(instruments: List<MarketEquityInstrument>) =
        withContext(Dispatchers.IO) { InstrumentMasterStore.saveEquities(appContext, instruments) }

    override suspend fun fetchNseMcpTools(session: QuantSession, server: String): List<NseMcpCatalog> {
        val catalogs = withContext(Dispatchers.IO) { QuantApi.fetchNseMcpTools(session, server) }
        Log.e("THREAD_AUDIT", "repository-return thread=${Thread.currentThread().id}/${Thread.currentThread().name} main=${Looper.getMainLooper().isCurrentThread()}")
        return catalogs
    }

    override suspend fun fetchAiProviders(session: QuantSession): List<AiProvider> =
        withContext(Dispatchers.IO) { QuantApi.fetchAiProviders(session) }

    override suspend fun fetchAiTaskRoutes(session: QuantSession): Map<String, AiTaskRoute> =
        withContext(Dispatchers.IO) { QuantApi.fetchAiTaskRoutes(session) }

    override suspend fun fetchAiResearchRuns(session: QuantSession, limit: Int): List<AiResearchRun> =
        withContext(Dispatchers.IO) { QuantApi.fetchAiResearchRuns(session, limit) }

    override suspend fun fetchAiBudgetStatus(session: QuantSession): AiBudgetStatus =
        withContext(Dispatchers.IO) { QuantApi.fetchAiBudgetStatus(session) }

    override suspend fun fetchAiResearchRun(session: QuantSession, runId: String): AiResearchRunDetail =
        withContext(Dispatchers.IO) { QuantApi.fetchAiResearchRun(session, runId) }

    override suspend fun saveAiProviderCredential(session: QuantSession, providerId: String, apiKey: String) =
        withContext(Dispatchers.IO) { QuantApi.saveAiProviderCredential(session, providerId, apiKey) }

    override suspend fun deleteAiProviderCredential(session: QuantSession, providerId: String) =
        withContext(Dispatchers.IO) { QuantApi.deleteAiProviderCredential(session, providerId) }

    override suspend fun setAiProviderEnabled(providerId: String, enabled: Boolean) =
        withContext(Dispatchers.IO) {
            require(providerId in AI_PROVIDER_IDS) { "Unsupported AI provider." }
            appContext.getSharedPreferences("rovex_ai_provider_preferences", 0)
                .edit().putBoolean(providerId, enabled).apply()
        }

    private fun enabledAiProviderIds(): List<String> {
        val preferences = appContext.getSharedPreferences("rovex_ai_provider_preferences", 0)
        return AI_PROVIDER_IDS.filter { preferences.getBoolean(it, true) }
    }

    override suspend fun saveUpstoxAnalyticsToken(session: QuantSession, token: String) =
        withContext(Dispatchers.IO) { QuantApi.saveUpstoxAnalyticsToken(session, token) }

    override suspend fun fetchUpstoxConfigured(session: QuantSession): Boolean =
        withContext(Dispatchers.IO) { QuantApi.fetchUpstoxConfigured(session) }

    override suspend fun fetchLatestEodMarketSnapshot(session: QuantSession, exchange: String): EodMarketSnapshot =
        withContext(Dispatchers.IO) {
            try {
                QuantApi.fetchLatestEodMarketSnapshot(session, exchange)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (persistedFailure: Exception) {
                try {
                    QuantApi.fetchLatestEodMarketSnapshotDirect(session, exchange)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (directFailure: Exception) {
                    directFailure.addSuppressed(persistedFailure)
                    throw directFailure
                }
            }
        }

    override suspend fun requestMarketIngestion(
        session: QuantSession, exchange: String, force: Boolean
    ): String = withContext(Dispatchers.IO) {
        QuantApi.requestMarketIngestion(session, exchange, force)
    }

    override suspend fun fetchMarketIngestionStatus(
        session: QuantSession, exchange: String
    ): MarketIngestionStatus = withContext(Dispatchers.IO) {
        QuantApi.fetchMarketIngestionStatus(session, exchange)
    }

    override suspend fun fetchInstrumentQuote(
        session: QuantSession, symbol: String
    ): InstrumentQuoteSnapshot? = withContext(Dispatchers.IO) {
        QuantApi.fetchInstrumentQuote(session, symbol)
    }

    override suspend fun fetchIntelligenceEvents(
        session: QuantSession, query: String, timespan: String
    ): IntelligenceFeed = withContext(Dispatchers.IO) {
        QuantApi.fetchIntelligenceEvents(session, query, timespan)
    }

    override suspend fun fetchTejHqOhlcv(
        exchange: String, symbol: String, from: LocalDate, to: LocalDate
    ): List<OhlcvBar> = withContext(Dispatchers.IO) {
        TejHqOhlcvClient.fetch(exchange, symbol, from, to)
    }

    override suspend fun fetchInstrumentOhlcv(
        instrument: InstrumentIdentity,
        from: LocalDate,
        to: LocalDate
    ): MarketOhlcvSeries = withContext(Dispatchers.IO) {
        val bars = when (instrument.provider) {
            OhlcvProvider.TEJHQ_EOD ->
                TejHqOhlcvClient.fetch(instrument.exchange, instrument.providerSymbol, from, to)
            OhlcvProvider.UPSTOX_V3_HISTORICAL, OhlcvProvider.UPSTOX_V3_INTRADAY -> {
                require(
                    if (instrument.provider == OhlcvProvider.UPSTOX_V3_HISTORICAL) {
                        instrument.interval == com.rovexquant.app.domain.OhlcvInterval.DAILY
                    } else instrument.interval.isIntraday
                ) { "Upstox V3 provider and chart timeframe do not match." }
                val session = QuantSessionStore.load(appContext)
                    ?: throw IllegalStateException("Sign in again before requesting intraday candles.")
                QuantApi.fetchUpstoxOhlcv(
                    session, instrument.exchange, instrument.providerSymbol, instrument.interval.key, from, to
                )
            }
        }
        MarketOhlcvSeries(
            instrument = instrument,
            bars = bars,
            retrievedAt = Instant.now(),
            volumeAvailable = instrument.instrumentType == com.rovexquant.app.domain.InstrumentType.EQUITY,
            adjustmentStatus = when (instrument.provider) {
                OhlcvProvider.TEJHQ_EOD -> "Raw end-of-day bhavcopy prices; corporate-action adjustment not applied"
                OhlcvProvider.UPSTOX_V3_HISTORICAL -> "NSE index daily OHLC; total-return treatment is not assumed"
                OhlcvProvider.UPSTOX_V3_INTRADAY -> "Raw intraday OHLCV; corporate-action adjustment not applied"
            },
            providerNote = when (instrument.provider) {
                OhlcvProvider.UPSTOX_V3_HISTORICAL ->
                    if (instrument.instrumentType == com.rovexquant.app.domain.InstrumentType.EQUITY) {
                        "Upstox V3 historical daily OHLCV; server-side read-only Analytics Token; corporate-action adjustment is not applied."
                    } else {
                        "Upstox V3 historical daily index OHLC; server-side read-only Analytics Token; index volume is not available."
                    }
                OhlcvProvider.UPSTOX_V3_INTRADAY ->
                    "Upstox V3 historical/intraday candles; server-side read-only Analytics Token; interval history is provider-limited."
                OhlcvProvider.TEJHQ_EOD -> null
            }
        )
    }

    override suspend fun fetchTechnicalIndicators(
        bars: List<OhlcvBar>,
        startIndex: Int,
        periods: TechnicalIndicatorPeriods
    ): TechnicalIndicators = withContext(Dispatchers.IO) {
        require(bars.size in 1..10_000) { "Indicator request window must contain at most 10,000 bars." }
        val session = QuantSessionStore.load(appContext)
            ?: throw IllegalStateException("Sign in again before calculating technical indicators.")
        QuantApi.fetchTechnicalIndicators(session, bars, startIndex, periods)
    }

    override suspend fun fetchEventStudy(
        session: QuantSession, symbol: String, eventDate: String, benchmarkSymbol: String?
    ): EventStudyResult = withContext(Dispatchers.IO) {
        QuantApi.fetchEventStudy(session, symbol, eventDate, benchmarkSymbol)
    }

    override suspend fun fetchMacroResearch(session: QuantSession): MacroResearchSummary =
        withContext(Dispatchers.IO) { QuantApi.fetchMacroResearch(session) }

    override suspend fun fetchMarketIntegrityStatus(session: QuantSession, exchange: String): MarketIntegrityStatus =
        withContext(Dispatchers.IO) { QuantApi.fetchMarketIntegrityStatus(session, exchange) }

    override suspend fun fetchMarketDataHealth(session: QuantSession, exchange: String): MarketDataHealth =
        withContext(Dispatchers.IO) { QuantApi.fetchMarketDataHealth(session, exchange) }

    override suspend fun sendAiMessage(
        session: QuantSession,
        providerId: String?,
        model: String?,
        messages: List<AiChatMessage>,
        credentialId: String?,
        fallbackProviders: List<String>,
        taskType: String
    ): AiCompletion = withContext(Dispatchers.IO) {
        QuantApi.sendAiMessage(session, providerId, model, messages, credentialId, fallbackProviders, taskType, enabledAiProviderIds())
    }

    override suspend fun requestAiStrategyBrief(
        session: QuantSession,
        providerId: String?,
        model: String?,
        symbol: String,
        exchange: String,
        fromDate: String,
        toDate: String,
        strategyContext: String,
        credentialId: String?,
        fallbackProviders: List<String>,
        automaticTaskRouting: Boolean
    ): AiStrategyBrief = withContext(Dispatchers.IO) {
        QuantApi.requestAiStrategyBrief(
            session, providerId, model, symbol, exchange, fromDate, toDate, strategyContext, credentialId, fallbackProviders, automaticTaskRouting
        )
    }

    override suspend fun callNseMcpTool(
        session: QuantSession,
        server: String,
        tool: String,
        argumentsJson: String,
    ): NseMcpResult = withContext(Dispatchers.IO) {
        QuantApi.callNseMcpTool(session, server, tool, argumentsJson)
    }

    override fun createMarketStream(
        session: QuantSession,
        onStatus: (String) -> Unit,
        onTicks: (List<MarketTick>) -> Unit
    ): MarketStream = MarketStreamController(session, onStatus, onTicks)
}
