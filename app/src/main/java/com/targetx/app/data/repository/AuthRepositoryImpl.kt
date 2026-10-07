package com.targetx.app.data.repository

import com.targetx.app.data.SupabaseConfig
import com.targetx.app.domain.model.AuthError
import com.targetx.app.domain.model.AuthResult
import com.targetx.app.domain.model.AuthState
import com.targetx.app.domain.model.AuthUser
import com.targetx.app.domain.repository.AuthRepository
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject

class AuthRepositoryImpl @Inject constructor(
    private val auth: Auth,
    private val config: SupabaseConfig,
) : AuthRepository {

    override val isConfigured: Boolean get() = config.isConfigured

    override fun observeAuthState(): Flow<AuthState> {
        if (!config.isConfigured) return flowOf(AuthState.Unauthenticated)
        return auth.sessionStatus.map { status ->
            when (status) {
                is SessionStatus.Authenticated ->
                    status.session.user?.toDomain()?.let(AuthState::Authenticated)
                        ?: AuthState.Unauthenticated
                is SessionStatus.Initializing -> AuthState.Loading
                is SessionStatus.NotAuthenticated,
                is SessionStatus.RefreshFailure -> AuthState.Unauthenticated
            }
        }
    }

    override suspend fun signIn(email: String, password: String): AuthResult = runAuth {
        auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
        AuthResult.Success
    }

    override suspend fun signUp(email: String, password: String): AuthResult = runAuth {
        auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
        // When email confirmation is enabled in Supabase, sign-up returns no session.
        if (auth.currentSessionOrNull() != null) AuthResult.Success
        else AuthResult.ConfirmationRequired(email)
    }

    override suspend fun signOut() {
        runCatching { auth.signOut() }
            .onFailure { if (it is CancellationException) throw it }
    }

    private suspend fun runAuth(block: suspend () -> AuthResult): AuthResult {
        if (!config.isConfigured) return AuthResult.Failure(AuthError.NotConfigured)
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RestException) {
            AuthResult.Failure(mapRestError(e))
        } catch (e: HttpRequestException) {
            AuthResult.Failure(AuthError.Network)
        } catch (e: HttpRequestTimeoutException) {
            AuthResult.Failure(AuthError.Network)
        } catch (e: IOException) {
            AuthResult.Failure(AuthError.Network)
        } catch (e: Exception) {
            AuthResult.Failure(AuthError.Unknown(e.message ?: e::class.simpleName.orEmpty()))
        }
    }

    private fun mapRestError(e: RestException): AuthError {
        val code = e.error.lowercase()
        val description = e.description.orEmpty().lowercase()
        return when {
            code == "invalid_credentials" || "invalid login credentials" in description ->
                AuthError.InvalidCredentials
            code == "email_not_confirmed" || "email not confirmed" in description ->
                AuthError.EmailNotConfirmed
            code == "user_already_exists" || code == "email_exists" || "already registered" in description ->
                AuthError.UserAlreadyExists
            code == "weak_password" -> AuthError.WeakPassword
            code.startsWith("over_") && code.endsWith("rate_limit") -> AuthError.RateLimited
            else -> AuthError.Unknown(e.description ?: e.error)
        }
    }

    private fun UserInfo.toDomain() = AuthUser(id = id, email = email)
}
