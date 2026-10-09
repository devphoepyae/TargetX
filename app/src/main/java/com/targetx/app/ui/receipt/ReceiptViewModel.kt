package com.targetx.app.ui.receipt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.targetx.app.data.receipt.ReceiptRepository
import com.targetx.app.domain.model.Receipt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ReceiptUiState {
    object Idle : ReceiptUiState
    object Loading : ReceiptUiState
    data class Ready(val receipt: Receipt) : ReceiptUiState
    data class Error(val message: String) : ReceiptUiState
}

@HiltViewModel
class ReceiptViewModel @Inject constructor(private val repository: ReceiptRepository) : ViewModel() {
    private val _uiState = MutableStateFlow<ReceiptUiState>(ReceiptUiState.Idle)
    val uiState: StateFlow<ReceiptUiState> = _uiState.asStateFlow()

    fun processBase64Image(base64: String) {
        viewModelScope.launch {
            _uiState.value = ReceiptUiState.Loading
            val result = repository.processReceipt(base64)
            result.fold(
                onSuccess = { receipt -> _uiState.value = ReceiptUiState.Ready(receipt) },
                onFailure = { err -> _uiState.value = ReceiptUiState.Error(err.message ?: "Unknown error") }
            )
        }
    }

    fun setEditedReceipt(receipt: Receipt) {
        _uiState.value = ReceiptUiState.Ready(receipt)
    }
}
