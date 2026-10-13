package com.icarocomix.proscaenia.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.icarocomix.proscaenia.data.local.PlaybackPositionStore
import com.icarocomix.proscaenia.domain.model.ShowDetail
import com.icarocomix.proscaenia.domain.policy.ResumePolicy
import com.icarocomix.proscaenia.domain.repository.CatalogRepository
import com.icarocomix.proscaenia.ui.common.AutoRetryTriggers
import com.icarocomix.proscaenia.ui.common.ErrorMessages
import com.icarocomix.proscaenia.ui.common.UiMessage
import com.icarocomix.proscaenia.ui.common.appContainer
import com.icarocomix.proscaenia.ui.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface DetailUiState {
    data object Loading : DetailUiState

    /** [resumePositionMs] vale 0 quando la riproduzione partirà dall'inizio. */
    data class Content(val show: ShowDetail, val resumePositionMs: Long) : DetailUiState

    data class Failure(val message: UiMessage, val retryable: Boolean) : DetailUiState
}

class DetailViewModel(
    savedStateHandle: SavedStateHandle,
    private val catalogRepository: CatalogRepository,
    private val positionStore: PlaybackPositionStore,
    autoRetryTriggers: AutoRetryTriggers,
) : ViewModel() {

    private val showId: String = checkNotNull(savedStateHandle[Routes.ARG_SHOW_ID]) {
        "Rotta di dettaglio senza identificativo dello spettacolo"
    }

    private val _state = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            autoRetryTriggers.events().collect {
                if ((_state.value as? DetailUiState.Failure)?.retryable == true) load()
            }
        }
    }

    fun load() {
        _state.value = DetailUiState.Loading
        viewModelScope.launch {
            try {
                val show = catalogRepository.show(showId)
                _state.value = DetailUiState.Content(show, resumePositionFor(show))
            } catch (e: IOException) {
                _state.value = DetailUiState.Failure(ErrorMessages.from(e, "Caricamento della scheda $showId non riuscito"), ErrorMessages.isRetryable(e))
            }
        }
    }

    /**
     * Ricalcolo il punto di ripresa ogni volta che la schermata torna visibile: tornando dal player la
     * posizione salvata è cambiata e l'etichetta "Riprendi da" deve riflettere l'ultimo arresto.
     */
    fun refreshResumePoint() = _state.update { current ->
        if (current is DetailUiState.Content) current.copy(resumePositionMs = resumePositionFor(current.show)) else current
    }

    private fun resumePositionFor(show: ShowDetail): Long =
        ResumePolicy.resumePositionMs(positionStore.read(show.id), show.durationSeconds?.times(1_000))

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                DetailViewModel(createSavedStateHandle(), container.catalogRepository, container.positionStore, container.autoRetryTriggers)
            }
        }
    }
}
