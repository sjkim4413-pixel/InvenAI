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
    val warehouse: String = "제1물류센터",
    val zone: String = "B구역",
    val rack: String = "R05",
    val tier: String = "3층",
    val rackId: String = "B구역 R05"
)
