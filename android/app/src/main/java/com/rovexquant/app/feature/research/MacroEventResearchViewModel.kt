package com.rovexquant.app.feature.research

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rovexquant.app.data.model.EventStudyResult
import com.rovexquant.app.data.model.MacroResearchSummary
import com.rovexquant.app.data.model.QuantSession
import com.rovexquant.app.data.repository.QuantDataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MacroEventResearchUiState(
    val macroLoading: Boolean = false,
    val macro: MacroResearchSummary? = null,
    val macroError: String? = null,
    val symbol: String = "RELIANCE",
    val eventDate: String = "",
    val benchmarkSymbol: String = "NIFTY 50",
    val eventLoading: Boolean = false,
    val eventStudy: EventStudyResult? = null,
    val eventError: String? = null
)

/** Owns independently cancellable macro and event-study requests. */
class MacroEventResearchViewModel(
    private val session: QuantSession,
    private val repository: QuantDataRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(MacroEventResearchUiState())
    val uiState = _uiState.asStateFlow()
    private var macroJob: Job? = null
    private var eventJob: Job? = null

    init {
        refreshMacro()
    }

    fun setSymbol(value: String) {
        _uiState.update { it.copy(symbol = value.take(40), eventError = null) }
    }

    fun setEventDate(value: String) {
        _uiState.update { it.copy(eventDate = value.take(10), eventError = null) }
    }

    fun setBenchmarkSymbol(value: String) {
        _uiState.update { it.copy(benchmarkSymbol = value.take(40), eventError = null) }
    }

    fun refreshMacro() {
        macroJob?.cancel()
        macroJob = viewModelScope.launch {
            _uiState.update { it.copy(macroLoading = true, macroError = null) }
            try {
                val result = repository.fetchMacroResearch(session)
                _uiState.update { it.copy(macroLoading = false, macro = result, macroError = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _uiState.update {
                    it.copy(macroLoading = false, macroError = failure.message ?: "Macro providers are unavailable.")
                }
            }
        }
    }

    fun runEventStudy() {
        val current = _uiState.value
        val symbol = current.symbol.trim().uppercase()
        val eventDate = current.eventDate.trim()
        val benchmark = current.benchmarkSymbol.trim().takeIf { it.isNotEmpty() }
        if (!symbol.matches(Regex("[A-Z0-9&._-]{1,40}"))) {
            _uiState.update { it.copy(eventError = "Enter a valid NSE trading symbol.") }
            return
        }
        if (!eventDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) ||
            runCatching { java.time.LocalDate.parse(eventDate) }.isFailure
        ) {
            _uiState.update { it.copy(eventError = "Enter a real event date in YYYY-MM-DD format.") }
            return
        }
        eventJob?.cancel()
        eventJob = viewModelScope.launch {
            _uiState.update { it.copy(eventLoading = true, eventError = null) }
            try {
                val result = repository.fetchEventStudy(session, symbol, eventDate, benchmark)
                _uiState.update { it.copy(eventLoading = false, eventStudy = result, eventError = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _uiState.update {
                    it.copy(eventLoading = false, eventError = failure.message ?: "Event study is unavailable.")
                }
            }
        }
    }
}
