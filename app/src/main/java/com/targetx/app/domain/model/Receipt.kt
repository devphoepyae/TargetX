package com.targetx.app.domain.model

data class ReceiptItem(
    val description: String = "",
    val quantity: Double? = null,
    val unitPrice: Double? = null,
    val totalPrice: Double? = null
)

data class Receipt(
    val merchantName: String = "",
    val date: String? = null, // YYYY-MM-DD
    val totalAmount: Double? = null,
    val currency: String? = null,
    val category: String? = null,
    val items: List<ReceiptItem> = emptyList()
)
