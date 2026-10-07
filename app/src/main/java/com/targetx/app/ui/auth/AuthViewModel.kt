package com.targetx.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.targetx.app.domain.model.AuthResult
import com.targetx.app.domain.repository.AuthRepository
import com.targetx.app.domain.usecase.SignInUseCase
import com.targetx.app.domain.usecase.SignUpUseCase
import com.targetx.app.domain.validation.CredentialsValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Successful login needs no navigation effect: the Supabase session status flips to
 * Authenticated and [com.targetx.app.ui.root.RootViewModel] swaps in the main flow.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val signIn: SignInUseCase,
    private val signUp: SignUpUseCase,
    private val validator: CredentialsValidator,
    repository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState(isConfigured = repository.isConfigured))
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun onEvent(event: AuthUiEvent) {
        when (event) {
            is AuthUiEvent.ModeChanged -> _state.update {
                it.copy(
                    mode = event.mode,
                    confirmPassword = "",
                    emailError = null,
                    passwordError = null,
                    confirmPasswordError = null,
                    error = null,
                )
            }
            is AuthUiEvent.EmailChanged -> _state.update { it.copy(email = event.value, emailError = null, error = null) }
            is AuthUiEvent.PasswordChanged -> _state.update { it.copy(password = event.value, passwordError = null, error = null) }
            is AuthUiEvent.ConfirmPasswordChanged -> _state.update {
                it.copy(confirmPassword = event.value, confirmPasswordError = null, error = null)
            }
            AuthUiEvent.TogglePasswordVisibility -> _state.update { it.copy(passwordVisible = !it.passwordVisible) }
            AuthUiEvent.DismissMessage -> _state.update { it.copy(error = null, confirmationSentTo = null) }
            AuthUiEvent.Submit -> submit()
        }
    }

    private fun submit() {
        val current = _state.value
        if (current.isLoading) return

        val validation = when (current.mode) {
            AuthMode.LOGIN -> validator.validateLogin(current.email, current.password)
            AuthMode.SIGN_UP -> validator.validateSignUp(current.email, current.password, current.confirmPassword)
        }
        if (!validation.isValid) {
            _state.update {
                it.copy(
                    emailError = validation.emailError,
                    passwordError = validation.passwordError,
                    confirmPasswordError = validation.confirmPasswordError,
                )
            }
            return
        }

        _state.update { it.copy(isLoading = true, error = null, confirmationSentTo = null) }
        viewModelScope.launch {
            val result = when (current.mode) {
                AuthMode.LOGIN -> signIn(current.email, current.password)
                AuthMode.SIGN_UP -> signUp(current.email, current.password)
            }
            _state.update {
                when (result) {
                    AuthResult.Success -> it.copy(isLoading = false)
                    is AuthResult.ConfirmationRequired -> it.copy(
                        isLoading = false,
                        mode = AuthMode.LOGIN,
                        password = "",
                        confirmPassword = "",
                        confirmationSentTo = result.email,
                    )
                    is AuthResult.Failure -> it.copy(isLoading = false, error = result.error)
                }
            }
        }
    }
}
