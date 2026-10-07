package com.targetx.app.domain.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialsValidatorTest {

    private val validator = CredentialsValidator()

    @Test
    fun `valid login passes`() {
        assertTrue(validator.validateLogin("rider@example.com", "secret").isValid)
    }

    @Test
    fun `blank and malformed emails are rejected`() {
        assertEquals(EmailError.Blank, validator.validateLogin("  ", "secret").emailError)
        assertEquals(EmailError.Invalid, validator.validateLogin("rider@", "secret").emailError)
        assertEquals(EmailError.Invalid, validator.validateLogin("rider example.com", "secret").emailError)
    }

    @Test
    fun `login only requires a non-empty password`() {
        assertEquals(PasswordError.Blank, validator.validateLogin("rider@example.com", "").passwordError)
        assertNull(validator.validateLogin("rider@example.com", "1").passwordError)
    }

    @Test
    fun `sign up enforces minimum length and matching confirmation`() {
        val short = validator.validateSignUp("rider@example.com", "12345", "12345")
        assertEquals(PasswordError.TooShort(CredentialsValidator.MIN_PASSWORD_LENGTH), short.passwordError)

        val mismatch = validator.validateSignUp("rider@example.com", "secret1", "secret2")
        assertEquals(PasswordError.Mismatch, mismatch.confirmPasswordError)

        assertTrue(validator.validateSignUp("rider@example.com", "secret1", "secret1").isValid)
    }
}
