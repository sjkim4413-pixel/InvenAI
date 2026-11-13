package com.example.myapp

import android.util.Log
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object F04AuthClient {

    private val api: F04ApiService by lazy {
        Retrofit.Builder()
            .baseUrl("http://10.0.2.2:8083/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(F04ApiService::class.java)
    }

    fun verifyCurrentUser(
        onSuccess: (F04AuthResponse) -> Unit,
        onError: (String) -> Unit
    ) {
        F04AuthTokenProvider.getToken(
            onSuccess = { token ->

                api.verifyFirebaseUser("Bearer $token")
                    .enqueue(object : Callback<F04AuthResponse> {

                        override fun onResponse(
                            call: Call<F04AuthResponse>,
                            response: Response<F04AuthResponse>
                        ) {
                            val body = response.body()

                            if (response.isSuccessful &&
                                body != null &&
                                body.authenticated
                            ) {
                                onSuccess(body)
                            } else {
                                val detail = response.errorBody()
                                    ?.string()
                                    ?.take(200)
                                    ?: "NO_DETAIL"

                                Log.e("F04_AUTH", "HTTP ${response.code()} / $detail")

                                onError(
                                    "서버 인증 실패 (HTTP ${response.code()}): $detail"
                                )
                            }
                        }

                        override fun onFailure(
                            call: Call<F04AuthResponse>,
                            error: Throwable
                        ) {
                            onError("인증 서버에 연결하지 못했습니다.")
                        }
                    })
            },
            onError = onError
        )
    }
}
