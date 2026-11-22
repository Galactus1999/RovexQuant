package com.rovexquant.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.rovexquant.app.data.local.QuantSessionStore
import com.rovexquant.app.data.model.MarketIndexInstrument
import com.rovexquant.app.data.model.MarketTick
import com.rovexquant.app.data.model.NseMcpCatalog
import com.rovexquant.app.data.model.NseMcpResult
import com.rovexquant.app.data.model.QuantSession
import com.rovexquant.app.data.repository.MarketStream
import com.rovexquant.app.data.repository.QuantDataRepository
import com.rovexquant.app.feature.workspace.QuantWorkspaceViewModel
import org.json.JSONObject

class QuantTestActivity : MainActivity() {
    val fakeRepository = FakeQuantRepository()

    override fun createWorkspaceViewModelFactory(): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(QuantWorkspaceViewModel::class.java))
                return QuantWorkspaceViewModel(application, fakeRepository) as T
            }
        }
}

class FakeQuantRepository : QuantDataRepository {
    var rejectLogin = false
    private val testSession = QuantSession("https://quant.test", "test-session-token")

    override suspend fun loadSavedConnection() =
        QuantSessionStore.SavedConnection(testSession.baseUrl, "quant-test")

    override suspend fun loadSession(): QuantSession? = null
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
        return servers.map { NseMcpCatalog(it, emptyList(), "No tools in deterministic UI test fixture.") }
    }

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
