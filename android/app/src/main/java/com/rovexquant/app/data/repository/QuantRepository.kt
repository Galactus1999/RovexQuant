package com.rovexquant.app.data.repository

import android.content.Context
import com.rovexquant.app.data.local.QuantSessionStore
import com.rovexquant.app.data.repository.MarketStream
import com.rovexquant.app.data.repository.QuantDataRepository
import com.rovexquant.app.data.model.MarketIndexInstrument
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

    override suspend fun fetchNseMcpTools(session: QuantSession, server: String): List<NseMcpCatalog> =
        withContext(Dispatchers.IO) { QuantApi.fetchNseMcpTools(session, server) }

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
