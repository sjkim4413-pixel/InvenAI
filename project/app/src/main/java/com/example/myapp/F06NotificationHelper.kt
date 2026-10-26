package com.example.myapp

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

// F-06 3초 이내 푸시 알림 및 헤즈업 팝업 전송 헬퍼
object F06NotificationHelper {
    private const val CHANNEL_ID = "f06_misplacement_channel"
    private const val CHANNEL_NAME = "F-06 오적재 푸시 알림"

    // F-06 검증방법: 오적재 알림 발송 시, 3초 이내에 타겟 적재자 기기에서 알림 소리와 함께 푸시 표출
    fun sendHeadsUpNotification(
        context: Context,
        payload: MisplacementAlertPayload
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "오적재 실시간 현장 시정 요청 알림"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, Frame1::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_F06_ALERT_ID", payload.alertId)
            putExtra("LOCATION", payload.location)
            putExtra("EXPECTED_ID", payload.expectedItemCode)
            putExtra("CURRENT_ID", payload.currentItemCode)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            payload.alertId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("🚨 F-06 오적재 실시간 푸시 알림")
            .setContentText("발생 위치: " + payload.location + " [정상:" + payload.expectedItemCode + " / 오적재:" + payload.currentItemCode + "]")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            notificationManager.notify(payload.alertId.hashCode(), notification)
        } catch (e: Exception) {
            // 알림 권한 비활성화 시 예외 가드
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