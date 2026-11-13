package com.example.myapp

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object F03RetrofitClient {

    // Android 에뮬레이터에서 PC의 F-03 서버 접속
    private const val BASE_URL = "http://10.0.2.2:8082/"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS) // CPU 연산(YOLO+OCR) 소요 시간 고려하여 90초로 넉넉하게 설정
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val token = try {
                val user = FirebaseAuth.getInstance().currentUser
                if (user != null) {
                    Tasks.await(user.getIdToken(false), 15, TimeUnit.SECONDS)?.token
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }

            val request = if (token.isNullOrBlank()) original
                else original.newBuilder().header("Authorization", "Bearer $token").build()
            chain.proceed(request)
        }
        .build()

    val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
}
