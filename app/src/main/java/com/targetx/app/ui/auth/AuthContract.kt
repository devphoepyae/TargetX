package com.targetx.app.ui.auth

import com.targetx.app.domain.model.AuthError
import com.targetx.app.domain.validation.EmailError
import com.targetx.app.domain.validation.PasswordError

enum class AuthMode { LOGIN, SIGN_UP }

data class AuthUiState(
    val mode: AuthMode = AuthMode.LOGIN,
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val passwordVisible: Boolean = false,
    val isLoading: Boolean = false,
    val isConfigured: Boolean = true,
    val emailError: EmailError? = null,
    val passwordError: PasswordError? = null,
    val confirmPasswordError: PasswordError? = null,
    val error: AuthError? = null,
    val confirmationSentTo: String? = null,
)

sealed interface AuthUiEvent {
    data class ModeChanged(val mode: AuthMode) : AuthUiEvent
    data class EmailChanged(val value: String) : AuthUiEvent
    data class PasswordChanged(val value: String) : AuthUiEvent
    data class ConfirmPasswordChanged(val value: String) : AuthUiEvent
    data object TogglePasswordVisibility : AuthUiEvent
    data object Submit : AuthUiEvent
    data object DismissMessage : AuthUiEvent
}
