package com.example.myapp

import com.google.gson.annotations.SerializedName

data class F03AnalyzeRequest(
    @SerializedName("filename")
    val filename: String
)
