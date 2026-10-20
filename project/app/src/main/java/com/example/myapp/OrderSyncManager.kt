package com.example.myapp

import android.content.Context
import android.util.Log
import android.widget.Toast
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

// REQ_01_02_01 & REQ_01_02_02: 적재 오더 API 수신 및 로컬 DB 캐싱 통합 관리자
object OrderSyncManager {
    private const val TAG = "OrderSyncManager"

    fun fetchAndCacheOrders(
        context: Context,
        authToken: String,
        onResult: (List<OrderResponse>, Boolean) -> Unit
    ) {
        val dbHelper = OrderDbHelper(context)

        RetrofitClient.instance.getExpectedOrders("Bearer $authToken")
            .enqueue(object : Callback<List<OrderResponse>> {
                override fun onResponse(
                    call: Call<List<OrderResponse>>,
                    response: Response<List<OrderResponse>>
                ) {
                    if (response.isSuccessful && response.body() != null) {
                        val orders = response.body()!!
                        Log.d(TAG, "API success. Fetched " + orders.size + " orders. Caching to local DB...")

                        // 로컬 DB (SQLite) 캐싱
                        dbHelper.cacheOrders(orders)
                        onResult(orders, true)
                    } else {
                        Log.w(TAG, "API call failed with code: " + response.code() + ". Loading local DB cache...")
                        val cachedOrders = dbHelper.getCachedOrders()
                        onResult(cachedOrders, false)
                    }
                }

                override fun onFailure(call: Call<List<OrderResponse>>, t: Throwable) {
                    Log.e(TAG, "Network error: " + t.message + ". Falling back to local DB cache.")
                    Toast.makeText(context, "네트워크 통신 단절. 로컬 DB 캐시 데이터를 불러옵니다.", Toast.LENGTH_SHORT).show()
                    val cachedOrders = dbHelper.getCachedOrders()
                    onResult(cachedOrders, false)
                }
            })
    }
}