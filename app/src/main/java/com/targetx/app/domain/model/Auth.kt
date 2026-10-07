package com.targetx.app.domain.model

data class AuthUser(
    val id: String,
    val email: String?,
)

sealed interface AuthState {
    data object Loading : AuthState
    data object Unauthenticated : AuthState
    data class Authenticated(val user: AuthUser) : AuthState
}

sealed interface AuthResult {
    data object Success : AuthResult
    data class ConfirmationRequired(val email: String) : AuthResult
    data class Failure(val error: AuthError) : AuthResult
}

sealed interface AuthError {
    data object NotConfigured : AuthError
    data object InvalidCredentials : AuthError
    data object EmailNotConfirmed : AuthError
    data object UserAlreadyExists : AuthError
    data object WeakPassword : AuthError
    data object RateLimited : AuthError
    data object Network : AuthError
    data class Unknown(val message: String) : AuthError
}
