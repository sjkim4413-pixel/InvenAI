package com.example.myapp

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

// Firestore 실시간 수신 후 적재자 기기에 표시하는 로컬 알림 도우미.
object F06NotificationHelper {
    const val CHANNEL_ID = "f06_misplacement_channel"
    private const val CHANNEL_NAME = "F-06 오적재 푸시 알림"
    private val notificationLock = Any()

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "이상 재고 발생 시 소리·진동·상단 알림"
                enableVibration(true)
            })
        }
    }

    fun notificationsEnabled(context: Context): Boolean {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE)
    }

    fun headsUpEnabled(context: Context): Boolean {
        if (!notificationsEnabled(context)) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID).importance >= NotificationManager.IMPORTANCE_HIGH
    }

    internal fun notifyAuditAlert(context: Context, alert: AuditMisplacementAlert): Boolean {
        if (alert.status != "OPEN" || InventoryManager.activeCompanyCode(context) != alert.companyCode) return false
        return postOnce(context, alert.id, alert.updatedAt, MisplacementAlertPayload(
            alert.id, alert.location, alert.expectedItemCode, alert.observedItemCode, isTest = alert.isTest), "audit")
    }

    internal fun notifyInventoryAlert(context: Context, item: InventoryItem): Boolean {
        if (item.status != "ANOMALY") return false
        return postOnce(context, item.documentId, item.updatedAtTimestamp,
            MisplacementAlertPayload(item.documentId, item.location, item.itemCode, item.itemCode, isTest = item.isTestData), "inventory")
    }

    private fun postOnce(context: Context, id: String, updatedAt: Long, payload: MisplacementAlertPayload, kind: String): Boolean {
        val company = InventoryManager.activeCompanyCode(context) ?: return false
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return false
        if (InventoryManager.role(context) != "LOADER") return false
        val key = if (kind == "audit") "$company:$uid:$id:$updatedAt" else "$company:$uid:inventory:$id:$updatedAt"
        return synchronized(notificationLock) {
            val prefs = context.getSharedPreferences("audit_alert_notifications", Context.MODE_PRIVATE)
            if (prefs.getBoolean(key, false)) true else {
                val posted = sendHeadsUpNotification(context, payload, kind)
                if (posted) prefs.edit().putBoolean(key, true).apply()
                posted
            }
        }
    }

    fun sendHeadsUpNotification(
        context: Context,
        payload: MisplacementAlertPayload,
        kind: String = "audit"
    ): Boolean {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!notificationsEnabled(context)) return false

        val intent = Intent(context, if (kind == "inventory") InventoryDetail::class.java else LoaderHomeDashboard::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_F06_ALERT_ID", payload.alertId)
            putExtra("LOCATION", payload.location)
            putExtra("EXPECTED_ID", payload.expectedItemCode)
            putExtra("CURRENT_ID", payload.currentItemCode)
            if (kind == "inventory") putExtra("INVENTORY_ID", payload.alertId)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            payload.alertId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(if (payload.isTest) "테스트 오적재 알림" else "실사 오적재 조치 요청")
            .setContentText(if (kind == "inventory") "${payload.location} · ${payload.expectedItemCode} 재적재 확인 필요"
                else "${payload.location} · 기준 ${payload.expectedItemCode} / 사진 ${payload.currentItemCode}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pendingIntent)
            .build()

        try {
            notificationManager.notify(payload.alertId.hashCode(), notification)
            android.util.Log.i("F06Push", "Device notification posted at ${System.currentTimeMillis()}")
            return true
        } catch (e: Exception) {
            return false
        }
    }

    // F-06 처리 파이프라인: 온라인 시 FCM/푸시 전송, 오프라인 단절 시 로컬 큐에 임시 저장 및 스낵바 표출
    fun dispatchAlert(
        activity: Activity,
        payload: MisplacementAlertPayload,
        onSuccess: () -> Unit = {}
    ) {
        if (F06OfflineQueueManager.isNetworkAvailable(activity)) {
            sendHeadsUpNotification(activity, payload)
            onSuccess()
        } else {
            // F-06 예외 규격: "네트워크 연결 불안정. 발송 대기열에 추가합니다" 스낵바 표출
            F06OfflineQueueManager.enqueueAlert(activity, payload)
            onSuccess()
        }
    }
}
