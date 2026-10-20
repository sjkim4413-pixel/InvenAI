package com.example.myapp

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface ApiService {
    // 1. REQ_01_01_01: 로그인 API
    @POST("/api/login")
    fun login(@Body request: LoginRequest): Call<LoginResponse>

    // 2. 회원가입 API
    @POST("/api/signup")
    fun register(@Body request: RegisterRequest): Call<RegisterResponse>

    // 3. REQ_01_02_01: 적재 오더 목록 수신 API
    @GET("/api/orders/expected")
    fun getExpectedOrders(@Header("Authorization") token: String): Call<List<OrderResponse>>

    // 4. REQ_01_03_01: 디바이스 토큰 등록 API
    @POST("/api/device/register")
    fun registerDeviceToken(
        @Header("Authorization") token: String,
        @Body request: DeviceRegisterRequest
    ): Call<Map<String, String>>
}