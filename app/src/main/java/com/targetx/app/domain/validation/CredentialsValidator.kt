package com.targetx.app.domain.validation

import javax.inject.Inject

sealed interface EmailError {
    data object Blank : EmailError
    data object Invalid : EmailError
}

sealed interface PasswordError {
    data object Blank : PasswordError
    data class TooShort(val minLength: Int) : PasswordError
    data object Mismatch : PasswordError
}

data class CredentialsValidation(
    val emailError: EmailError? = null,
    val passwordError: PasswordError? = null,
    val confirmPasswordError: PasswordError? = null,
) {
    val isValid: Boolean get() = emailError == null && passwordError == null && confirmPasswordError == null
}

class CredentialsValidator @Inject constructor() {

    fun validateLogin(email: String, password: String) = CredentialsValidation(
        emailError = validateEmail(email),
        passwordError = if (password.isEmpty()) PasswordError.Blank else null,
    )

    fun validateSignUp(email: String, password: String, confirmPassword: String) = CredentialsValidation(
        emailError = validateEmail(email),
        passwordError = when {
            password.isEmpty() -> PasswordError.Blank
            password.length < MIN_PASSWORD_LENGTH -> PasswordError.TooShort(MIN_PASSWORD_LENGTH)
            else -> null
        },
        confirmPasswordError = if (confirmPassword != password) PasswordError.Mismatch else null,
    )

    private fun validateEmail(email: String): EmailError? = when {
        email.isBlank() -> EmailError.Blank
        !EMAIL_REGEX.matches(email.trim()) -> EmailError.Invalid
        else -> null
    }

    companion object {
        /** Matches Supabase's default minimum password length. */
        const val MIN_PASSWORD_LENGTH = 6
        private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}
