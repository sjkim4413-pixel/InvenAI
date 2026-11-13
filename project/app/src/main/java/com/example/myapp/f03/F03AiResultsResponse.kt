package com.example.myapp


import com.google.gson.annotations.SerializedName

data class F03AiResultsResponse(
    @SerializedName("module")
    val module: String,

    @SerializedName("data_source")
    val dataSource: String,

    @SerializedName("total_positions")
    val totalPositions: Int,

    @SerializedName("results")
    val results: List<F03AiPosition>
)

data class F03AiPosition(
    @SerializedName("rack")
    val rack: String,

    @SerializedName("floor")
    val floor: Int,

    @SerializedName("erp_registered")
    val erpRegistered: Int,

    @SerializedName("ai_candidates")
    val aiCandidates: Int,

    @SerializedName("review_status")
    val reviewStatus: String
)
