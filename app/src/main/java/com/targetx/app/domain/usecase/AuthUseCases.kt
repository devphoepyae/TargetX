package com.targetx.app.domain.usecase

import com.targetx.app.domain.model.AuthResult
import com.targetx.app.domain.model.AuthState
import com.targetx.app.domain.repository.AuthRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveAuthStateUseCase @Inject constructor(private val repository: AuthRepository) {
    operator fun invoke(): Flow<AuthState> = repository.observeAuthState()
}

class SignInUseCase @Inject constructor(private val repository: AuthRepository) {
    suspend operator fun invoke(email: String, password: String): AuthResult =
        repository.signIn(email.trim(), password)
}

class SignUpUseCase @Inject constructor(private val repository: AuthRepository) {
    suspend operator fun invoke(email: String, password: String): AuthResult =
        repository.signUp(email.trim(), password)
}

class SignOutUseCase @Inject constructor(private val repository: AuthRepository) {
    suspend operator fun invoke() = repository.signOut()
}
