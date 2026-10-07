package com.targetx.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.targetx.app.domain.usecase.SignOutUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val signOut: SignOutUseCase,
) : ViewModel() {
    fun onSignOut() {
        viewModelScope.launch { signOut() }
    }
}
