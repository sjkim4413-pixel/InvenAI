package com.example.myapp

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WarehouseScreenTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val inventoryId = "E2E-TEST_001234"
    private val product = "검증 상품"

    @Test fun workScreensUpdateStockAndShowBothHistoryTabs() {
        val auth = FirebaseAuth.getInstance()
        auth.useEmulator("10.0.2.2", 18762)
        val firestore = FirebaseFirestore.getInstance()
        firestore.useEmulator("10.0.2.2", 18761)
        val credential = Tasks.await(
            auth.signInWithEmailAndPassword("warehouse-e2e-qa@example.test", "local-emulator-test"),
            15, TimeUnit.SECONDS
        )
        val uid = requireNotNull(credential.user).uid
        val session = context.getSharedPreferences("app_user_session", Context.MODE_PRIVATE)
        session.edit().putString("COMPANY_CODE", "E2E-TEST").putString("USER_ID", "qa-fixture")
            .putString("ROLE", "QA").putString("FIREBASE_UID", uid).commit()
        val stock = firestore.collection("inventory").document(inventoryId)
        try {
            editStock(Inbound::class.java, "입고 수량", "4", "입고 저장")
            eventually { check(Tasks.await(stock.get(Source.SERVER)).getLong("quantity") == 14L) }

            editStock(Transfer::class.java, "목적지 (예: B구역 R07-1층)", "R07-1F", "이동 저장")
            eventually { check(Tasks.await(stock.get(Source.SERVER)).getString("location") == "R07-1F") }

            editStock(Outbound::class.java, "출고 수량", "2", "출고 저장")
            eventually { check(Tasks.await(stock.get(Source.SERVER)).getLong("quantity") == 12L) }

            ActivityScenario.launch<InventoryDetail>(openStock(InventoryDetail::class.java)).use {
                eventually { onView(withText(product)).check(matches(isDisplayed())) }
                onView(withText("오적재 조치 요청")).perform(scrollTo(), click())
                onView(withText("요청")).perform(click())
                eventually { check(Tasks.await(stock.get(Source.SERVER)).getString("status") == "ANOMALY") }
            }

            ActivityScenario.launch(WorkHistory::class.java).use {
                eventually { onView(withText("작업 이력 3건")).check(matches(isDisplayed())) }
                onView(withText("이동 · $product")).perform(scrollTo()).check(matches(isDisplayed()))
                onView(withText("이상 이력 1건")).perform(click())
                onView(withText("이상 조치 요청 · $product")).check(matches(isDisplayed()))
            }

            ActivityScenario.launch(InventoryList::class.java).use {
                eventually { onView(withText("$product · 001234")).check(matches(isDisplayed())) }
                onView(withHint("상품명, 코드, 랙 위치 검색"))
                    .perform(replaceText("등록 안 된 상품"), closeSoftKeyboard())
                onView(withText("조건에 맞는 재고가 없습니다.")).check(matches(isDisplayed()))
            }

            ActivityScenario.launch(HomeDashboard::class.java).use {
                eventually { onView(withId(R.id.r8y8i9t5s33h)).check(matches(withText("12개"))) }
                eventually {
                    onView(withText("이상 조치 요청 · $product")).perform(scrollTo()).check(matches(isDisplayed()))
                }
            }

            ActivityScenario.launch(ErpWmsConnection::class.java).use {
                eventually { onView(withText(org.hamcrest.CoreMatchers.containsString("연결됨 · 재고 위치 1건")))
                    .check(matches(isDisplayed())) }
            }

            ActivityScenario.launch(AuditStart::class.java).use {
                eventually { onView(withText("Test WH")).check(matches(isDisplayed())) }
            }

            ActivityScenario.launch(FileUpload::class.java).use {
                eventually { onView(withText("전산 재고 1건을 확인했습니다.")).check(matches(isDisplayed())) }
                onView(withText("선택한 재고 반영")).check(matches(org.hamcrest.CoreMatchers.not(isEnabled())))
            }

            context.getSharedPreferences("app_user_session", Context.MODE_PRIVATE).edit()
                .putString("ROLE", "LOADER").commit()
            ActivityScenario.launch(LoaderHomeDashboard::class.java).use {
                eventually { onView(withText("오적재 조치 요청 1건")).check(matches(isDisplayed())) }
            }
        } finally {
            session.edit().clear().commit()
            auth.signOut()
        }
    }

    private fun editStock(screen: Class<*>, field: String, value: String, submit: String) {
        ActivityScenario.launch<StockOperationScreen>(openStock(screen)).use {
            eventually { onView(withText(product)).check(matches(isDisplayed())) }
            onView(withHint(field)).perform(scrollTo(), replaceText(value), closeSoftKeyboard())
            onView(withText(submit)).perform(scrollTo(), click())
            onView(withText("저장")).perform(click())
            eventually { check(it.state == androidx.lifecycle.Lifecycle.State.DESTROYED) }
        }
    }

    private fun openStock(screen: Class<*>): Intent =
        Intent(context, screen).putExtra("INVENTORY_ID", inventoryId)

    private fun eventually(assert: () -> Unit) {
        val end = android.os.SystemClock.elapsedRealtime() + 15_000
        while (true) {
            try {
                assert()
                return
            } catch (failure: Throwable) {
                if (android.os.SystemClock.elapsedRealtime() >= end) throw failure
                android.os.SystemClock.sleep(200)
            }
        }
    }
}
