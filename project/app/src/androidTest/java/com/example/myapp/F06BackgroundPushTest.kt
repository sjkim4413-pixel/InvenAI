package com.example.myapp

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.messaging.FirebaseMessaging
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Firebase database/auth are local; real-FCM mode targets only this test emulator. */
@RunWith(AndroidJUnit4::class)
class F06BackgroundPushTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun waitFor(message: String, seconds: Long = 30, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime()+seconds*1000
        while (SystemClock.elapsedRealtime()<end) { if (condition()) return;SystemClock.sleep(200) }
        fail(message)
    }
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }

    @Test fun anAuthenticatedPushShowsOutsideTheAppAndRejectsStaleOrOtherAccountMessages() {
        val auth = FirebaseAuth.getInstance().apply { useEmulator("10.0.2.2",18862) }
        val db = FirebaseFirestore.getInstance().apply { useEmulator("10.0.2.2",18861) }
        check(db.firestoreSettings.host=="10.0.2.2:18861")
        Tasks.await(db.clearPersistence(),10,TimeUnit.SECONDS)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val probe = File(context.noBackupFilesDir,"push48-probe.json")
        var home: android.app.Activity? = null
        val prefs = context.getSharedPreferences("app_user_session",Context.MODE_PRIVATE)
        fun login(role: String) {
            Tasks.await(auth.signInWithEmailAndPassword(if(role=="QA") "qa-ui45@invenai.test" else "loader-ui45@invenai.test",
                "Ui45LocalOnly!2026"),20,TimeUnit.SECONDS)
            prefs.edit().putString("FIREBASE_UID",auth.currentUser!!.uid).putString("COMPANY_CODE","ABC-002")
                .putString("ROLE",role).putString("USER_ID","push-background-test").apply()
        }
        try {
            login("QA")
            val qa = auth.currentUser!!.uid
            for (doc in Tasks.await(db.collection(AuditAlertManager.COLLECTION).whereEqualTo("companyCode","ABC-002")
                .get(Source.SERVER),20,TimeUnit.SECONDS).documents) {
                if(doc.getString("status")!="RESOLVED") Tasks.await(doc.reference.update(mapOf("status" to "RESOLVED","updatedAt" to FieldValue.serverTimestamp())))
            }
            val before = Tasks.await(db.collection("inventory").whereEqualTo("companyCode","ABC-002").get(Source.SERVER),20,TimeUnit.SECONDS)
                .documents.associate { it.id to it.data }
            val response = instrumentation.context.assets.open("ui45/pair_03_misplaced.json").bufferedReader().use {
                Gson().fromJson(it,F03AnalyzeAndCompareResponse::class.java)
            }.copy(jobId=UUID.randomUUID().toString().replace("-",""))
            val planned = planAuditMisplacementAlerts(response,qa).first()
            val reference = db.collection(AuditAlertManager.COLLECTION).document(planned.id)
            Tasks.await(reference.set(AuditAlertManager.documentFields(planned)),20,TimeUnit.SECONDS)
            login("LOADER")
            val uid = auth.currentUser!!.uid
            notifications.cancelAll()
            F06NotificationHelper.ensureChannel(context)
            assertTrue(F06NotificationHelper.notificationsEnabled(context))
            val event = java.security.MessageDigest.getInstance("SHA-256").digest(planned.id.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val data = mapOf("type" to "invenai_anomaly","kind" to "audit","alert_id" to planned.id,
                "company_code" to "ABC-002","recipient_uid" to uid,"event_id" to event)
            val real = InstrumentationRegistry.getArguments().getString("realFcm")=="true"
            if (real) {
                FirebaseMessaging.getInstance().isAutoInitEnabled=true
                val token = Tasks.await(FirebaseMessaging.getInstance().token,45,TimeUnit.SECONDS)
                assertTrue("FCM must return a device token",token.length>20)
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                shell("input keyevent KEYCODE_SLEEP")
                // Only the local coordinator reads this private file; never print its token.
                probe.writeText(Gson().toJson(mapOf("token" to token,"data" to data)))
                waitFor("Real FCM must notify while another app is active and the screen is off",180) {
                    notifications.activeNotifications.any { it.id==planned.id.hashCode() }
                }
            } else {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                assertEquals(F06PushReceipt.DONE,F06PushInbox.receive(context,data,5))
            }
            val first = notifications.activeNotifications.single { it.id==planned.id.hashCode() }
            File(context.getExternalFilesDir(null),"push48-receipt.json").writeText(Gson().toJson(mapOf(
                "real_fcm" to real,"posted_at_epoch_ms" to first.postTime,"screen_off" to real)))
            assertEquals(F06NotificationHelper.CHANNEL_ID,first.notification.channelId)
            assertEquals(NotificationManager.IMPORTANCE_HIGH,notifications.getNotificationChannel(F06NotificationHelper.CHANNEL_ID).importance)
            assertEquals(F06PushReceipt.DONE,F06PushInbox.receive(context,data,5))
            assertEquals(first.postTime,notifications.activeNotifications.single { it.id==planned.id.hashCode() }.postTime)
            shell("input keyevent KEYCODE_WAKEUP")
            shell("input keyevent KEYCODE_MENU")
            val monitor = instrumentation.addMonitor(LoaderHomeDashboard::class.java.name,null,false)
            first.notification.contentIntent.send()
            home=instrumentation.waitForMonitorWithTimeout(monitor,15000)
            instrumentation.removeMonitor(monitor)
            assertNotNull("Notification must open the loader's action screen",home)
            assertEquals(planned.id,home!!.intent.getStringExtra("OPEN_F06_ALERT_ID"))
            instrumentation.runOnMainSync { home?.finish();home=null }
            notifications.cancel(planned.id.hashCode())
            waitFor("Android must finish cancelling the existing notification",5) {
                notifications.activeNotifications.none { it.id==planned.id.hashCode() }
            }
            assertEquals(F06PushReceipt.DONE,F06PushInbox.receive(context,data+("recipient_uid" to "other-user"),5))
            assertEquals(F06PushReceipt.DONE,F06PushInbox.receive(context,data+("company_code" to "OTHER"),5))
            assertTrue(notifications.activeNotifications.none { it.id==planned.id.hashCode() })
            login("QA")
            Tasks.await(reference.update(mapOf("status" to "RESOLVED","updatedAt" to FieldValue.serverTimestamp())),20,TimeUnit.SECONDS)
            login("LOADER")
            notifications.cancelAll()
            waitFor("Old notifications must be cancelled before checking a closed alert",5) {
                notifications.activeNotifications.none { it.id==planned.id.hashCode() }
            }
            assertEquals(F06PushReceipt.DONE,F06PushInbox.receive(context,data,5))
            assertTrue(notifications.activeNotifications.none { it.id==planned.id.hashCode() })
            auth.signOut();prefs.edit().clear().apply()
            assertEquals(F06PushReceipt.DONE,F06PushInbox.receive(context,data,5))
            login("QA")
            val after = Tasks.await(db.collection("inventory").whereEqualTo("companyCode","ABC-002").get(Source.SERVER),20,TimeUnit.SECONDS)
                .documents.associate { it.id to it.data }
            assertEquals(before,after)
            println(if(real) "REAL_FCM_BACKGROUND_AND_SCREEN_OFF_VERIFIED" else "LOCAL_PUSH_HANDLER_VERIFIED")
        } finally {
            probe.delete()
            shell("input keyevent KEYCODE_WAKEUP")
            shell("input keyevent KEYCODE_MENU")
            instrumentation.runOnMainSync { home?.finish() }
            auth.signOut();prefs.edit().clear().apply()
            notifications.cancelAll()
        }
    }
}
