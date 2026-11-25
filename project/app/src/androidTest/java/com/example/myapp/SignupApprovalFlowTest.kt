package com.example.myapp

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SignupApprovalFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val password = "Approval49LocalOnly!"
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 25000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(150)
        }
        fail(message)
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
    @Test fun signupPendingAdminApprovalAndRejectionResubmission() {
        val auth = FirebaseAuth.getInstance().apply { useEmulator("10.0.2.2",18862) }
        val db = FirebaseFirestore.getInstance().apply { useEmulator("10.0.2.2",18861) }
        check(db.firestoreSettings.host == "10.0.2.2:18861")
        Tasks.await(db.clearPersistence(),10,TimeUnit.SECONDS)
        context.getSharedPreferences("app_user_session",Context.MODE_PRIVATE).edit().clear().commit()
        auth.signOut()
        val id="ui-signup-${System.currentTimeMillis()}"
        val applicantEmail="$id@warehouse.com"
        var applicantUid=""
        ActivityScenario.launch<Frame1>(Intent(context,Frame1::class.java)).use { scenario ->
            onView(withId(R.id.rini482f9v4)).perform(click())
            onView(withId(R.id.etSignupCompany)).perform(replaceText("APPROVAL49"),closeSoftKeyboard())
            onView(withId(R.id.etSignupId)).perform(replaceText(id),closeSoftKeyboard())
            onView(withId(R.id.etSignupPw)).perform(replaceText(password),closeSoftKeyboard())
            onView(withId(R.id.etSignupPwConfirm)).perform(replaceText(password),closeSoftKeyboard())
            onView(withId(R.id.btnSubmitSignup)).perform(scrollTo(),click())
            await("Signup saved and dialog dismissed") {
                var value="";scenario.onActivity { value=it.findViewById<EditText>(R.id.rtduz0dht8w).text.toString() }
                value==id && auth.currentUser==null
            }
            Tasks.await(auth.signInWithEmailAndPassword(applicantEmail,password),20,TimeUnit.SECONDS)
            applicantUid=auth.currentUser!!.uid
            val request=Tasks.await(db.collection(SignupApproval.REQUESTS).document(applicantUid).get(Source.SERVER))
            assertEquals("PENDING",request.getString("status"))
            assertEquals("LOADER",request.getString("requestedRole"))
            assertFalse(Tasks.await(db.collection(WarehouseMembership.COLLECTION).document(applicantUid).get(Source.SERVER)).exists())
            auth.signOut()
        }
        // Actual login routes an unapproved user to the status screen.
        ActivityScenario.launch<Frame1>(Intent(context,Frame1::class.java)).use {
            onView(withId(R.id.rmm3ibea01dh)).perform(replaceText("APPROVAL49"),closeSoftKeyboard())
            onView(withId(R.id.rtduz0dht8w)).perform(replaceText(id),closeSoftKeyboard())
            onView(withId(R.id.r5suocwfrhze)).perform(replaceText(password),closeSoftKeyboard())
            onView(withId(R.id.rlk8j1kj2j19)).perform(click())
            await("Unapproved login reaches pending screen") {
                try { onView(withId(R.id.approvalStatus)).check(androidx.test.espresso.assertion.ViewAssertions.matches(withText("관리자 승인 대기 중")));true }
                catch (_: AssertionError) { false } catch (_: androidx.test.espresso.NoMatchingViewException) { false }
            }
            capture("approval49-pending.png")
        }
        auth.signOut()
        // A real admin login permits an empty company code and routes to the admin dashboard.
        ActivityScenario.launch<Frame1>(Intent(context,Frame1::class.java)).use {
            onView(withId(R.id.rtduz0dht8w)).perform(replaceText("ui-admin@approval49.test"),closeSoftKeyboard())
            onView(withId(R.id.r5suocwfrhze)).perform(replaceText(password),closeSoftKeyboard())
            onView(withId(R.id.rlk8j1kj2j19)).perform(click())
            await("Admin dashboard loads pending requests") {
                try { onView(withId(R.id.adminRequestCount)).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()));true }
                catch (_: AssertionError) { false } catch (_: androidx.test.espresso.NoMatchingViewException) { false }
            }
        }
        // Use a fresh dashboard scenario for deterministic lifecycle assertions and UI approval.
        ActivityScenario.launch<AdminDashboard>(Intent(context,AdminDashboard::class.java)).use { admin ->
            await("Applicant card present") {
                var found=false;admin.onActivity { found=findText(it.findViewById(android.R.id.content),id) };found
            }
            capture("approval49-admin.png")
            // There are other adversarial fixtures; choose this applicant's own card action.
            admin.onActivity { activity ->
                val name=findTextView(activity.findViewById(android.R.id.content),id)!!
                (name.parent as android.view.ViewGroup).findViewById<android.view.View>(R.id.adminApprove).performClick()
            }
            onView(withId(R.id.adminAssignedCompany)).perform(replaceText("APPROVAL49-ASSIGNED"),closeSoftKeyboard())
            onView(withId(android.R.id.button1)).perform(click())
            await("Atomic membership and approval committed") {
                val r=Tasks.await(db.collection(SignupApproval.REQUESTS).document(applicantUid).get(Source.SERVER))
                r.getString("status")=="APPROVED"
            }
            val membership=Tasks.await(db.collection(WarehouseMembership.COLLECTION).document(applicantUid).get(Source.SERVER))
            assertEquals("APPROVAL49-ASSIGNED",membership.getString("companyCode"))
            assertEquals("LOADER",membership.getString("role"))
            assertEquals(auth.currentUser!!.uid,membership.getString("approvedBy"))
        }
        auth.signOut()
        Tasks.await(auth.signInWithEmailAndPassword("ui-rejected@approval49.test",password))
        val rejectedUid=auth.currentUser!!.uid
        Tasks.await(SignupApproval.submit("APPROVAL49","QA","ui-rejected"))
        val submitted=Tasks.await(db.collection(SignupApproval.REQUESTS).document(rejectedUid).get(Source.SERVER))
        auth.signOut()
        Tasks.await(auth.signInWithEmailAndPassword("ui-admin@approval49.test",password))
        Tasks.await(SignupApproval.review(rejectedUid,SignupApproval.revision(submitted),"","","회사 확인 필요"))
        auth.signOut()
        Tasks.await(auth.signInWithEmailAndPassword("ui-rejected@approval49.test",password))
        ActivityScenario.launch<PendingApproval>(Intent(context,PendingApproval::class.java)).use { pending ->
            await("Rejected screen shows reason") {
                var found=false;pending.onActivity { found=findText(it.findViewById(android.R.id.content),"반려 사유: 회사 확인 필요") };found
            }
            onView(withId(R.id.approvalCompany)).perform(replaceText("APPROVAL49-CORRECTED"),closeSoftKeyboard())
            onView(withId(R.id.approvalSubmit)).perform(scrollTo(),click())
            await("Rejected user can resubmit without granting self access") {
                Tasks.await(db.collection(SignupApproval.REQUESTS).document(rejectedUid).get(Source.SERVER)).getString("status")=="PENDING"
            }
            assertFalse(Tasks.await(db.collection(WarehouseMembership.COLLECTION).document(rejectedUid).get(Source.SERVER)).exists())
        }
        auth.signOut()
        ActivityScenario.launch<Frame1>(Intent(context,Frame1::class.java)).use {
            onView(withId(R.id.rmm3ibea01dh)).perform(replaceText("APPROVAL49-ASSIGNED"),closeSoftKeyboard())
            onView(withId(R.id.rtduz0dht8w)).perform(replaceText(id),closeSoftKeyboard())
            onView(withId(R.id.r5suocwfrhze)).perform(replaceText(password),closeSoftKeyboard())
            onView(withId(R.id.rlk8j1kj2j19)).perform(click())
            await("Approved applicant enters worker session") {
                val session=context.getSharedPreferences("app_user_session",Context.MODE_PRIVATE)
                session.getString("ROLE","")=="LOADER" && session.getString("COMPANY_CODE","")=="APPROVAL49-ASSIGNED"
            }
        }
        auth.signOut()
        context.getSharedPreferences("app_user_session",Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun findText(root: android.view.View,text: String): Boolean = findTextView(root,text)!=null
    private fun findTextView(root: android.view.View,text: String): TextView? {
        if(root is TextView && root.text.toString()==text)return root
        if(root is android.view.ViewGroup)for(i in 0 until root.childCount) {
            val found=findTextView(root.getChildAt(i),text);if(found!=null)return found
        }
        return null
    }
}
