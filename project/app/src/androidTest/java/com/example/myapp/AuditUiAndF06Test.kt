package com.example.myapp

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** All writes are guarded to local emulator ports before any test account login. */
@RunWith(AndroidJUnit4::class)
class AuditUiAndF06Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val company = "ABC-002"
    private val password = "Ui45LocalOnly!2026"
    private fun waitFor(message: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 25000
        while (SystemClock.elapsedRealtime() < end) { if (condition()) return; SystemClock.sleep(150) }
        fail(message)
    }
    private fun session(auth: FirebaseAuth, role: String) {
        context.getSharedPreferences("app_user_session", Context.MODE_PRIVATE).edit()
            .putString("COMPANY_CODE", company).putString("FIREBASE_UID", auth.currentUser!!.uid)
            .putString("USER_ID", "ui45-$role").putString("ROLE", role).apply()
    }
    private fun login(auth: FirebaseAuth, role: String) {
        Tasks.await(auth.signInWithEmailAndPassword(if (role == "QA") "qa-ui45@invenai.test" else "loader-ui45@invenai.test", password), 20, TimeUnit.SECONDS)
        session(auth, role)
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        repeat(3) {
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            if (bitmap != null) {
                File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                return
            }
            SystemClock.sleep(200)
        }
        // Captures support the UI assertions; an emulator compositor failure is not an app-flow failure.
        File(context.getExternalFilesDir(null), "ui45_capture_unavailable.txt").appendText("$name\n")
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun a_actualQaResultSendsOnceAndASecondQaClientNotifiesTheConnectedLoader() {
        val auth = FirebaseAuth.getInstance()
        val db = FirebaseFirestore.getInstance()
        auth.useEmulator("10.0.2.2", 18862)
        db.useEmulator("10.0.2.2", 18861)
        check(db.firestoreSettings.host == "10.0.2.2:18861")
        // Restarted emulators cannot resume watch tokens from an older local database.
        Tasks.await(db.clearPersistence(), 10, TimeUnit.SECONDS)
        val finding = instrumentation.context.assets.open("ui45/pair_03_misplaced.json").bufferedReader().use {
            Gson().fromJson(it, F03AnalyzeAndCompareResponse::class.java)
        }.copy(jobId = java.util.UUID.randomUUID().toString().replace("-", ""))
        val liveJob = java.util.UUID.randomUUID().toString().replace("-", "")
        val collection = db.collection(AuditAlertManager.COLLECTION)
        var senderApp: FirebaseApp? = null
        var detailActivity: Activity? = null
        try {
            login(auth, "QA")
            val qa = auth.currentUser!!.uid
            // An earlier interrupted local run may have left a finding open.
            for (doc in Tasks.await(collection.whereEqualTo("companyCode", company).get(Source.SERVER), 20, TimeUnit.SECONDS).documents) {
                if (doc.getString("status") != "RESOLVED") Tasks.await(doc.reference.update(mapOf(
                    "status" to "RESOLVED", "updatedAt" to FieldValue.serverTimestamp())))
            }
            val beforeStock = Tasks.await(db.collection("inventory").whereEqualTo("companyCode", company).get(Source.SERVER), 20, TimeUnit.SECONDS)
                .documents.associate { it.id to it.data }
            val planned = planAuditMisplacementAlerts(finding, qa)
            assertEquals(2, planned.size)
            CapturedPhotoHolder.getAndClear()
            ActivityScenario.launch<AiAuditResults>(Intent(context, AiAuditResults::class.java)).use { scenario ->
                fun trigger() = scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity)[AuditResultViewModel::class.java]
                    AuditResultViewModel::class.java.getDeclaredMethod("sendAuditAlerts", F03AnalyzeAndCompareResponse::class.java)
                        .apply { isAccessible = true }.invoke(model, finding)
                }
                trigger()
                waitFor("QA findings must reach Firestore") {
                    planned.all { Tasks.await(collection.document(it.id).get(Source.SERVER), 10, TimeUnit.SECONDS).exists() }
                }
                val created = planned.associate { it.id to Tasks.await(collection.document(it.id).get(Source.SERVER)).getTimestamp("createdAt") }
                trigger()
                waitFor("Repeated rendering must complete without another finding") { AuditAlertManager.queueState.value?.pending?.intersect(planned.map { it.id }.toSet()).isNullOrEmpty() }
                assertTrue(planned.all { created[it.id] == Tasks.await(collection.document(it.id).get(Source.SERVER)).getTimestamp("createdAt") })
                // Close this first occurrence so the loader receives only the later, live arrival.
                for (alert in planned) {
                    val done = CountDownLatch(1);var error: String? = null
                    instrumentation.runOnMainSync { AuditAlertManager.changeStatus(context, alert, "RESOLVED", "Validated first scan", { done.countDown() }, { error = it;done.countDown() }) }
                    assertTrue(done.await(15, TimeUnit.SECONDS));assertNull(error)
                }
            }

            auth.signOut();login(auth, "LOADER")
            ActivityScenario.launch<LoaderHomeDashboard>(Intent(context, LoaderHomeDashboard::class.java)).use { loader ->
                waitFor("Loader must subscribe before the next scan") {
                    var ready = false
                    loader.onActivity { ready = it.findViewById<TextView>(R.id.tvAlertContent).text.isNotBlank() }
                    ready
                }
                senderApp = FirebaseApp.initializeApp(context, FirebaseApp.getInstance().options, "ui45-qa-sender")!!
                val senderAuth = FirebaseAuth.getInstance(senderApp!!).apply { useEmulator("10.0.2.2", 18862) }
                val senderDb = FirebaseFirestore.getInstance(senderApp!!).apply { useEmulator("10.0.2.2", 18861) }
                check(senderDb.firestoreSettings.host == "10.0.2.2:18861")
                Tasks.await(senderAuth.signInWithEmailAndPassword("qa-ui45@invenai.test", password), 20, TimeUnit.SECONDS)
                val live = planAuditMisplacementAlerts(finding.copy(jobId = liveJob), senderAuth.currentUser!!.uid)
                for (alert in live) Tasks.await(senderDb.collection(AuditAlertManager.COLLECTION).document(alert.id)
                    .set(AuditAlertManager.documentFields(alert)), 20, TimeUnit.SECONDS)
                waitFor("Both streamed findings must be combined in the loader popup") {
                    instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("실사 오적재 알림 2건")?.isNotEmpty() == true
                }
                onView(withText("실사 오적재 알림 2건")).inRoot(isDialog()).check(matches(isDisplayed()))
                loader.onActivity {
                    assertTrue(it.findViewById<TextView>(R.id.tvAlertTitle).text.contains("오적재 조치 요청"))
                    assertTrue(it.findViewById<TextView>(R.id.tvAlertContent).text.contains("테스트 실사"))
                }
                val notifications = context.getSystemService(NotificationManager::class.java).activeNotifications
                assertTrue(live.all { alert -> notifications.any { it.id == alert.id.hashCode() } })
                screenshot("ui45_loader_alert.png")
                val monitor = instrumentation.addMonitor(AnomalyInventory::class.java.name, null, false)
                onView(withText("오적재 확인")).inRoot(isDialog()).perform(click())
                detailActivity = instrumentation.waitForMonitorWithTimeout(monitor, 10000)
                assertNotNull(detailActivity)
                instrumentation.removeMonitor(monitor)
                waitFor("Loader must see both audit cards") {
                    var ready = false
                    instrumentation.runOnMainSync { ready = descendants(detailActivity!!.window.decorView).count { it is Button && it.text == "알림 상세 및 조치" } >= 2 }
                    ready
                }
                screenshot("ui45_loader_list.png")
                instrumentation.runOnMainSync {
                    (descendants(detailActivity!!.window.decorView).first { it is Button && it.text == "알림 상세 및 조치" } as Button).performClick()
                }
                onView(withText("조치 완료·재검수 요청")).inRoot(isDialog()).perform(click())
                waitFor("Loader response must be shared with QA") {
                    live.any { Tasks.await(collection.document(it.id).get(Source.SERVER)).getString("status") == "REVIEW_REQUESTED" }
                }
                instrumentation.runOnMainSync { detailActivity?.finish();detailActivity = null }
            }
            auth.signOut();login(auth, "QA")
            ActivityScenario.launch<AnomalyInventory>(Intent(context, AnomalyInventory::class.java)).use { qaScreen ->
                waitFor("QA must receive the loader's review request") {
                    var ready = false
                    qaScreen.onActivity { ready = descendants(it.window.decorView).filterIsInstance<TextView>().any { v -> v.text.contains("실사 오적재 · 재검수 대기") } }
                    ready
                }
                screenshot("ui45_qa_review.png")
                qaScreen.onActivity { activity ->
                    val container = activity.findViewById<LinearLayout>(R.id.rj0lzllza8c)
                    (descendants(container.getChildAt(0)).first { it is Button && it.text == "알림 상세 및 조치" } as Button).performClick()
                }
                onView(withText("재검수 완료")).inRoot(isDialog()).perform(click())
                val live = planAuditMisplacementAlerts(finding.copy(jobId = liveJob), qa)
                waitFor("QA approval must close the reviewed finding") {
                    live.any { Tasks.await(collection.document(it.id).get(Source.SERVER)).getString("status") == "RESOLVED" }
                }
            }
            val afterStock = Tasks.await(db.collection("inventory").whereEqualTo("companyCode", company).get(Source.SERVER), 20, TimeUnit.SECONDS)
                .documents.associate { it.id to it.data }
            assertEquals(beforeStock, afterStock)
            for (doc in Tasks.await(collection.whereEqualTo("companyCode", company).get(Source.SERVER)).documents) {
                if (doc.getString("status") != "RESOLVED") Tasks.await(doc.reference.update(mapOf(
                    "status" to "RESOLVED", "updatedAt" to FieldValue.serverTimestamp())))
            }
        } finally {
            instrumentation.runOnMainSync { detailActivity?.finish() }
            senderApp?.let { FirebaseAuth.getInstance(it).signOut();it.delete() }
            auth.signOut()
            context.getSharedPreferences("app_user_session", Context.MODE_PRIVATE).edit().clear().apply()
        }
    }

    @Test fun b_selectionMenuShowsOutlinedChoicesAndReturnsTheChosenValue() {
        CapturedPhotoHolder.getAndClear()
        ActivityScenario.launch<AiAuditResults>(Intent(context, AiAuditResults::class.java)).use { scenario ->
            var selected = -1
            scenario.onActivity { activity ->
                WarehouseMenus.show(activity, "창고 선택", listOf("전체 창고", "제1 물류센터", "제2 물류센터"), 0) { selected = it }
            }
            onView(withText("전체 창고")).inRoot(isDialog()).check(matches(isDisplayed()))
            screenshot("ui45_dropdown.png")
            onView(withText("제2 물류센터")).inRoot(isDialog()).perform(click())
            assertEquals(2, selected)
        }
    }
}
