package com.example.myapp

import com.google.gson.annotations.SerializedName

data class F03AnalyzeAndCompareResponse(
    val status: String,
    val rack: String,

    @SerializedName("total_positions")
    val totalPositions: Int,

    val results: List<F03ComparedPosition>,
    @SerializedName("job_id") val jobId: String? = null,
    @SerializedName("data_source") val dataSource: String? = null,
    @SerializedName("erp_comparison_available") val erpComparisonAvailable: Boolean? = null,
    @SerializedName("erp_warehouse") val erpWarehouse: String? = null,
    @SerializedName("verification_scope") val verificationScope: String? = null,
    @SerializedName("ocr_status") val ocrStatus: String? = null,
    @SerializedName("visual_status") val visualStatus: String? = null,
    @SerializedName("ocr_tokens") val ocrTokens: List<F03OcrToken>? = null,
    val detections: List<F03Detection> = emptyList(),
    @SerializedName("registered_code_observations") val registeredCodes: List<F03RegisteredCode>? = null,
    @SerializedName("inventory_issues") val inventoryIssues: List<F03InventoryIssue>? = null
)

data class F03ComparedPosition(
    val rack: String,
    val floor: Int?,

    @SerializedName("erp_product_ids")
    val erpProductIds: List<String>,

    @SerializedName("ai_product_id")
    val aiProductId: String,

    @SerializedName("yolo_candidates")
    val yoloCandidates: Int,

    @SerializedName("review_status")
    val reviewStatus: String,
    @SerializedName("ai_product_ids") val aiProductIds: List<String>? = null,
    @SerializedName("product_names") val productNames: Map<String, String>? = null,
    @SerializedName("verification_passed") val verificationPassed: Boolean = false,
    @SerializedName("location_verified") val locationVerified: Boolean = false,
    @SerializedName("product_location_verified") val productLocationVerified: Boolean = false,
    @SerializedName("expected_quantity") val expectedQuantity: Double? = null,
    @SerializedName("quantity_verified") val quantityVerified: Boolean? = null,
    @SerializedName("location_method") val locationMethod: String? = null,
    @SerializedName("location_label") val locationLabel: String? = null,
    @SerializedName("review_reasons") val reviewReasons: List<String> = emptyList(),
    @SerializedName("detection_ids") val detectionIds: List<String> = emptyList(),
    @SerializedName("ocr_token_ids") val ocrTokenIds: List<String>? = null,
    @SerializedName("visual_matches") val visualMatches: List<F03VisualMatch>? = null,
    @SerializedName("appearance_location_verified") val appearanceLocationVerified: Boolean = false,
    @SerializedName("identity_method") val identityMethod: String? = null,
    @SerializedName("f04_status_code") val f04StatusCode: Int? = null,
    @SerializedName("ocr_max_confidence") val ocrMaxConfidence: Double? = null,
    @SerializedName("test_label_match") val testLabelMatch: Boolean = false
) {
    val isVerifiedMatch: Boolean get() = f04StatusCode == 1 && (verificationPassed || productLocationVerified)
    val isMatch: Boolean get() = isVerifiedMatch || testLabelMatch
    val isConfirmedAnomaly: Boolean get() = (f04StatusCode ?: 5) in 2..4
}

data class F03OcrToken(
    val text: String,
    val confidence: Double,
    val bbox: List<Float>,
    val id: String? = null,
    val kind: String? = null
)

data class F03VisualMatch(
    val sku: String,
    val inliers: Int,
    @SerializedName("inlier_ratio") val inlierRatio: Double,
    @SerializedName("reference_coverage") val referenceCoverage: Double,
    val bbox: List<Float>
)

data class F03Detection(
    val id: String,
    val bbox: List<Float>,
    val confidence: Float?,
    @SerializedName("product_ids") val productIds: List<String>,
    val source: String? = null,
    @SerializedName("location_verified") val locationVerified: Boolean = false,
    @SerializedName("verification_passed") val verificationPassed: Boolean = false,
    @SerializedName("product_location_verified") val productLocationVerified: Boolean = false,
    @SerializedName("review_status") val reviewStatus: String? = null,
    @SerializedName("detection_stable") val detectionStable: Boolean = false,
    @SerializedName("overlay_parent_id") val overlayParentId: String? = null,
    @SerializedName("ocr_token_ids") val ocrTokenIds: List<String>? = null,
    @SerializedName("support_scales") val supportScales: List<Int> = emptyList(),
    @SerializedName("test_label_match") val testLabelMatch: Boolean = false
)

data class F03RegisteredCode(val sku: String)
data class F03InventoryIssue(val reason: String)
