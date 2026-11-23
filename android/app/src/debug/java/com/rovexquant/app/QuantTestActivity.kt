package com.rovexquant.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.rovexquant.app.data.local.QuantSessionStore
import com.rovexquant.app.data.model.AiChatMessage
import com.rovexquant.app.data.model.AiCompletion
import com.rovexquant.app.data.model.AiDailyBar
import com.rovexquant.app.data.model.AiProvider
import com.rovexquant.app.data.model.AiStrategyBrief
import com.rovexquant.app.data.model.EodMarketSnapshot
import com.rovexquant.app.data.model.EodStockMove
import com.rovexquant.app.data.model.AiStrategyEvidence
import com.rovexquant.app.data.model.MarketIndexInstrument
import com.rovexquant.app.data.model.MarketEquityInstrument
import com.rovexquant.app.data.model.IntelligenceFeed
import com.rovexquant.app.data.model.IntelligenceSource
import com.rovexquant.app.data.model.MarketEvent
import com.rovexquant.app.data.model.SectorImpact
import com.rovexquant.app.data.model.MarketTick
import com.rovexquant.app.data.model.MarketIngestionStatus
import com.rovexquant.app.data.model.MacroResearchSummary
import com.rovexquant.app.data.model.MacroSeriesObservation
import com.rovexquant.app.data.model.AmfiNavObservation
import com.rovexquant.app.data.model.EventStudyResult
import com.rovexquant.app.data.model.NseMcpCatalog
import com.rovexquant.app.data.model.NseMcpResult
import com.rovexquant.app.data.model.QuantSession
import com.rovexquant.app.data.repository.MarketStream
import com.rovexquant.app.data.repository.QuantDataRepository
import com.rovexquant.app.feature.workspace.QuantWorkspaceViewModel
import org.json.JSONObject

/**
 * Deterministic test host compiled only into the debug variant.
 *
 * Workspace tests restore a pre-authenticated fake session so dashboard tests
 * never depend on typing credentials or contacting a backend. Login tests use
 * [QuantLoginTestActivity], which deliberately starts without a session.
 */
open class QuantTestActivity : MainActivity() {
    open val fakeRepository = FakeQuantRepository(restoreAuthenticatedSession = true)

    override fun createWorkspaceViewModelFactory(): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(QuantWorkspaceViewModel::class.java))
                return QuantWorkspaceViewModel(application, fakeRepository) as T
            }
        }
}

/** Separate host keeps login-form coverage independent of authenticated UI tests. */
class QuantLoginTestActivity : QuantTestActivity() {
    override val fakeRepository = FakeQuantRepository(restoreAuthenticatedSession = false)
}

class FakeQuantRepository(
    private val restoreAuthenticatedSession: Boolean
) : QuantDataRepository {
    var rejectLogin = false
    private val testSession = QuantSession("https://quant.test", "test-session-token")

    override suspend fun loadSavedConnection() =
        QuantSessionStore.SavedConnection(testSession.baseUrl, "quant-test")

    override suspend fun loadSession(): QuantSession? =
        testSession.takeIf { restoreAuthenticatedSession }

    override suspend fun saveSession(session: QuantSession, username: String) = Unit
    override suspend fun clearSession() = Unit

    override suspend fun login(baseUrl: String, username: String, password: String): QuantSession {
        if (rejectLogin || username != "quant-test" || password != "correct-password") {
            throw IllegalStateException("Test credentials rejected.")
        }
        require(baseUrl.startsWith("https://"))
        return testSession
    }

    override suspend fun validateSession(session: QuantSession): Boolean = true

    override suspend fun fetchIndexInstruments(session: QuantSession) = listOf(
        MarketIndexInstrument("NSE_INDEX|Nifty 50", "Nifty 50", "NIFTY 50"),
        MarketIndexInstrument("NSE_INDEX|Nifty Bank", "Nifty Bank", "NIFTY BANK")
    )

    override suspend fun fetchEquityInstruments(session: QuantSession) = listOf(
        MarketEquityInstrument("NSE_EQ|INE002A01018", "RELIANCE INDUSTRIES LTD", "RELIANCE", "INE002A01018"),
        MarketEquityInstrument("NSE_EQ|INE040A01034", "HDFC BANK LTD", "HDFCBANK", "INE040A01034"),
        MarketEquityInstrument("NSE_EQ|INE009A01021", "INFOSYS LIMITED", "INFY", "INE009A01021")
    )

    override suspend fun fetchNseMcpTools(session: QuantSession, server: String): List<NseMcpCatalog> {
        val servers = if (server == "both") listOf("bhavcopy", "cmmkt") else listOf(server)
        return servers.map {
            NseMcpCatalog(it, emptyList(), "No tools in deterministic UI test fixture.")
        }
    }

    override suspend fun fetchAiProviders(session: QuantSession) =
        listOf(AiProvider("groq", true, listOf("default")))

    override suspend fun saveAiProviderCredential(session: QuantSession, providerId: String, apiKey: String) = Unit
    override suspend fun deleteAiProviderCredential(session: QuantSession, providerId: String) = Unit
    override suspend fun saveDalalOsApiKey(session: QuantSession, apiKey: String) = Unit
    override suspend fun deleteDalalOsApiKey(session: QuantSession) = Unit
    override suspend fun fetchDalalOsConfigured(session: QuantSession): Boolean = false
    override suspend fun saveUpstoxAnalyticsToken(session: QuantSession, token: String) = Unit
    override suspend fun fetchUpstoxConfigured(session: QuantSession): Boolean = false
    override suspend fun requestMarketIngestion(
        session: QuantSession,
        exchange: String,
        force: Boolean
    ): String = "SUCCEEDED"

    override suspend fun fetchMarketIngestionStatus(
        session: QuantSession,
        exchange: String
    ) = MarketIngestionStatus(
        exchange = exchange.uppercase(),
        status = "SUCCEEDED",
        provider = "tejhq_eod",
        tradingDate = "2026-11-20",
        rowsReceived = 3000,
        rowsSkipped = 500,
        rowsUpserted = 2500,
        lastSuccessfulTradingDate = "2026-11-20",
        lastSuccessfulProvider = "tejhq_eod",
        lastSuccessfulRows = 2500,
        lastSuccessfulAt = "2026-11-21T04:30:00Z"
    )
    override suspend fun fetchLatestEodMarketSnapshot(
        session: QuantSession,
        exchange: String
    ): EodMarketSnapshot = EodMarketSnapshot(
        provider = "tejhq_eod",
        dataMode = "END_OF_DAY",
        exchange = exchange.uppercase(),
        marketScope = "NSE_EQ",
        tradingDate = "2026-11-20",
        totalRows = 3000,
        equitySeriesRows = 2500,
        pricedSymbols = 2450,
        advances = 1200,
        declines = 1100,
        unchanged = 150,
        topGainers = listOf(EodStockMove("FIXTURE", "Fixture Industries", "EQ", 110.0, 100.0, 10.0, 10.0, 100000, null)),
        topLosers = listOf(EodStockMove("LOSER", "Loser Industries", "EQ", 90.0, 100.0, -10.0, -10.0, 200000, null)),
        mostActiveByVolume = listOf(EodStockMove("ACTIVE", "Active Industries", "EQ", 100.0, 99.0, 1.0, 1.01, 500000, null))
    )

    override suspend fun fetchIntelligenceEvents(
        session: QuantSession, query: String, timespan: String
    ) = IntelligenceFeed(
        source = "GDELT_DOC_API",
        retrievedAt = "2026-11-22T10:00:00Z",
        query = query,
        timespan = timespan,
        articleCount = 2,
        eventCount = 1,
        events = listOf(MarketEvent(
            eventId = "event-fixture-001",
            headline = "Oil supply disruption raises crude prices",
            eventCategory = "COMMODITY_SUPPLY",
            eventTags = listOf("COMMODITY_SUPPLY"),
            sourceArticleCount = 2,
            independentSourceCount = 2,
            sources = listOf(IntelligenceSource("fixture.example", "https://fixture.example/oil", "20261122T090000Z", "English", "India")),
            classificationMethod = "DETERMINISTIC_KEYWORD_RULES",
            classificationConfidence = 0.64,
            confidenceSemantics = "Heuristic rule-match score; not a calibrated probability.",
            sectorImpacts = listOf(SectorImpact("AVIATION", "POTENTIAL_NEGATIVE", "Fuel costs may pressure operating margins.")),
            stockMappingStatus = "NOT_RESOLVED",
            stockMappingNote = "Company and instrument mapping is not yet enabled.",
            impactStatus = "HYPOTHESIS_REQUIRES_COMPANY_LEVEL_VERIFICATION"
        )),
        limitations = listOf("Global news discovery is not primary-source verification.")
    )

    override suspend fun fetchEventStudy(
        session: QuantSession, symbol: String, eventDate: String, benchmarkSymbol: String?
    ) = EventStudyResult(
        exchange = "NSE", symbol = symbol, eventDate = eventDate, anchorDate = "2026-11-20",
        firstPostEventDate = "2026-11-23", lastPostEventDate = "2026-11-23",
        preEventReturn = 0.02, cumulativeReturn = 0.015,
        marketAdjustedReturn = null, cumulativeAbnormalReturn = null,
        benchmarkSymbol = benchmarkSymbol, benchmarkMatches = 0, postBarsUsed = 1,
        method = "UNADJUSTED_CLOSE_TO_CLOSE_EVENT_WINDOW",
        limitations = listOf("Deterministic test fixture; not live data.")
    )

    override suspend fun fetchMacroResearch(session: QuantSession) = MacroResearchSummary(
        retrievedAt = "2026-11-23T00:00:00Z",
        worldBankSeries = listOf(
            MacroSeriesObservation("gdp_growth", "GDP growth (annual %)", "ANNUAL", "PERCENT", "2024", 6.5, "WORLD_BANK_INDICATORS_V2"),
            MacroSeriesObservation("inflation", "Consumer price inflation (annual %)", "ANNUAL", "PERCENT", "2024", 4.9, "WORLD_BANK_INDICATORS_V2")
        ),
        amfiNavCount = 1,
        amfiExamples = listOf(AmfiNavObservation(119551, "Fixture Equity Fund", 123.45, "21-Nov-2026")),
        fredConfigured = false,
        fredSeriesCount = 0,
        providerErrors = emptyList()
    )

    override suspend fun sendAiMessage(
        session: QuantSession,
        providerId: String,
        model: String,
        messages: List<AiChatMessage>,
        credentialId: String?,
        fallbackProviders: List<String>
    ) = AiCompletion(providerId, credentialId, model, "Deterministic AI fixture response.", false)

    override suspend fun requestAiStrategyBrief(
        session: QuantSession,
        providerId: String,
        model: String,
        symbol: String,
        exchange: String,
        fromDate: String,
        toDate: String,
        strategyContext: String,
        credentialId: String?,
        fallbackProviders: List<String>
    ) = AiStrategyBrief(
        provider = "tejhq_eod",
        dataMode = "END_OF_DAY",
        exchange = exchange,
        symbol = symbol,
        firstTradingDate = fromDate,
        latestTradingDate = toDate,
        receivedAt = "2026-11-22T10:00:00Z",
        barCount = 60,
        evidence = AiStrategyEvidence(
            latestClose = 2500.0,
            sma20 = 2450.0,
            sma50 = 2400.0,
            rsi14 = 58.0,
            atr14 = 45.0,
            adx14 = 22.0,
            donchianState = "INSIDE_PRIOR_20_DAY_RANGE",
            relativeVolume20 = 1.1,
            return21 = 0.04,
            return63 = 0.09,
            trendContext = "CLOSE_ABOVE_SMA20_AND_SMA50"
        ),
        bars = listOf(AiDailyBar(toDate, 2480.0, 2520.0, 2470.0, 2500.0, 100000)),
        ai = AiCompletion(providerId, credentialId, model, "Deterministic EOD strategy fixture response.", false),
        dataRights = "Private personal research only.",
        limitations = listOf("EOD daily bars only.")
    )

    override suspend fun callNseMcpTool(
        session: QuantSession,
        server: String,
        tool: String,
        argumentsJson: String,
        confirmNonReadOnly: Boolean
    ) = NseMcpResult("test", "TEST", null, null, JSONObject().toString())

    override fun createMarketStream(
        session: QuantSession,
        onStatus: (String) -> Unit,
        onTicks: (List<MarketTick>) -> Unit
    ): MarketStream = object : MarketStream {
        override fun start(instrumentKeys: List<String>) {
            onStatus("Connected · deterministic UI test")
        }

        override fun stop() = Unit
    }
}
