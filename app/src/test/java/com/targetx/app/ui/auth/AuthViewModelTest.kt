package com.targetx.app.ui.auth

import com.targetx.app.domain.model.AuthError
import com.targetx.app.domain.model.AuthResult
import com.targetx.app.domain.model.AuthState
import com.targetx.app.domain.repository.AuthRepository
import com.targetx.app.domain.usecase.SignInUseCase
import com.targetx.app.domain.usecase.SignUpUseCase
import com.targetx.app.domain.validation.CredentialsValidator
import com.targetx.app.domain.validation.EmailError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeAuthRepository()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = AuthViewModel(
        signIn = SignInUseCase(repository),
        signUp = SignUpUseCase(repository),
        validator = CredentialsValidator(),
        repository = repository,
    )

    @Test
    fun `invalid input shows field errors without calling repository`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(AuthUiEvent.EmailChanged("bad"))
        vm.onEvent(AuthUiEvent.Submit)
        advanceUntilIdle()

        assertEquals(EmailError.Invalid, vm.state.value.emailError)
        assertEquals(0, repository.signInCalls)
    }

    @Test
    fun `login trims email and surfaces repository failure`() = runTest(dispatcher) {
        repository.signInResult = AuthResult.Failure(AuthError.InvalidCredentials)
        val vm = viewModel()
        vm.onEvent(AuthUiEvent.EmailChanged("  rider@example.com "))
        vm.onEvent(AuthUiEvent.PasswordChanged("secret"))
        vm.onEvent(AuthUiEvent.Submit)
        assertEquals(true, vm.state.value.isLoading)
        advanceUntilIdle()

        assertEquals("rider@example.com", repository.lastEmail)
        assertFalse(vm.state.value.isLoading)
        assertEquals(AuthError.InvalidCredentials, vm.state.value.error)
    }

    @Test
    fun `sign up requiring confirmation switches back to login with notice`() = runTest(dispatcher) {
        repository.signUpResult = AuthResult.ConfirmationRequired("rider@example.com")
        val vm = viewModel()
        vm.onEvent(AuthUiEvent.ModeChanged(AuthMode.SIGN_UP))
        vm.onEvent(AuthUiEvent.EmailChanged("rider@example.com"))
        vm.onEvent(AuthUiEvent.PasswordChanged("secret1"))
        vm.onEvent(AuthUiEvent.ConfirmPasswordChanged("secret1"))
        vm.onEvent(AuthUiEvent.Submit)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(AuthMode.LOGIN, state.mode)
        assertEquals("rider@example.com", state.confirmationSentTo)
        assertEquals("", state.password)
        assertNull(state.error)
    }

    private class FakeAuthRepository : AuthRepository {
        var signInResult: AuthResult = AuthResult.Success
        var signUpResult: AuthResult = AuthResult.Success
        var signInCalls = 0
        var lastEmail: String? = null

        override val isConfigured = true
        override fun observeAuthState(): Flow<AuthState> = emptyFlow()

        override suspend fun signIn(email: String, password: String): AuthResult {
            signInCalls++
            lastEmail = email
            return signInResult
        }

        override suspend fun signUp(email: String, password: String): AuthResult {
            lastEmail = email
            return signUpResult
        }

        override suspend fun signOut() = Unit
    }
}
