package com.example.myapp

import com.google.gson.annotations.SerializedName

data class F03IntegratedResultsResponse(
    @SerializedName("module")
    val module: String,

    @SerializedName("data_source")
    val dataSource: String,

    @SerializedName("total_positions")
    val totalPositions: Int,

    @SerializedName("results")
    val results: List<F03IntegratedPosition>
)

data class F03IntegratedPosition(
    @SerializedName("rack")
    val rack: String,

    @SerializedName("floor")
    val floor: Int,

    @SerializedName("erp_product_id")
    val erpProductId: String,

    @SerializedName("ai_product_id")
    val aiProductId: String,

    @SerializedName("yolo_candidates")
    val yoloCandidates: Int,

    @SerializedName("review_status")
    val reviewStatus: String
)
