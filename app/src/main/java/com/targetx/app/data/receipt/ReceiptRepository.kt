package com.targetx.app.data.receipt

import com.targetx.app.data.remote.ReceiptDto
import com.targetx.app.data.remote.ReceiptItemDto
import com.targetx.app.domain.model.Receipt
import com.targetx.app.domain.model.ReceiptItem
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import javax.inject.Inject

class ReceiptRepository @Inject constructor(
    private val httpClient: HttpClient,
    private val edgeFunctionUrl: String,
    private val bearerToken: String?,
    private val apiKey: String?
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun processReceipt(base64Image: String, mimeType: String = "image/jpeg"): Result<Receipt> =
        withContext(Dispatchers.IO) {
            try {
                val payload = mapOf(
                    "image" to base64Image,
                    "mime_type" to mimeType
                )

                val response: HttpResponse = httpClient.post(edgeFunctionUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                    bearerToken?.let { header("Authorization", "Bearer $it") }
                    apiKey?.let { header("apikey", it) }
                }

                val bodyText = response.bodyAsText()
                if (!response.status.isSuccess()) {
                    return@withContext Result.failure(Exception("HTTP ${response.status.value}: $bodyText"))
                }

                // Parse into DTO then map to domain model
                val dto = json.decodeFromString<ReceiptDto>(bodyText)
                val receipt = dto.toDomain()
                Result.success(receipt)
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
}

private fun ReceiptDto.toDomain(): Receipt {
    return Receipt(
        merchantName = this.merchant_name.orEmpty(),
        date = this.date,
        totalAmount = this.total_amount,
        currency = this.currency,
        category = this.category,
        items = this.items.map { it.toDomain() }
    )
}

private fun ReceiptItemDto.toDomain(): ReceiptItem {
    return ReceiptItem(
        description = this.description.orEmpty(),
        quantity = this.quantity,
        unitPrice = this.unit_price,
        totalPrice = this.total_price
    )
}
