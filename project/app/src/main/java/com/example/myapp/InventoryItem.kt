package com.example.myapp

data class InventoryItem(
    val itemCode: String = "",
    val itemName: String = "",
    val quantity: Int = 0,
    val location: String = "",
    val status: String = "NORMAL", // "NORMAL", "ANOMALY", "PENDING_QA"
    val pendingQuantity: Int = 0,
    val pendingLocation: String = ""
)