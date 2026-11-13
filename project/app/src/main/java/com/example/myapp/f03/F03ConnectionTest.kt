
package com.example.myapp

import android.util.Log
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

object F03ConnectionTest {

    fun testConnection() {

        val api = F03RetrofitClient.retrofit
            .create(F03ApiService::class.java)

        api.getAuditProgress().enqueue(
            object : Callback<F03AuditProgressResponse> {

                override fun onResponse(
                    call: Call<F03AuditProgressResponse>,
                    response: Response<F03AuditProgressResponse>
                ) {
                    if (response.isSuccessful) {

                        val data = response.body()

                        if (data != null) {
                            Log.d("F03_TEST", "서버 연결 성공!")
                            Log.d("F03_TEST", "전체 위치: ${data.summary.total}")
                            Log.d("F03_TEST", "실제 완료: ${data.summary.completed}")
                            Log.d("F03_TEST", "가상 완료: ${data.summary.simulatedCompleted}")
                            Log.d("F03_TEST", "미검사: ${data.summary.notChecked}")
                        }

                    } else {
                        Log.e(
                            "F03_TEST",
                            "서버 응답 오류: ${response.code()}"
                        )
                    }
                }

                override fun onFailure(
                    call: Call<F03AuditProgressResponse>,
                    t: Throwable
                ) {
                    Log.e(
                        "F03_TEST",
                        "서버 연결 실패: ${t.message}"
                    )
                }
            }
        )
    }
}
