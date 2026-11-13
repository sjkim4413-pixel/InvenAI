
package com.example.myapp

import com.google.gson.annotations.SerializedName

data class F03AnalyzeImageResponse(

    @SerializedName("status")
    val status: String,

    @SerializedName("rack")
    val rack: String,

    @SerializedName("total_positions")
    val totalPositions: Int,

    @SerializedName("shelf_boundaries")
    val shelfBoundaries: List<Int>,

    @SerializedName("results")
    val results: List<F03AnalyzePosition>
)

data class F03AnalyzePosition(

    @SerializedName("랙번호")
    val rack: String,

    @SerializedName("단수")
    val floor: Int,

    @SerializedName("YOLO탐지후보수")
    val yoloCandidates: Int,

    @SerializedName("인식제품ID")
    val recognizedProductId: String,

    @SerializedName("검토상태")
    val reviewStatus: String
)
