package com.example.myapp

data class InventoryItem(
    val itemCode: String = "",
    val itemName: String = "",
    val quantity: Int = 0,
    val location: String = "",
    val status: String = "NORMAL", // "NORMAL", "ANOMALY", "PENDING_QA", "OUTBOUND_COMPLETE"
    val pendingQuantity: Int = 0,
    val pendingLocation: String = "",
    val updatedAtTimestamp: Long = System.currentTimeMillis(),
    val userId: String = "시스템",
    val targetLocation: String = "",
    val relocationDone: Boolean = false,
    val warehouse: String = "",
    val zone: String = "",
    val rack: String = "",
    val tier: String = "",
    val rackId: String = "",
    val documentId: String = "",
    val palletId: String = "",
    val receivedAtTimestamp: Long = 0L,
    val barcodes: List<String> = emptyList(),
    val isTestData: Boolean = false
)
