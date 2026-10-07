package com.targetx.app.domain.repository

import com.targetx.app.domain.model.AuthResult
import com.targetx.app.domain.model.AuthState
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    val isConfigured: Boolean
    fun observeAuthState(): Flow<AuthState>
    suspend fun signIn(email: String, password: String): AuthResult
    suspend fun signUp(email: String, password: String): AuthResult
    suspend fun signOut()
}
