package com.icarocomix.proscaenia.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.icarocomix.proscaenia.R
import com.icarocomix.proscaenia.domain.error.InvalidCredentialsException
import com.icarocomix.proscaenia.domain.repository.AuthRepository
import com.icarocomix.proscaenia.ui.common.ErrorMessages
import com.icarocomix.proscaenia.ui.common.UiMessage
import com.icarocomix.proscaenia.ui.common.appContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val error: UiMessage? = null,
    val isSignedIn: Boolean = false,
) {
    val canSubmit: Boolean get() = username.isNotBlank() && password.isNotEmpty() && !isSubmitting
}

class LoginViewModel(private val authRepository: AuthRepository) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, error = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    /**
     * Ignoro gli invii mentre uno è già in corso, perché il tasto "Invio" della tastiera e il
     * pulsante possono scattare quasi insieme e due login paralleli si invaliderebbero a vicenda.
     */
    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        _state.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            try {
                authRepository.signIn(current.username, current.password)
                _state.update { it.copy(isSubmitting = false, isSignedIn = true) }
            } catch (e: IOException) {
                val message = ErrorMessages.from(e, "Login non riuscito")
                // Al primo avvio un rifiuto significa davvero username o password errati.
                val shown = if (e is InvalidCredentialsException) UiMessage(R.string.error_invalid_credentials) else message
                _state.update { it.copy(isSubmitting = false, error = shown) }
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { LoginViewModel(appContainer().authRepository) }
        }
    }
}
