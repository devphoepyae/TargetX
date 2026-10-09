package com.targetx.app.data.remote

import kotlinx.serialization.Serializable

@Serializable
data class ReceiptItemDto(
    val description: String? = null,
    val quantity: Double? = null,
    val unit_price: Double? = null,
    val total_price: Double? = null
)

@Serializable
data class ReceiptDto(
    val merchant_name: String? = null,
    val date: String? = null,
    val total_amount: Double? = null,
    val currency: String? = null,
    val category: String? = null,
    val items: List<ReceiptItemDto> = emptyList()
)
