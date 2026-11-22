package com.rovexquant.app.data.repository

import com.rovexquant.app.data.local.QuantSessionStore
import com.rovexquant.app.data.model.MarketIndexInstrument
import com.rovexquant.app.data.model.MarketTick
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
    suspend fun fetchNseMcpTools(session: QuantSession, server: String): List<NseMcpCatalog>
    suspend fun callNseMcpTool(
        session: QuantSession,
        server: String,
        tool: String,
        argumentsJson: String
    ): NseMcpResult
    fun createMarketStream(
        session: QuantSession,
        onStatus: (String) -> Unit,
        onTick: (MarketTick) -> Unit
    ): MarketStream
}
