package com.targetx.app.ui.receipt

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.targetx.app.domain.model.Receipt
import com.targetx.app.domain.model.ReceiptItem

@Composable
fun ReceiptReviewScreen(
    initial: Receipt,
    onSave: (Receipt) -> Unit,
    onCancel: () -> Unit
) {
    var merchantName by remember { mutableStateOf(initial.merchantName) }
    var date by remember { mutableStateOf(initial.date ?: "") }
    var total by remember { mutableStateOf(initial.totalAmount?.toString() ?: "") }
    var currency by remember { mutableStateOf(initial.currency ?: "") }
    var category by remember { mutableStateOf(initial.category ?: "") }
    var items by remember { mutableStateOf(initial.items.toMutableStateList()) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(value = merchantName, onValueChange = { merchantName = it }, label = { Text("Merchant") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(value = date, onValueChange = { date = it }, label = { Text("Date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(value = total, onValueChange = { total = it }, label = { Text("Total Amount") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(value = currency, onValueChange = { currency = it }, label = { Text("Currency") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Category") }, modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(12.dp))
        Text("Items", modifier = Modifier.padding(4.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(items) { idx, item ->
                Row(modifier = Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(value = item.description, onValueChange = { items[idx] = item.copy(description = it) }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = item.unitPrice?.toString() ?: "", onValueChange = { items[idx] = item.copy(unitPrice = it.toDoubleOrNull()) }, label = { Text("Unit Price") }, modifier = Modifier.fillMaxWidth())
                    }
                    IconButton(onClick = { items.removeAt(idx) }) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Remove")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onCancel) { Text("Cancel") }
            Button(onClick = {
                val receipt = Receipt(
                    merchantName = merchantName,
                    date = if (date.isBlank()) null else date,
                    totalAmount = total.toDoubleOrNull(),
                    currency = if (currency.isBlank()) null else currency,
                    category = if (category.isBlank()) null else category,
                    items = items.map { it.copy() }
                )
                onSave(receipt)
            }) {
                Text("Save")
            }
        }
    }
}
