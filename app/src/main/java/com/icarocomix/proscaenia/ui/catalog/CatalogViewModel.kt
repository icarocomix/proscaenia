package com.icarocomix.proscaenia.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.icarocomix.proscaenia.domain.model.Category
import com.icarocomix.proscaenia.domain.repository.CatalogRepository
import com.icarocomix.proscaenia.ui.common.AutoRetryTriggers
import com.icarocomix.proscaenia.ui.common.ErrorMessages
import com.icarocomix.proscaenia.ui.common.UiMessage
import com.icarocomix.proscaenia.ui.common.appContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface CatalogUiState {
    data object Loading : CatalogUiState

    /**
     * Durante un aggiornamento manuale continuo a mostrare il catalogo già caricato: un errore di
     * rete in quella fase non deve cancellare contenuti validi, quindi lo espongo a parte.
     */
    data class Content(
        val categories: List<Category>,
        val isRefreshing: Boolean = false,
        val refreshError: UiMessage? = null,
    ) : CatalogUiState

    data class Failure(val message: UiMessage, val retryable: Boolean) : CatalogUiState
}

class CatalogViewModel(
    private val catalogRepository: CatalogRepository,
    autoRetryTriggers: AutoRetryTriggers,
) : ViewModel() {

    private val _state = MutableStateFlow<CatalogUiState>(CatalogUiState.Loading)
    val state: StateFlow<CatalogUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        load(forceRefresh = false)
        // Un catalogo fermo sull'errore riparte da solo (rete tornata, app riaperta, ogni minuto).
        // Non tocco un catalogo già caricato: lì l'errore di aggiornamento è solo una notifica.
        viewModelScope.launch {
            autoRetryTriggers.events().collect {
                if ((_state.value as? CatalogUiState.Failure)?.retryable == true) retry()
            }
        }
    }

    fun refresh() = load(forceRefresh = true)

    fun retry() {
        _state.value = CatalogUiState.Loading
        load(forceRefresh = true)
    }

    fun dismissRefreshError() = _state.update {
        if (it is CatalogUiState.Content) it.copy(refreshError = null) else it
    }

    private fun load(forceRefresh: Boolean) {
        loadJob?.cancel()
        _state.update { if (it is CatalogUiState.Content) it.copy(isRefreshing = true, refreshError = null) else it }
        loadJob = viewModelScope.launch {
            try {
                val categories = catalogRepository.categories(forceRefresh)
                _state.value = CatalogUiState.Content(categories)
            } catch (e: IOException) {
                val message = ErrorMessages.from(e, "Caricamento del catalogo non riuscito")
                _state.update { previous ->
                    if (previous is CatalogUiState.Content) {
                        previous.copy(isRefreshing = false, refreshError = message)
                    } else {
                        CatalogUiState.Failure(message, ErrorMessages.isRetryable(e))
                    }
                }
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                CatalogViewModel(container.catalogRepository, container.autoRetryTriggers)
            }
        }
    }
}
