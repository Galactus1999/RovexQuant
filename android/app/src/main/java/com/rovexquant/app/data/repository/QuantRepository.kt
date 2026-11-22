package com.rovexquant.app.data.repository

import android.content.Context
import com.rovexquant.app.data.local.QuantSessionStore
import com.rovexquant.app.data.model.AiChatMessage
import com.rovexquant.app.data.model.AiCompletion
import com.rovexquant.app.data.model.AiProvider
import com.rovexquant.app.data.model.AiStrategyBrief
import com.rovexquant.app.data.model.IntelligenceFeed
import com.rovexquant.app.data.model.MarketIndexInstrument
import com.rovexquant.app.data.model.MarketEquityInstrument
import com.rovexquant.app.data.model.MarketTick
import com.rovexquant.app.data.model.NseMcpCatalog
import com.rovexquant.app.data.model.NseMcpResult
import com.rovexquant.app.data.model.QuantSession
import com.rovexquant.app.data.remote.MarketStreamController
import com.rovexquant.app.data.remote.QuantApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Coordinates remote and local data sources; presentation code never calls transport directly. */
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

    override suspend fun fetchNseMcpTools(session: QuantSession, server: String): List<NseMcpCatalog> =
        withContext(Dispatchers.IO) { QuantApi.fetchNseMcpTools(session, server) }

    override suspend fun fetchAiProviders(session: QuantSession): List<AiProvider> =
        withContext(Dispatchers.IO) { QuantApi.fetchAiProviders(session) }

    override suspend fun fetchIntelligenceEvents(
        session: QuantSession, query: String, timespan: String
    ): IntelligenceFeed = withContext(Dispatchers.IO) {
        QuantApi.fetchIntelligenceEvents(session, query, timespan)
    }

    override suspend fun sendAiMessage(
        session: QuantSession,
        providerId: String,
        model: String,
        messages: List<AiChatMessage>,
        credentialId: String?
    ): AiCompletion = withContext(Dispatchers.IO) {
        QuantApi.sendAiMessage(session, providerId, model, messages, credentialId)
    }

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
    ): AiStrategyBrief = withContext(Dispatchers.IO) {
        QuantApi.requestAiStrategyBrief(
            session, providerId, model, symbol, exchange, fromDate, toDate, strategyContext, credentialId
        )
    }

    override suspend fun callNseMcpTool(
        session: QuantSession,
        server: String,
        tool: String,
        argumentsJson: String
    ): NseMcpResult = withContext(Dispatchers.IO) {
        QuantApi.callNseMcpTool(session, server, tool, argumentsJson)
    }

    override fun createMarketStream(
        session: QuantSession,
        onStatus: (String) -> Unit,
        onTick: (MarketTick) -> Unit
    ): MarketStream = MarketStreamController(session, onStatus, onTick)
}
