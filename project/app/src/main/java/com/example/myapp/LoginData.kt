package com.example.myapp

import com.google.gson.annotations.SerializedName

// 1. 로그인 요청 (Request) 데이터
data class LoginRequest(
    @SerializedName("employeeId") val employeeId: String,
    @SerializedName("password") val password: String
)

// 2. 로그인 응답 (Response) 데이터
data class LoginResponse(
    @SerializedName("token") val token: String, // JWT 토큰
    @SerializedName("role") val role: String    // "QA" 또는 "LOADER"
)

// 3. 회원가입 요청 (Request) 데이터
data class RegisterRequest(
    @SerializedName("companyCode") val companyCode: String,
    @SerializedName("employeeId") val employeeId: String,
    @SerializedName("password") val password: String,
    @SerializedName("role") val role: String    // "QA" 또는 "LOADER"
)

// 4. 회원가입 응답 (Response) 데이터
data class RegisterResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String
)