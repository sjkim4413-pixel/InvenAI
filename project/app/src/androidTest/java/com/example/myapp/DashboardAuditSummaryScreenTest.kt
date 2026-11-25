package com.example.myapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.gson.Gson
import org.hamcrest.CoreMatchers.equalTo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.NumberFormat
import java.util.concurrent.TimeUnit

/** Reproduces the missing-home-counter bug with real local Firestore listeners. */
@RunWith(AndroidJUnit4::class)
class DashboardAuditSummaryScreenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val password = "Ui45LocalOnly!2026"
    private fun waitFor(message: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 25000
        while (SystemClock.elapsedRealtime() < end) { if (condition()) return;SystemClock.sleep(150) }
        fail(message)
    }
    private fun count(value: Long) = "${NumberFormat.getIntegerInstance().format(value)}개"
    private fun capture(file: String) {
        instrumentation.waitForIdleSync()
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), file).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test fun homeTracksNewDuplicatePendingAndResolvedFindingsWithoutChangingAnyStock() {
        val auth = FirebaseAuth.getInstance().apply { useEmulator("10.0.2.2", 18862) }
        val db = FirebaseFirestore.getInstance().apply { useEmulator("10.0.2.2", 18861) }
        check(db.firestoreSettings.host == "10.0.2.2:18861")
        // The local emulator fixture can be reset between instrumentation runs.
        Tasks.await(db.clearPersistence(), 10, TimeUnit.SECONDS)
        var sender: FirebaseApp? = null
        var detail: Activity? = null
        try {
            Tasks.await(auth.signInWithEmailAndPassword("qa-ui45@invenai.test", password), 20, TimeUnit.SECONDS)
            val uid = auth.currentUser!!.uid
            context.getSharedPreferences("app_user_session", Context.MODE_PRIVATE).edit()
                .putString("FIREBASE_UID", uid).putString("COMPANY_CODE", "ABC-002")
                .putString("ROLE", "QA").putString("USER_ID", "dashboard-test").apply()
            val collection = db.collection(AuditAlertManager.COLLECTION)
            for (doc in Tasks.await(collection.whereEqualTo("companyCode", "ABC-002").get(Source.SERVER), 20, TimeUnit.SECONDS).documents) {
                if (doc.getString("status") != "RESOLVED") Tasks.await(doc.reference.update(mapOf(
                    "status" to "RESOLVED", "updatedAt" to FieldValue.serverTimestamp())))
            }
            val snapshots = Tasks.await(db.collection("inventory").whereEqualTo("companyCode", "ABC-002").get(Source.SERVER))
            val before = snapshots.documents.associate { it.id to it.data }
            val items = snapshots.documents.map(InventoryManager::readItem)
            val finding = instrumentation.context.assets.open("ui45/pair_03_misplaced.json").bufferedReader().use {
                Gson().fromJson(it, F03AnalyzeAndCompareResponse::class.java)
            }.copy(jobId = java.util.UUID.randomUUID().toString().replace("-", ""))
            val alerts = planAuditMisplacementAlerts(finding, uid)
            assertEquals(2, alerts.size)
            val affected = items.filter { item -> alerts.any { it.inventoryId == item.documentId } }
            assertTrue(affected.all { it.status == "NORMAL" })
            val quantity = affected.sumOf { it.quantity.toLong() }
            val total = items.sumOf { it.quantity.toLong() }
            val normal = items.filter { it.status in setOf("NORMAL", "정상") }.sumOf { it.quantity.toLong() }
            val review = items.filter { it.needsReview }.sumOf { it.quantity.toLong() }
            val selectedWarehouse = alerts.first().warehouse
            val scoped = items.filter { it.warehouse == selectedWarehouse }
            val scopedReview = scoped.filter { it.needsReview }.sumOf { it.quantity.toLong() }
            sender = FirebaseApp.initializeApp(context, FirebaseApp.getInstance().options, "dashboard-summary-writer")!!
            val writerAuth = FirebaseAuth.getInstance(sender!!).apply { useEmulator("10.0.2.2", 18862) }
            val writer = FirebaseFirestore.getInstance(sender!!).apply { useEmulator("10.0.2.2", 18861) }
            check(writer.firestoreSettings.host == "10.0.2.2:18861")
            Tasks.await(writerAuth.signInWithEmailAndPassword("qa-ui45@invenai.test", password), 20, TimeUnit.SECONDS)
            ActivityScenario.launch<HomeDashboard>(Intent(context, HomeDashboard::class.java)).use { home ->
                fun shown(id: Int): String { var text = "";home.onActivity { text = it.findViewById<TextView>(id).text.toString() };return text }
                waitFor("Initial authoritative counters") {
                    shown(R.id.r8y8i9t5s33h) == count(total) && shown(R.id.rs6kkinbc3pk) == count(review)
                }
                capture("dashboard46_before.png")
                for (alert in alerts) Tasks.await(writer.collection(AuditAlertManager.COLLECTION).document(alert.id)
                    .set(AuditAlertManager.documentFields(alert)), 20, TimeUnit.SECONDS)
                waitFor("New audit findings must immediately change the home counters") {
                    shown(R.id.rs6kkinbc3pk) == count(review + quantity) && shown(R.id.rhfbnn8zu2se) == count(normal - quantity)
                }
                assertEquals(count(total), shown(R.id.r8y8i9t5s33h))
                capture("dashboard46_active.png")
                val repeated = planAuditMisplacementAlerts(finding.copy(jobId = java.util.UUID.randomUUID().toString().replace("-", "")), uid).first()
                Tasks.await(writer.collection(AuditAlertManager.COLLECTION).document(repeated.id).set(AuditAlertManager.documentFields(repeated)))
                waitFor("Three findings must still count the same two inventory positions once") {
                    shown(R.id.homeAnomalySummary).contains("실사 알림 3건")
                }
                assertEquals(count(review + quantity), shown(R.id.rs6kkinbc3pk))
                onView(withId(R.id.r5cnj6sku8uh)).perform(click())
                onData(equalTo(selectedWarehouse)).perform(click())
                waitFor("Selected warehouse is applied to both inventory and findings") {
                    shown(R.id.r5cnj6sku8uh) == selectedWarehouse && shown(R.id.r8y8i9t5s33h) == count(scoped.sumOf { it.quantity.toLong() }) &&
                        shown(R.id.rs6kkinbc3pk) == count(scopedReview + quantity)
                }
                capture("dashboard46_warehouse.png")
                val monitor = instrumentation.addMonitor(AnomalyInventory::class.java.name, null, false)
                onView(withId(R.id.r0da5dco3z0cj)).perform(click())
                detail = instrumentation.waitForMonitorWithTimeout(monitor, 10000)
                instrumentation.removeMonitor(monitor)
                assertNotNull(detail)
                assertEquals(selectedWarehouse, detail!!.intent.getStringExtra("WAREHOUSE_CODE"))
                waitFor("Scoped anomaly list") {
                    var present = false
                    instrumentation.runOnMainSync {
                        val container = detail!!.findViewById<ViewGroup>(R.id.rj0lzllza8c)
                        present = (0 until container.childCount).any { (container.getChildAt(it) as? TextView)?.text == "창고 · $selectedWarehouse" }
                    }
                    present
                }
                instrumentation.runOnMainSync { detail?.finish();detail = null }
                val qa = writerAuth.currentUser!!.uid
                Tasks.await(writerAuth.signInWithEmailAndPassword("loader-ui45@invenai.test", password), 20, TimeUnit.SECONDS)
                Tasks.await(writer.collection(AuditAlertManager.COLLECTION).document(alerts[1].id).update(mapOf(
                    "status" to "REVIEW_REQUESTED", "responderUid" to writerAuth.currentUser!!.uid,
                    "updatedAt" to FieldValue.serverTimestamp())))
                waitFor("Home must reload on returning from the list") { shown(R.id.rs6kkinbc3pk) == count(scopedReview + quantity) }
                // Keep pending review in the count, and keep one position counted while another job is still open.
                Tasks.await(writerAuth.signInWithEmailAndPassword("qa-ui45@invenai.test", password), 20, TimeUnit.SECONDS)
                assertEquals(qa, writerAuth.currentUser!!.uid)
                Tasks.await(writer.collection(AuditAlertManager.COLLECTION).document(alerts[0].id).update(mapOf(
                    "status" to "RESOLVED", "updatedAt" to FieldValue.serverTimestamp())))
                waitFor("Partial resolution still leaves the duplicate and pending review") {
                    shown(R.id.homeAnomalySummary).contains("실사 알림 2건")
                }
                assertEquals(count(scopedReview + quantity), shown(R.id.rs6kkinbc3pk))
                for (alert in listOf(alerts[1], repeated)) Tasks.await(writer.collection(AuditAlertManager.COLLECTION).document(alert.id)
                    .update(mapOf("status" to "RESOLVED", "updatedAt" to FieldValue.serverTimestamp())))
                waitFor("QA completion must clear the audit overlay") { shown(R.id.rs6kkinbc3pk) == count(scopedReview) }
                capture("dashboard46_resolved.png")
            }
            val after = Tasks.await(db.collection("inventory").whereEqualTo("companyCode", "ABC-002").get(Source.SERVER))
                .documents.associate { it.id to it.data }
            assertEquals(before, after)
        } finally {
            instrumentation.runOnMainSync { detail?.finish() }
            sender?.let { FirebaseAuth.getInstance(it).signOut();it.delete() }
            auth.signOut()
            context.getSharedPreferences("app_user_session", Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
