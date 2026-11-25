package com.example.myapp

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.work.*
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.concurrent.TimeUnit
import androidx.core.app.NotificationCompat
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

internal data class F06PushEnvelope(val id: String, val kind: String, val company: String, val uid: String, val event: String)

internal fun parseF06Push(data: Map<String, String>): F06PushEnvelope? {
    if (data["type"] != "invenai_anomaly") return null
    val kind = data["kind"]?.takeIf { it in setOf("audit", "inventory") } ?: return null
    val id = data["alert_id"]?.takeIf { it.isNotBlank() && it.length <= 240 && '/' !in it } ?: return null
    if (kind == "audit" && !id.matches(Regex("AI_[a-f0-9]{64}"))) return null
    val company = data["company_code"]?.takeIf { it.matches(Regex("[A-Z0-9][A-Z0-9_-]{0,63}")) } ?: return null
    val uid = data["recipient_uid"]?.takeIf { it.isNotBlank() && it.length <= 128 && '/' !in it } ?: return null
    val event = data["event_id"]?.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: return null
    return F06PushEnvelope(id, kind, company, uid, event)
}

internal enum class F06PushReceipt { DONE, RETRY }

internal object F06PushInbox {
    /** Called off the main thread. Always verify server documents before exposing stock details. */
    fun receive(context: Context, data: Map<String, String>, timeoutSeconds: Long = 3): F06PushReceipt {
        val message = parseF06Push(data) ?: return F06PushReceipt.DONE
        fun ownsSession() = FirebaseAuth.getInstance().currentUser?.uid == message.uid &&
            InventoryManager.activeCompanyCode(context) == message.company && InventoryManager.role(context) == "LOADER"
        if (!ownsSession()) return F06PushReceipt.DONE
        return try {
            val db = FirebaseFirestore.getInstance()
            val member = Tasks.await(db.collection(WarehouseMembership.COLLECTION).document(message.uid).get(Source.SERVER), timeoutSeconds, TimeUnit.SECONDS)
            if (member.getString("companyCode") != message.company || member.getString("role") != "LOADER") return F06PushReceipt.DONE
            val document = Tasks.await(db.collection(if (message.kind == "audit") AuditAlertManager.COLLECTION else "inventory")
                .document(message.id).get(Source.SERVER), timeoutSeconds, TimeUnit.SECONDS)
            if (!document.exists() || !ownsSession() || document.getString("companyCode") != message.company) return F06PushReceipt.DONE
            if (message.kind == "audit") {
                val alert = AuditAlertManager.read(document) ?: return F06PushReceipt.DONE
                if (alert.status == "OPEN") F06NotificationHelper.notifyAuditAlert(context, alert)
            } else {
                val item = InventoryManager.readItem(document)
                if (item.status == "ANOMALY") F06NotificationHelper.notifyInventoryAlert(context, item)
            }
            F06PushReceipt.DONE
        } catch (error: Exception) {
            val cause = error.cause ?: error
            if (cause is com.google.firebase.firestore.FirebaseFirestoreException && cause.code in setOf(
                    com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED,
                    com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAUTHENTICATED)) F06PushReceipt.DONE
            else F06PushReceipt.RETRY
        }
    }

    fun schedule(context: Context, data: Map<String, String>, urgent: Boolean = false) {
        val message = parseF06Push(data) ?: return
        if (FirebaseAuth.getInstance().currentUser?.uid != message.uid ||
            InventoryManager.activeCompanyCode(context) != message.company || InventoryManager.role(context) != "LOADER") return
        val input = Data.Builder().apply { data.forEach { (key, value) -> putString(key, value) } }.build()
        val builder = OneTimeWorkRequestBuilder<F06PushInboxWorker>().setInputData(input)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
        if (urgent) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        val work = builder.build()
        WorkManager.getInstance(context).enqueueUniqueWork("f06-inbox:${message.uid}:${message.event}", ExistingWorkPolicy.KEEP, work)
    }
}

class F06MessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Handler(Looper.getMainLooper()).post { F06PushRegistration.sync(applicationContext, token) }
    }
    override fun onMessageReceived(message: RemoteMessage) {
        android.util.Log.i("F06Push", "Cloud message received at ${System.currentTimeMillis()} priority=${message.priority}")
        F06PushInbox.schedule(applicationContext, message.data, message.priority == RemoteMessage.PRIORITY_HIGH)
    }
}

class F06PushInboxWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun getForegroundInfoAsync(): ListenableFuture<ForegroundInfo> {
        // WorkManager uses this brief, silent notification for expedited work before Android 12.
        val channel = "f06_alert_verification"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(channel, "알림 상태 확인", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("이상 재고 알림 확인 중")
            .setOngoing(true).setSilent(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        return Futures.immediateFuture(ForegroundInfo(6048, notification))
    }
    override fun doWork(): Result {
        android.util.Log.i("F06Push", "Notification verification started at ${System.currentTimeMillis()}")
        val values = inputData.keyValueMap.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
        return if (F06PushInbox.receive(applicationContext, values, 5) == F06PushReceipt.DONE || runAttemptCount >= 5)
            Result.success() else Result.retry()
    }
}
