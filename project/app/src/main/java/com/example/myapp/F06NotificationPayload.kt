package com.example.myapp

import com.google.gson.annotations.SerializedName

// F-06: 오적재 알림 JSON 페이로드 모델
data class MisplacementAlertPayload(
    @SerializedName("alertId") val alertId: String,
    @SerializedName("location") val location: String,            // 오적재 위치 (예: "B구역 R05-3층")
    @SerializedName("expectedItemCode") val expectedItemCode: String, // 정상 배치 대상 ID
    @SerializedName("currentItemCode") val currentItemCode: String,   // 오적재된 ID
    @SerializedName("photoUrl") val photoUrl: String = "",       // 크롭된 현장 사진 URL
    @SerializedName("status") val status: String = "ALERTED",    // ALERTED -> WAITING_REVERIFICATION -> VERIFIED
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)