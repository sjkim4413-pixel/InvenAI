package com.example.myapp

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// F-06 예외 처리: 네트워크 단절 시 로컬 큐 대기열 관리 및 스낵바 안내 & 복구 시 자동 재발송
object F06OfflineQueueManager {
    private const val TAG = "F06OfflineQueueManager"
    private const val PREF_NAME = "f06_offline_queue"
    private const val KEY_QUEUE = "pending_alerts_json"

    // 네트워크 연결 가능 여부 체크
    fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    // 예외 처리: 네트워크 단절 시 스낵바 표출 및 기기 내 로컬 큐에 임시 저장
    fun enqueueAlert(activity: Activity, payload: MisplacementAlertPayload) {
        val queue = getQueue(activity).toMutableList()
        queue.add(payload)
        saveQueue(activity, queue)

        Log.w(TAG, "Network offline. Alert " + payload.alertId + " added to local offline queue.")

        // 명세서 규격: "네트워크 연결 불안정. 발송 대기열에 추가합니다" 스낵바 표출
        val rootView = activity.window.decorView.rootView
        Snackbar.make(rootView, "네트워크 연결 불안정. 발송 대기열에 추가합니다", Snackbar.LENGTH_LONG)
            .setAction("확인") {}
            .show()
    }

    // 네트워크 복구 시 대기열의 알림 자동 재발송
    fun flushPendingQueue(activity: Activity, onSend: (MisplacementAlertPayload) -> Unit) {
        if (!isNetworkAvailable(activity)) return

        val queue = getQueue(activity)
        if (queue.isNotEmpty()) {
            Log.d(TAG, "Network restored. Flushing " + queue.size + " alerts from local queue...")
            for (alert in queue) {
                onSend(alert)
            }
            clearQueue(activity)
            Snackbar.make(activity.window.decorView.rootView, "네트워크 복구 완료: 대기열 알림 재발송 완료", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun getQueue(context: Context): List<MisplacementAlertPayload> {
        val pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = pref.getString(KEY_QUEUE, null) ?: return emptyList()
        val type = object : TypeToken<List<MisplacementAlertPayload>>() {}.type
        return try {
            Gson().fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveQueue(context: Context, queue: List<MisplacementAlertPayload>) {
        val pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = Gson().toJson(queue)
        pref.edit().putString(KEY_QUEUE, json).apply()
    }

    private fun clearQueue(context: Context) {
        val pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        pref.edit().remove(KEY_QUEUE).apply()
    }
}