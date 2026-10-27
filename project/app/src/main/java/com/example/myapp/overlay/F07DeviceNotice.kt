package com.example.myapp.overlay

import com.example.myapp.R

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** 로컬 시연 기기의 알림만 갱신합니다. 다른 사람의 기기에 보내는 FCM 발송은 F-06 서버 역할입니다. */
object F07DeviceNotice {
    private const val CHANNEL = "invenai_action_demo"
    private const val PERMISSION = "android.permission.POST_NOTIFICATIONS"

    fun requestPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(PERMISSION), 707)
        }
    }

    fun update(context: Context, inspection: InspectionCase, location: String, status: String, detail: String) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "조치 진행 알림 (로컬 시연)", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = inspection.attach(Intent(context, OverlayActivity::class.java)).apply {
            data = android.net.Uri.parse("invenai://inspection/${inspection.key}/$location")
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("action_location", location)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val pending = PendingIntent.getActivity(context, location.hashCode(), intent, flags)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else Notification.Builder(context)
        val notification = builder.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("${inspection.title(context)} · ${displayLocation(location)} · $status")
            .setContentText(detail).setStyle(Notification.BigTextStyle().bigText(detail))
            .setSubText("로컬 시연 · 다른 기기에는 발송하지 않음")
            .setContentIntent(pending).setOnlyAlertOnce(true).setAutoCancel(true).build()
        // 위치별 태그를 유지하여 조치 중 -> 검수 대기 -> 정상/재조치를 같은 알림에 반영합니다.
        try { manager.notify("local-demo:${inspection.key}:$location", 707, notification) } catch (_: SecurityException) { }
    }
}