package com.example.myapp

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real Firestore transactions plus actual home/history screens. All writes are loopback-only. */
@RunWith(AndroidJUnit4::class)
class WarehouseHistoryFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val company = "HISTORY50"
    private val password = "Approval49LocalOnly!"
    private fun waitFor(message: String, condition: () -> Boolean) {
        val end=SystemClock.elapsedRealtime()+25000
        while(SystemClock.elapsedRealtime()<end) { if(condition())return;SystemClock.sleep(100) }
        fail(message)
    }
    private fun login(auth: FirebaseAuth, role: String) {
        auth.signOut()
        Tasks.await(auth.signInWithEmailAndPassword(if(role=="QA") "qa-history50@approval49.test" else "loader-history50@approval49.test",password),20,TimeUnit.SECONDS)
        context.getSharedPreferences("app_user_session",Context.MODE_PRIVATE).edit()
            .putString("FIREBASE_UID",auth.currentUser!!.uid).putString("COMPANY_CODE",company)
            .putString("USER_ID","history50-$role").putString("ROLE",role).commit()
    }
    private fun complete(action: (() -> Unit,(String) -> Unit) -> Unit) {
        val done=CountDownLatch(1);var error:String?=null
        instrumentation.runOnMainSync { action({done.countDown()},{error=it;done.countDown()}) }
        assertTrue("Operation callback",done.await(25,TimeUnit.SECONDS));assertNull(error)
    }
    private fun texts(root: View): List<String> = (if(root is TextView)listOf(root.text.toString()) else emptyList())+
        if(root is ViewGroup)(0 until root.childCount).flatMap { texts(root.getChildAt(it)) } else emptyList()
    private fun views(root: View): List<View> = listOf(root)+
        if(root is ViewGroup)(0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()

    @Test fun auditAndLegacyRelocationAppearInRecentWorkAnomalyAndResolvedLogs() {
        val auth=FirebaseAuth.getInstance().apply { useEmulator("10.0.2.2",18862) }
        val db=FirebaseFirestore.getInstance().apply { useEmulator("10.0.2.2",18861) }
        check(db.firestoreSettings.host=="10.0.2.2:18861")
        Tasks.await(db.clearPersistence(),10,TimeUnit.SECONDS)
        login(auth,"QA")
        val qa=auth.currentUser!!.uid
        val stockRef=db.collection("inventory").document("HISTORY50_EXPECTED")
        val before=Tasks.await(stockRef.get(Source.SERVER)).data
        val alert=AuditMisplacementAlert("AI_"+UUID.randomUUID().toString().replace("-","").repeat(2),company,qa,
            UUID.randomUUID().toString().replace("-",""),stockRef.id,"H50 warehouse","A01-1F","EXPECTED","OBSERVED",
            "Expected","Observed","A02-1F",true)
        val ref=db.collection(AuditAlertManager.COLLECTION).document(alert.id)
        fun current()=requireNotNull(AuditAlertManager.read(Tasks.await(ref.get(Source.SERVER))))
        fun trail()=Tasks.await(AuditHistory.events(company).whereEqualTo("alertId",alert.id).get(Source.SERVER)).documents
        fun change(status: String,note: String) {
            val latest=current()
            complete { success,failure -> AuditAlertManager.changeStatus(context,latest,status,note,success,failure) }
        }
        // Send twice: server history and merged UI must both show only one detection.
        instrumentation.runOnMainSync { AuditAlertManager.send(context,listOf(alert));AuditAlertManager.send(context,listOf(alert)) }
        waitFor("Finding and history saved") { Tasks.await(ref.get(Source.SERVER)).exists() && trail().size==1 }
        ActivityScenario.launch<HomeDashboard>(Intent(context,HomeDashboard::class.java)).use { home ->
            waitFor("Home receives audit detection") {
                var ok=false;home.onActivity { ok=texts(it.findViewById(R.id.llHomeRecentLogsContainer)).any { t->t.contains("실사 오적재 발견")&&t.contains("Observed") } };ok
            }
        }
        assertEquals(1,trail().size)
        login(auth,"LOADER")
        val loader=auth.currentUser!!.uid
        change("REVIEW_REQUESTED","Moved first")
        ActivityScenario.launch<WorkHistory>(Intent(context,WorkHistory::class.java)).use { history ->
            waitFor("Loader work log contains physical relocation report") {
                var ok=false;history.onActivity { activity ->
                    val all=texts(activity.findViewById(android.R.id.content))
                    ok=all.any { it.contains("재적재 회신·재검수 요청") } && all.any { it.contains("A01-1F → A02-1F") }
                };ok
            }
            history.onActivity { activity ->
                views(activity.findViewById(android.R.id.content)).filterIsInstance<Button>().first { it.text.startsWith("이상 이력") }.performClick()
            }
            waitFor("Anomaly log includes detection and reported move") {
                var ok=false;history.onActivity {
                    val all=texts(it.findViewById(android.R.id.content))
                    ok=all.any { t->t.contains("실사 오적재 발견") } && all.any { t->t.contains("재적재 회신·재검수 요청") }
                };ok
            }
        }
        login(auth,"QA");change("OPEN","Move again")
        login(auth,"LOADER");change("REVIEW_REQUESTED","Moved second")
        login(auth,"QA");change("RESOLVED","Verified")
        val saved=trail()
        assertEquals(5,saved.size)
        assertEquals(2,saved.count { it.getString("type")=="AUDIT_RELOCATION"&&it.getString("actorUid")==loader })
        assertEquals(1,saved.count { it.getString("type")=="AUDIT_APPROVAL"&&it.getString("actorUid")==qa })
        assertEquals(before,Tasks.await(stockRef.get(Source.SERVER)).data)
        // Existing alerts without history remain visible, with unknown final reviewer left unknown.
        val old=alert.copy(id="AI_"+UUID.randomUUID().toString().replace("-","").repeat(2),jobId=UUID.randomUUID().toString().replace("-",""))
        val oldRef=db.collection(AuditAlertManager.COLLECTION).document(old.id)
        Tasks.await(oldRef.set(AuditAlertManager.documentFields(old)))
        Tasks.await(oldRef.update(mapOf("status" to "RESOLVED","updatedAt" to FieldValue.serverTimestamp())))
        val observed=AtomicReference<List<WarehouseEvent>>(emptyList())
        val problem=AtomicReference<String?>(null)
        var subscription:com.google.firebase.firestore.ListenerRegistration?=null
        instrumentation.runOnMainSync { subscription=WarehouseHistory.listen(context,onError={problem.set(it)}) { observed.set(it) } }
        try {
            waitFor("Combined listener returns new and legacy events") {
                observed.get().count { it.alertId==alert.id }==5&&observed.get().count { it.alertId==old.id }==2
            }
            assertNull(problem.get())
            assertFalse(observed.get().filter { it.alertId==alert.id }.any { it.inferred })
            assertEquals("",observed.get().single { it.alertId==old.id&&it.isResolved }.actorUid)
        } finally { instrumentation.runOnMainSync { subscription?.remove() } }

        val legacyRef=db.collection("inventory").document("HISTORY50_LEGACY")
        var legacy=InventoryManager.readItem(Tasks.await(legacyRef.get(Source.SERVER)))
        complete { success,failure -> AnomalyManager.sendMisplacementAlert(context,legacy,"B02-1F",success,failure) }
        login(auth,"LOADER")
        legacy=InventoryManager.readItem(Tasks.await(legacyRef.get(Source.SERVER)))
        complete { success,failure -> InventoryManager.requestReview(context,legacy,"B02-1F",11,success,failure) }
        val pending=Tasks.await(legacyRef.get(Source.SERVER))
        assertEquals("B01-1F",pending.getString("location"))
        assertEquals(12L,pending.getLong("quantity"))
        val pendingEvent=Tasks.await(db.collection("stockHistory").document(company).collection("events")
            .document(pending.getString("lastEventId")!!).get(Source.SERVER))
        assertEquals("B01-1F",pendingEvent.getString("fromLocation"))
        assertEquals("B02-1F",pendingEvent.getString("toLocation"))
        assertEquals(11L,pendingEvent.getLong("afterQuantity"))
        login(auth,"QA")
        legacy=InventoryManager.readItem(pending)
        complete { success,failure -> AnomalyManager.finishReview(context,legacy,true,success,failure) }
        val confirmed=Tasks.await(legacyRef.get(Source.SERVER))
        assertEquals("B02-1F",confirmed.getString("location"))
        assertEquals(11L,confirmed.getLong("quantity"))
        ActivityScenario.launch<WorkHistory>(Intent(context,WorkHistory::class.java).putExtra("ANOMALIES",true).putExtra("RESOLVED",true)).use { history ->
            waitFor("Resolved log includes both audit and stock-review approvals") {
                var ok=false;history.onActivity {
                    val all=texts(it.findViewById(android.R.id.content))
                    ok=all.any { t->t.contains("실사 재검수 승인") }&&all.any { t->t.contains("검수 승인 · Legacy") }
                };ok
            }
            history.onActivity { activity ->
                views(activity.findViewById(android.R.id.content)).filterIsInstance<Button>().first { it.text.startsWith("작업 이력") }.performClick()
            }
            waitFor("Work log includes confirmed stock move and its pending report") {
                var ok=false;history.onActivity {
                    val all=texts(it.findViewById(android.R.id.content))
                    ok=all.any { t->t.contains("검수 승인 · Legacy") }&&all.any { t->t.contains("재검수 요청 · Legacy") }
                };ok
            }
        }
        ActivityScenario.launch<HomeDashboard>(Intent(context,HomeDashboard::class.java)).use { home ->
            waitFor("Recent home log reflects approved move") {
                var ok=false;home.onActivity { ok=texts(it.findViewById(R.id.llHomeRecentLogsContainer)).any { t->t.contains("검수 승인 · Legacy") } };ok
            }
        }
        auth.signOut()
        context.getSharedPreferences("app_user_session",Context.MODE_PRIVATE).edit().clear().commit()
    }
}
