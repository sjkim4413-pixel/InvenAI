package com.example.myapp

import com.google.gson.annotations.SerializedName
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.GET
import retrofit2.http.Header

data class F04DetectionItem(
    val location: String,
    @SerializedName("product_id")
    val productId: String? = null,
    @SerializedName("detection_state")
    val detectionState: String = "unreadable"
)

data class F04ReviewRequest(
    val detections: List<F04DetectionItem>
)

data class F04ReviewItem(
    val location: String,
    @SerializedName("ai_product_id")
    val aiProductId: String?,
    @SerializedName("review_status")
    val reviewStatus: String,
    @SerializedName("verification_passed")
    val verificationPassed: Boolean,
    val reason: String
)

data class F04ReviewResponse(
    val module: String,
    val status: String,
    @SerializedName("total_positions")
    val totalPositions: Int,
    val results: List<F04ReviewItem>
)

data class F04AuthResponse(
    val authenticated: Boolean,
    val uid: String,
    val companyCode: String
)

interface F04ApiService {

    @GET("f04/auth/verify")
    fun verifyFirebaseUser(
        @Header("Authorization") authorization: String
    ): Call<F04AuthResponse>

    @POST("f04/safe-review")
    fun safeReview(
        @Body request: F04ReviewRequest
    ): Call<F04ReviewResponse>
}
