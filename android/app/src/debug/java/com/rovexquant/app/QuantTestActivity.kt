package com.rovexquant.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.rovexquant.app.data.local.QuantSessionStore
import com.rovexquant.app.data.model.AiChatMessage
import com.rovexquant.app.data.model.AiCompletion
import com.rovexquant.app.data.model.AiDailyBar
import com.rovexquant.app.data.model.AiProvider
import com.rovexquant.app.data.model.AiStrategyBrief
import com.rovexquant.app.data.model.AiStrategyEvidence
import com.rovexquant.app.data.model.MarketIndexInstrument
import com.rovexquant.app.data.model.IntelligenceFeed
import com.rovexquant.app.data.model.IntelligenceSource
import com.rovexquant.app.data.model.MarketEvent
import com.rovexquant.app.data.model.SectorImpact
import com.rovexquant.app.data.model.MarketTick
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

    override suspend fun fetchNseMcpTools(session: QuantSession, server: String): List<NseMcpCatalog> {
        val servers = if (server == "both") listOf("bhavcopy", "cmmkt") else listOf(server)
        return servers.map {
            NseMcpCatalog(it, emptyList(), "No tools in deterministic UI test fixture.")
        }
    }

    override suspend fun fetchAiProviders(session: QuantSession) =
        listOf(AiProvider("groq", true, listOf("default")))

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

    override suspend fun sendAiMessage(
        session: QuantSession,
        providerId: String,
        model: String,
        messages: List<AiChatMessage>,
        credentialId: String?
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
        credentialId: String?
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
        argumentsJson: String
    ) = NseMcpResult("test", "TEST", null, null, JSONObject().toString())

    override fun createMarketStream(
        session: QuantSession,
        onStatus: (String) -> Unit,
        onTick: (MarketTick) -> Unit
    ): MarketStream = object : MarketStream {
        override fun start(instrumentKeys: List<String>) {
            onStatus("Connected · deterministic UI test")
        }

        override fun stop() = Unit
    }
}
