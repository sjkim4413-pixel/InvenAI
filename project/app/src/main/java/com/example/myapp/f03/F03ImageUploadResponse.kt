
package com.example.myapp

import com.google.gson.annotations.SerializedName

data class F03ImageUploadResponse(
    @SerializedName("status")
    val status: String,

    @SerializedName("filename")
    val filename: String,

    @SerializedName("message")
    val message: String,

    @SerializedName("analysis_status")
    val analysisStatus: String
)
