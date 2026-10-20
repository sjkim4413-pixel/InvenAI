package com.example.myapp

import com.google.gson.annotations.SerializedName

// REQ_01_02_01: 적재 오더 Data Model (expected.csv / JSON)
data class OrderResponse(
    @SerializedName("orderId") val orderId: String,
    @SerializedName("itemCode") val itemCode: String,
    @SerializedName("itemName") val itemName: String,
    @SerializedName("quantity") val quantity: Int,
    @SerializedName("location") val location: String,
    @SerializedName("status") val status: String
)

// REQ_01_03_01: 디바이스 토큰 등록 Request Model
data class DeviceRegisterRequest(
    @SerializedName("employeeId") val employeeId: String,
    @SerializedName("deviceToken") val deviceToken: String,
    @SerializedName("deviceModel") val deviceModel: String
)