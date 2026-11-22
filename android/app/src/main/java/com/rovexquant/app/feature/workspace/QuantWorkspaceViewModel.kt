package com.rovexquant.app.feature.workspace

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rovexquant.app.data.local.QuantSessionStore
import com.rovexquant.app.data.model.MarketIndexInstrument
import com.rovexquant.app.data.model.EodMarketSnapshot
import com.rovexquant.app.data.model.MarketEquityInstrument
import com.rovexquant.app.data.model.MarketTick
import com.rovexquant.app.data.model.MarketIngestionStatus
import com.rovexquant.app.data.model.QuantSession
import com.rovexquant.app.data.repository.MarketStream
import com.rovexquant.app.data.repository.QuantDataRepository
import com.rovexquant.app.data.repository.QuantRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class QuantWorkspaceUiState(
    val restoringSession: Boolean = true,
    val session: QuantSession? = null,
    val savedConnection: QuantSessionStore.SavedConnection = QuantSessionStore.SavedConnection("", ""),
    val loginLoading: Boolean = false,
    val loginError: String? = null,
    val ticks: Map<String, MarketTick> = emptyMap(),
    val indexKeys: Map<String, String> = emptyMap(),
    val equities: List<MarketEquityInstrument> = emptyList(),
    val streamStatus: String = "Not connected",
    val eodSnapshot: EodMarketSnapshot? = null,
    val eodSnapshotStatus: String = "EOD snapshot not loaded",
    val marketIngestionStatus: MarketIngestionStatus? = null,
    val marketIngestionStatusLabel: String = "Persistent database status not checked"
)

/** Owns session, stream lifecycle and workspace state across Compose recompositions. */
class QuantWorkspaceViewModel(
    application: Application,
    val repository: QuantDataRepository
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, QuantRepository(application))

    private val _uiState = MutableStateFlow(QuantWorkspaceUiState())
    val uiState = _uiState.asStateFlow()
    private var marketStream: MarketStream? = null
    private var marketJob: Job? = null
    private var loginJob: Job? = null
    private var eodSnapshotJob: Job? = null
    private var ingestionStatusJob: Job? = null
    private val authMutex = Mutex()

    init {
        restoreSession()
    }

    fun login(baseUrl: String, username: String, password: String) {
        if (_uiState.value.loginLoading) return
        loginJob = viewModelScope.launch {
            _uiState.update { it.copy(loginLoading = true, loginError = null) }
            try {
                authMutex.withLock {
                    val session = repository.login(baseUrl, username, password)
                    repository.saveSession(session, username)
                    _uiState.update {
                        it.copy(
                            session = session,
                            savedConnection = QuantSessionStore.SavedConnection(session.baseUrl, username.trim()),
                            loginError = null
                        )
                    }
                    startMarketStream(session)
                    refreshEodSnapshot(session)
                    refreshMarketIngestionStatus(session)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _uiState.update {
                    it.copy(loginError = failure.message ?: "Unable to sign in securely.")
                }
            } finally {
                _uiState.update { it.copy(loginLoading = false) }
            }
        }
    }

    fun signOut() {
        loginJob?.cancel()
        loginJob = null
        eodSnapshotJob?.cancel()
        eodSnapshotJob = null
        ingestionStatusJob?.cancel()
        ingestionStatusJob = null
        stopMarketStream()
        viewModelScope.launch { authMutex.withLock { repository.clearSession() } }
        _uiState.update {
            it.copy(
                session = null,
                ticks = emptyMap(),
                indexKeys = emptyMap(),
                equities = emptyList(),
                streamStatus = "Not connected",
                eodSnapshot = null,
                eodSnapshotStatus = "EOD snapshot not loaded",
                marketIngestionStatus = null,
                marketIngestionStatusLabel = "Persistent database status not checked",
                loginError = null
            )
        }
    }

    private fun restoreSession() {
        viewModelScope.launch {
            var savedConnection = QuantSessionStore.SavedConnection("", "")
            try {
                savedConnection = repository.loadSavedConnection()
                val stored = repository.loadSession()
                if (stored != null) {
                    try {
                        if (repository.validateSession(stored)) {
                            _uiState.update { it.copy(session = stored) }
                            startMarketStream(stored)
                            refreshEodSnapshot(stored)
                            refreshMarketIngestionStatus(stored)
                        } else {
                            repository.clearSession()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Preserve the encrypted token during temporary network/server failures.
                        // The user can retry login; the next launch will validate it again.
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A local keystore/storage failure must not crash the workspace.
            } finally {
                _uiState.update {
                    it.copy(restoringSession = false, savedConnection = savedConnection)
                }
            }
        }
    }

    fun reconnectMarketStream() {
        val session = _uiState.value.session ?: return
        startMarketStream(session)
    }

    fun refreshEodSnapshot() {
        val session = _uiState.value.session ?: return
        refreshEodSnapshot(session)
        refreshMarketIngestionStatus(session)
    }

    fun refreshMarketIngestionStatus() {
        val session = _uiState.value.session ?: return
        refreshMarketIngestionStatus(session)
    }

    private fun refreshMarketIngestionStatus(session: QuantSession) {
        ingestionStatusJob?.cancel()
        _uiState.update {
            it.copy(marketIngestionStatusLabel = "Checking PostgreSQL ingestion status…")
        }
        ingestionStatusJob = viewModelScope.launch {
            try {
                val status = repository.fetchMarketIngestionStatus(session, "nse")
                val label = when (status.status) {
                    "SUCCEEDED" -> "Latest collection completed"
                    "FAILED" -> "Latest collection failed"
                    "RUNNING" -> "Collection currently running"
                    "EMPTY" -> "Collection returned no usable rows"
                    "NOT_RUN" -> "No collection recorded yet"
                    else -> "Ingestion status: ${status.status}"
                }
                _uiState.update {
                    it.copy(marketIngestionStatus = status, marketIngestionStatusLabel = label)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _uiState.update {
                    it.copy(
                        marketIngestionStatusLabel = failure.message
                            ?: "Persistent ingestion status unavailable"
                    )
                }
            }
        }
    }

    private fun refreshEodSnapshot(session: QuantSession) {
        eodSnapshotJob?.cancel()
        _uiState.update { it.copy(eodSnapshotStatus = "Loading latest NSE EOD snapshot…") }
        eodSnapshotJob = viewModelScope.launch {
            try {
                val snapshot = repository.fetchLatestEodMarketSnapshot(session, "nse")
                _uiState.update {
                    it.copy(
                        eodSnapshot = snapshot,
                        eodSnapshotStatus = "EOD snapshot ready · ${snapshot.tradingDate}"
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _uiState.update {
                    it.copy(
                        eodSnapshotStatus = failure.message ?: "Latest NSE EOD snapshot unavailable; showing the last successful snapshot if available"
                    )
                }
            }
        }
    }

    private fun startMarketStream(session: QuantSession) {
        stopMarketStream()
        val controller = repository.createMarketStream(
            session = session,
            onStatus = { status -> _uiState.update { it.copy(streamStatus = status) } },
            onTicks = { ticks ->
                _uiState.update { current ->
                    current.copy(ticks = current.ticks + ticks.associateBy { it.instrumentKey })
                }
            }
        )
        marketStream = controller
        _uiState.update { it.copy(streamStatus = "Loading official NSE index and equity masters") }

        marketJob = viewModelScope.launch {
            try {
                val indexMaster = repository.fetchIndexInstruments(session)
                val resolvedIndices = resolveDashboardIndices(indexMaster)
                if (resolvedIndices.isEmpty()) {
                    throw IllegalStateException("Official NSE index master contained no dashboard benchmark matches.")
                }

                var equityMasterFailure: String? = null
                val equities = try {
                    repository.fetchEquityInstruments(session)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    equityMasterFailure = failure.message ?: "NSE equity master unavailable"
                    emptyList()
                }

                val subscriptions = (resolvedIndices.values + equities.map { it.instrumentKey }).distinct()
                if (subscriptions.size > 5000) {
                    throw IllegalStateException(
                        "NSE instrument universe (${subscriptions.size}) exceeds Upstox's 5,000 LTPC subscription limit."
                    )
                }
                _uiState.update {
                    it.copy(
                        indexKeys = resolvedIndices,
                        equities = equities,
                        streamStatus = equityMasterFailure?.let {
                            "NSE equity master unavailable: $it · index-only feed"
                        } ?: "Connecting · ${equities.size} equities + ${resolvedIndices.size} indices"
                    )
                }
                controller.start(subscriptions)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _uiState.update {
                    it.copy(streamStatus = failure.message ?: "Unable to load official NSE instrument master")
                }
            }
        }
    }

    private fun resolveDashboardIndices(master: List<MarketIndexInstrument>): Map<String, String> {
        val aliases = mapOf(
            "NIFTY 50" to listOf("NIFTY 50"),
            "NIFTY BANK" to listOf("NIFTY BANK"),
            "NIFTY FINANCIAL SERVICES" to listOf("NIFTY FINANCIAL SERVICES", "NIFTY FIN SERVICE"),
            "NIFTY IT" to listOf("NIFTY IT"),
            "NIFTY AUTO" to listOf("NIFTY AUTO"),
            "NIFTY PHARMA" to listOf("NIFTY PHARMA"),
            "NIFTY HEALTHCARE" to listOf("NIFTY HEALTHCARE"),
            "NIFTY FMCG" to listOf("NIFTY FMCG"),
            "NIFTY METAL" to listOf("NIFTY METAL"),
            "NIFTY OIL & GAS" to listOf("NIFTY OIL & GAS", "NIFTY OIL AND GAS"),
            "NIFTY REALTY" to listOf("NIFTY REALTY"),
            "NIFTY POWER" to listOf("NIFTY POWER"),
            "NIFTY MEDIA" to listOf("NIFTY MEDIA"),
            "NIFTY PSU BANK" to listOf("NIFTY PSU BANK"),
            "NIFTY PRIVATE BANK" to listOf("NIFTY PRIVATE BANK"),
            "NIFTY CONSUMER DURABLES" to listOf("NIFTY CONSUMER DURABLES"),
            "NIFTY CAPITAL GOODS" to listOf("NIFTY CAPITAL GOODS"),
            "NIFTY CHEMICALS" to listOf("NIFTY CHEMICALS"),
            "NIFTY CEMENT" to listOf("NIFTY CEMENT"),
            "NIFTY TELECOMMUNICATIONS" to listOf("NIFTY TELECOMMUNICATIONS", "NIFTY TELECOM")
        )
        fun normalize(value: String) = value.uppercase().filter { it.isLetterOrDigit() }

        return buildMap {
            aliases.forEach { (dashboardName, names) ->
                val candidates = names.map(::normalize).toSet()
                val match = master.firstOrNull { instrument ->
                    normalize(instrument.name) in candidates ||
                        normalize(instrument.tradingSymbol) in candidates
                }
                if (match != null) put(dashboardName, match.instrumentKey)
            }
        }
    }

    private fun stopMarketStream() {
        marketJob?.cancel()
        marketJob = null
        marketStream?.stop()
        marketStream = null
    }

    override fun onCleared() {
        stopMarketStream()
        super.onCleared()
    }
}
