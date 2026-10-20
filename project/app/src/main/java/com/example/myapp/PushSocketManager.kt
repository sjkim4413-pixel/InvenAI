package com.example.myapp

import android.content.Context
import android.os.Build
import android.util.Log
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.UUID

// REQ_01_03_01: 소켓/푸시 연결 및 디바이스 고유 토큰 서버 등록 관리자
object PushSocketManager {
    private const val TAG = "PushSocketManager"

    fun registerDeviceToken(context: Context, employeeId: String, authToken: String) {
        val sharedPref = context.getSharedPreferences("app_user_session", Context.MODE_PRIVATE)
        var deviceToken = sharedPref.getString("DEVICE_TOKEN", null)

        if (deviceToken == null) {
            deviceToken = "FCM_TOKEN_" + UUID.randomUUID().toString().take(12)
            sharedPref.edit().putString("DEVICE_TOKEN", deviceToken).apply()
        }

        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        val request = DeviceRegisterRequest(
            employeeId = employeeId,
            deviceToken = deviceToken,
            deviceModel = deviceModel
        )

        Log.d(TAG, "Connecting Push/Socket & registering device token: $deviceToken for $employeeId ($deviceModel)")

        RetrofitClient.instance.registerDeviceToken("Bearer $authToken", request)
            .enqueue(object : Callback<Map<String, String>> {
                override fun onResponse(call: Call<Map<String, String>>, response: Response<Map<String, String>>) {
                    if (response.isSuccessful) {
                        Log.d(TAG, "Device token registered successfully on server.")
                    } else {
                        Log.w(TAG, "Device token registration response code: " + response.code())
                    }
                }

                override fun onFailure(call: Call<Map<String, String>>, t: Throwable) {
                    Log.e(TAG, "Device token registration network failure: " + t.message)
                }
            })
    }
}