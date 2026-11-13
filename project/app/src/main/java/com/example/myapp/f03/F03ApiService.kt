package com.example.myapp

import okhttp3.MultipartBody
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

interface F03ApiService {

    // 기존 실사 진행률
    @GET("f03/audit/progress")
    fun getAuditProgress(): Call<F03AuditProgressResponse>

    // 기존 AI + ERP 비교 결과
    @GET("f03/audit/ai-results")
    fun getAiResults(): Call<F03AiResultsResponse>

    // 기존 통합 AI + ERP 비교 결과
    @GET("f03/audit/integrated-results")
    fun getIntegratedResults(): Call<F03IntegratedResultsResponse>

    // 촬영 이미지 업로드
    @Multipart
    @POST("f03/audit/upload-image")
    fun uploadImage(
        @Part image: MultipartBody.Part
    ): Call<F03ImageUploadResponse>

    // 업로드된 이미지 AI 분석 요청 (filename 및 warehouse_id 전달)
    @POST("f03/audit/analyze-image")
    fun analyzeImage(
        @Query("filename") filename: String,
        @Query("warehouse_id") warehouseId: Int = 1
    ): Call<F03AnalyzeImageResponse>

    // 실제 AI 분석 + ERP 전산 비교 (filename 및 warehouse_id 전달)
    @POST("f03/audit/analyze-and-compare")
    fun analyzeAndCompare(
        @Query("filename") filename: String,
        @Query("warehouse_id") warehouseId: Int = 1,
        @Query("warehouse_code") warehouseCode: String? = null,
        @Query("mode") mode: String = "live"
    ): Call<F03AnalyzeAndCompareResponse>
}
