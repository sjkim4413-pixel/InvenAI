package com.example.myapp

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import okhttp3.Request
import okio.Timeout
import org.hamcrest.CoreMatchers.equalTo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit

/** Local Firebase only. The analysis API below is a recording fake and never sends HTTP. */
@RunWith(AndroidJUnit4::class)
class AuditWarehouseSelectionScreenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun waitFor(message: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 25000
        while (SystemClock.elapsedRealtime() < end) { if (condition()) return;SystemClock.sleep(150) }
        fail(message)
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        repeat(3) {
            val shot = instrumentation.uiAutomation.takeScreenshot()
            if (shot != null) {
                File(context.getExternalFilesDir(null), name).outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                shot.recycle()
                return
            }
            SystemClock.sleep(200)
        }
        println("Screenshot unavailable: $name")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> reply(body: T): Call<T> = Proxy.newProxyInstance(Call::class.java.classLoader, arrayOf(Call::class.java)) { call, method, args ->
        when (method.name) {
            "enqueue" -> { (args!![0] as Callback<T>).onResponse(call as Call<T>, Response.success(body));null }
            "cancel" -> null
            "isCanceled", "isExecuted" -> false
            "timeout" -> Timeout.NONE
            "request" -> Request.Builder().url("http://127.0.0.1/unused-local-test").build()
            "clone" -> call
            else -> error("Unexpected call: ${method.name}")
        }
    } as Call<T>

    private fun verifyAnalysisRequest(warehouse: String) {
        var uploads = 0
        var requested: String? = null
        var requestedMode: String? = null
        var returned = warehouse
        val service = Proxy.newProxyInstance(F03ApiService::class.java.classLoader, arrayOf(F03ApiService::class.java)) { _, method, args ->
            when (method.name) {
                "uploadImage" -> { uploads++;reply(F03ImageUploadResponse("uploaded", "test.jpg", "", "ready")) }
                "analyzeAndCompare" -> {
                    requested = args!![2] as? String
                    requestedMode = args[3] as? String
                    reply(F03AnalyzeAndCompareResponse("compared", "TEST", 0, emptyList(), warehouseCode = returned, erpWarehouse = returned))
                }
                else -> error("Unexpected service request: ${method.name}")
            }
        } as F03ApiService
        fun open(selected: String?, expected: String?): AuditResultState? {
            val store = ViewModelStore()
            val model = AuditResultViewModel(context.applicationContext as Application)
            store.put("recorded-analysis", model)
            AuditResultViewModel::class.java.getDeclaredField("api\$delegate").apply { isAccessible = true }.set(model, lazy { service })
            return try {
                model.open(byteArrayOf(1), null, selected, testMode = "test_reference_pairs", testWarehouse = expected)
                model.state.value
            } finally { store.clear() }
        }
        instrumentation.runOnMainSync {
            assertTrue(open(null, warehouse) is AuditResultState.Failure)
            assertTrue(open("Wrong warehouse", warehouse) is AuditResultState.Failure)
            assertEquals(0, uploads)
            assertTrue(open(warehouse, warehouse) is AuditResultState.Ready)
            assertEquals(1, uploads)
            assertEquals(warehouse, requested)
            assertEquals("test_reference_pairs", requestedMode)
            returned = "Wrong response warehouse"
            assertTrue(open(warehouse, warehouse) is AuditResultState.Failure)
            assertEquals(warehouse, requested)
        }
    }

    @Test fun warehouseIsRequiredAndOnlyItsPhotosCanReachPreviewAndAnalysis() {
        val auth = FirebaseAuth.getInstance().apply { useEmulator("10.0.2.2", 18862) }
        val db = FirebaseFirestore.getInstance().apply { useEmulator("10.0.2.2", 18861) }
        check(db.firestoreSettings.host == "10.0.2.2:18861")
        Tasks.await(db.clearPersistence(), 10, TimeUnit.SECONDS)
        var camera: Activity? = null
        var preview: Activity? = null
        try {
            Tasks.await(auth.signInWithEmailAndPassword("qa-ui45@invenai.test", "Ui45LocalOnly!2026"), 20, TimeUnit.SECONDS)
            context.getSharedPreferences("app_user_session", Context.MODE_PRIVATE).edit()
                .putString("FIREBASE_UID", auth.currentUser!!.uid).putString("COMPANY_CODE", "ABC-002")
                .putString("ROLE", "QA").putString("USER_ID", "warehouse-test").apply()
            val documents = Tasks.await(db.collection("inventory").whereEqualTo("companyCode", "ABC-002").get(Source.SERVER), 20, TimeUnit.SECONDS)
            val before = documents.documents.associate { it.id to it.data }
            val images = loadAuditTestImages(context)
            val choices = auditWarehouseChoices(documents.documents.map(InventoryManager::readItem), images)
            assertEquals(4, choices.size)
            assertFalse("AI 상품·위치 비교 테스트 창고" in choices)
            assertFalse("AI 이미지 테스트 창고 04" in choices)
            val warehouse = "AI 이미지 테스트 창고 01"
            val compatible = auditImagesForWarehouse(images, warehouse)
            assertEquals(2, compatible.size)
            ActivityScenario.launch<AuditStart>(Intent(context, AuditStart::class.java).putExtra("WAREHOUSE_CODE", "AI 이미지 테스트 창고 04")).use { screen ->
                waitFor("Company warehouse list must load") {
                    var ready = false;screen.onActivity { ready = it.findViewById<View>(R.id.auditSelectWarehouse).isEnabled };ready
                }
                screen.onActivity {
                    assertFalse(it.findViewById<View>(R.id.auditOpenCamera).isEnabled)
                    assertEquals("실사 창고 선택", it.findViewById<TextView>(R.id.auditSelectWarehouse).text.toString())
                }
                onView(withId(R.id.auditSelectWarehouse)).perform(click())
                onView(withText("분석에서 결정")).check(doesNotExist())
                onView(withText("AI 상품·위치 비교 테스트 창고")).check(doesNotExist())
                onView(withText("AI 이미지 테스트 창고 04")).check(doesNotExist())
                capture("warehouse47_choices.png")
                onData(equalTo(warehouse)).perform(click())
                screen.onActivity {
                    assertTrue(it.findViewById<View>(R.id.auditOpenCamera).isEnabled)
                    assertTrue(it.findViewById<TextView>(R.id.auditWarehouseStatus).text.contains("테스트 이미지 2장"))
                }
                screen.recreate()
                waitFor("Selected warehouse survives recreation") {
                    var ready = false;screen.onActivity { ready = it.findViewById<View>(R.id.auditOpenCamera).isEnabled };ready
                }
                val cameraMonitor = instrumentation.addMonitor(AiAuditCamera::class.java.name, null, false)
                onView(withId(R.id.auditOpenCamera)).perform(click())
                camera = instrumentation.waitForMonitorWithTimeout(cameraMonitor, 10000)
                instrumentation.removeMonitor(cameraMonitor)
                assertNotNull(camera)
                assertEquals(warehouse, camera!!.intent.getStringExtra("WAREHOUSE_CODE"))
                waitFor("Camera validates the same company warehouse") {
                    var ready = false;instrumentation.runOnMainSync { ready = camera!!.findViewById<View>(R.id.btnSelectTestImage).isEnabled };ready
                }
                instrumentation.runOnMainSync { camera!!.findViewById<View>(R.id.btnSelectTestImage).performClick() }
                for (entry in compatible) onView(withText(entry.title)).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(withText(images.first { it.warehouseCode != warehouse }.title)).check(doesNotExist())
                capture("warehouse47_photo_picker.png")
                val previewMonitor = instrumentation.addMonitor(PhotoConfirmation::class.java.name, null, false)
                onData(equalTo(compatible.last().title)).perform(click())
                preview = instrumentation.waitForMonitorWithTimeout(previewMonitor, 15000)
                instrumentation.removeMonitor(previewMonitor)
                assertNotNull(preview)
                assertEquals(warehouse, preview!!.intent.getStringExtra("WAREHOUSE_CODE"))
                assertEquals(warehouse, preview!!.intent.getStringExtra(EXTRA_AUDIT_TEST_WAREHOUSE))
                assertEquals("test_reference_pairs", preview!!.intent.getStringExtra(EXTRA_AUDIT_TEST_MODE))
                assertNotNull(preview!!.findViewById<Button>(R.id.auditAnalyzePhoto))
                capture("warehouse47_preview.png")
                instrumentation.runOnMainSync { preview?.finish();preview = null;camera = null }
            }
            verifyAnalysisRequest(warehouse)
            for (selected in listOf<String?>(null, "Wrong warehouse")) {
                CapturedPhotoHolder.photoBytes = byteArrayOf(1)
                ActivityScenario.launch<PhotoConfirmation>(Intent(context, PhotoConfirmation::class.java)
                    .putExtra("WAREHOUSE_CODE", selected).putExtra(EXTRA_AUDIT_TEST_MODE, "test_reference_pairs")
                    .putExtra(EXTRA_AUDIT_TEST_WAREHOUSE, warehouse)).use { screen ->
                    screen.onActivity { assertNull(it.findViewById<View>(R.id.auditAnalyzePhoto)) }
                }
            }
            val after = Tasks.await(db.collection("inventory").whereEqualTo("companyCode", "ABC-002").get(Source.SERVER), 20, TimeUnit.SECONDS)
                .documents.associate { it.id to it.data }
            assertEquals(before, after)
        } finally {
            instrumentation.runOnMainSync { preview?.finish();camera?.finish() }
            CapturedPhotoHolder.getAndClear()
            auth.signOut()
            context.getSharedPreferences("app_user_session", Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
