package com.rovexquant.app.feature.intelligence

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rovexquant.app.data.model.IntelligenceFeed
import com.rovexquant.app.data.model.QuantSession
import com.rovexquant.app.data.repository.QuantDataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class IntelligenceCentreUiState(
    val loading: Boolean = false,
    val query: String = "India OR oil OR war OR technology OR central bank",
    val timespan: String = "7d",
    val feed: IntelligenceFeed? = null,
    val error: String? = null
)

/** Owns cancellable, latest-request-wins intelligence feed retrieval. */
class IntelligenceCentreViewModel(
    private val session: QuantSession,
    private val repository: QuantDataRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(IntelligenceCentreUiState(loading = true))
    val uiState = _uiState.asStateFlow()
    private var requestJob: Job? = null

    init {
        refresh()
    }

    fun refresh(query: String = _uiState.value.query, timespan: String = _uiState.value.timespan) {
        val normalized = query.trim()
        if (normalized.isEmpty() || normalized.length > 160 || timespan !in setOf("1d", "3d", "7d")) {
            _uiState.update { it.copy(error = "Enter a valid search query and choose 1d, 3d or 7d.") }
            return
        }
        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, query = normalized, timespan = timespan, error = null) }
            try {
                val feed = repository.fetchIntelligenceEvents(session, normalized, timespan)
                _uiState.update { it.copy(loading = false, feed = feed, error = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _uiState.update {
                    it.copy(loading = false, error = failure.message ?: "Market intelligence is unavailable.")
                }
            }
        }
    }
}
