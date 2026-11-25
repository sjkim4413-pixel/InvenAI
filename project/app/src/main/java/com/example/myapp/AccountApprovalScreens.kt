package com.example.myapp

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import java.text.SimpleDateFormat
import java.util.Locale

abstract class AccountApprovalScreen : WarehouseScreen() {
    protected var registration: ListenerRegistration? = null
    protected val auth get() = FirebaseAuth.getInstance()
    protected fun notifyUser(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    protected fun login(company: String = "", userId: String = "") {
        auth.signOut()
        getSharedPreferences("app_user_session", MODE_PRIVATE).edit().clear().apply()
        startActivity(Intent(this, Frame1::class.java).putExtra("COMPANY_CODE", company)
            .putExtra("USER_ID", userId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
    override fun onStop() {
        registration?.remove()
        registration = null
        super.onStop()
    }
}

class PendingApproval : AccountApprovalScreen() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openScreen("가입 승인 상태", navigation = false)
    }
    override fun onStart() {
        super.onStart()
        val user = auth.currentUser ?: return login()
        body.removeAllViews()
        label("승인 상태를 확인하고 있습니다.")
        registration = FirebaseFirestore.getInstance().collection(SignupApproval.REQUESTS).document(user.uid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { doc, error ->
                if (isFinishing || auth.currentUser?.uid != user.uid) return@addSnapshotListener
                if (error != null) {
                    body.removeAllViews()
                    label("승인 상태를 확인하지 못했습니다. 연결 상태를 확인하세요.")
                    button("다시 로그인") { login() }
                } else if (doc != null) render(doc)
            }
    }
    private fun render(doc: DocumentSnapshot) {
        body.removeAllViews()
        val id = doc.getString("userId") ?: intent.getStringExtra("USER_ID")
            ?: auth.currentUser?.email?.substringBefore('@').orEmpty()
        val company = doc.getString("companyCode") ?: intent.getStringExtra("COMPANY_CODE").orEmpty()
        val status = doc.getString("status")
        label(id, true)
        val state = when (status) {
            "PENDING" -> "관리자 승인 대기 중"
            "REJECTED" -> "가입 요청이 반려되었습니다"
            "APPROVED" -> "가입이 승인되었습니다"
            else -> "회사와 작업 역할을 선택해 가입 승인을 요청하세요"
        }
        label(state, true).id = R.id.approvalStatus
        if (doc.metadata.isFromCache) label("저장된 상태입니다. 서버에 연결하면 최신 상태가 표시됩니다.")
        if (status == "APPROVED") {
            val assigned = doc.getString("assignedCompanyCode").orEmpty()
            label("회사: $assigned\n역할: ${SignupApproval.roleName(doc.getString("assignedRole").orEmpty())}")
            button("승인된 계정으로 로그인", true) { login(assigned, id) }
        } else {
            if (status == "REJECTED") label("반려 사유: ${doc.getString("rejectionReason").orEmpty()}")
            if (status == "PENDING") label("요청 회사: $company\n요청 역할: ${SignupApproval.roleName(doc.getString("requestedRole").orEmpty())}")
            val companyField = field("회사/사업장 코드").apply { setText(company); this.id = R.id.approvalCompany }
            var role = doc.getString("requestedRole")?.takeIf { it in SignupApproval.roles } ?: "LOADER"
            lateinit var roleButton: MaterialButton
            roleButton = selector(SignupApproval.roleName(role)) {
                choose("요청 역할", SignupApproval.roleNames, SignupApproval.roles.indexOf(role)) {
                    role = SignupApproval.roles[it]; roleButton.text = SignupApproval.roleName(role)
                }
            }.apply { this.id = R.id.approvalRole }
            lateinit var submit: MaterialButton
            submit = button(if (status == "PENDING") "가입 요청 수정" else "가입 승인 요청", true) {
                val requested = companyField.text.toString().trim().uppercase(Locale.ROOT)
                if (!SignupApproval.validCompany(requested)) {
                    notifyUser("영문 대문자·숫자·하이픈·밑줄로 회사 코드를 입력하세요. DEFAULT는 사용할 수 없습니다.")
                    return@button
                }
                submit.isEnabled = false
                SignupApproval.submit(requested, role, id).addOnCompleteListener {
                    submit.isEnabled = true
                    notifyUser(if (it.isSuccessful) "가입 요청을 보냈습니다." else "요청을 저장하지 못했습니다. 연결 및 승인 상태를 확인하세요.")
                }
            }.apply { this.id = R.id.approvalSubmit }
        }
        button("로그아웃") { login() }
    }
}

class AdminDashboard : AccountApprovalScreen() {
    private var status = "PENDING"
    private var busy = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openScreen("관리자 · 가입 승인", navigation = false)
    }
    override fun onStart() {
        super.onStart()
        if (auth.currentUser == null) return login()
        body.removeAllViews()
        label("관리자 권한을 확인하고 있습니다.")
        val uid = auth.currentUser!!.uid
        SignupApproval.checkAdmin().addOnSuccessListener {
            if (isFinishing || auth.currentUser?.uid != uid) return@addOnSuccessListener
            if (it.getBoolean("enabled") == true) observe() else login()
        }.addOnFailureListener {
            if (!isFinishing) { notifyUser("관리자 권한을 확인하지 못했습니다. 다시 로그인하세요."); login() }
        }
    }
    private fun observe() {
        registration?.remove()
        registration = FirebaseFirestore.getInstance().collection(SignupApproval.REQUESTS)
            .whereEqualTo("status", status).addSnapshotListener(MetadataChanges.INCLUDE) { result, error ->
                if (isFinishing) return@addSnapshotListener
                body.removeAllViews()
                label("가입 요청을 확인하고 회사와 작업 역할을 지정하세요.")
                val states = listOf("PENDING", "APPROVED", "REJECTED")
                val names = listOf("승인 대기", "승인 완료", "반려")
                selector(names[states.indexOf(status)]) {
                    choose("가입 요청 상태", names, states.indexOf(status)) { status = states[it]; observe() }
                }.id = R.id.adminStatusFilter
                button("로그아웃") { login() }
                if (error != null) {
                    label("가입 요청을 불러오지 못했습니다. 연결 상태와 관리자 권한을 확인하세요.")
                    button("다시 불러오기") { observe() }
                    return@addSnapshotListener
                }
                if (result?.metadata?.isFromCache == true) label("저장된 목록입니다. 승인 처리는 서버에 연결된 상태에서만 가능합니다.")
                val docs = result?.documents.orEmpty().sortedByDescending { it.getTimestamp("submittedAt") }
                label("${names[states.indexOf(status)]} ${docs.size}건", true).id = R.id.adminRequestCount
                if (docs.isEmpty()) label("해당하는 가입 요청이 없습니다.")
                for (doc in docs) {
                    val title = label(doc.getString("userId").orEmpty(), true)
                    val date = doc.getTimestamp("submittedAt")?.toDate()?.let {
                        SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(it)
                    }.orEmpty()
                    val detail = label("${doc.getString("email")}\n요청 회사: ${doc.getString("companyCode")}\n요청 역할: ${SignupApproval.roleName(doc.getString("requestedRole").orEmpty())}\n신청: $date")
                    if (status == "PENDING") {
                        val approve = button("회사·역할 지정 후 승인", true) { if (!busy) approve(doc) }
                            .apply { id = R.id.adminApprove }
                        val reject = button("반려") { if (!busy) reject(doc) }.apply { id = R.id.adminReject }
                        card(title, detail, approve, reject)
                    } else {
                        val review = label(if (status == "APPROVED")
                            "승인 회사: ${doc.getString("assignedCompanyCode")}\n승인 역할: ${SignupApproval.roleName(doc.getString("assignedRole").orEmpty())}"
                            else "반려 사유: ${doc.getString("rejectionReason")}")
                        card(title, detail, review)
                    }
                }
            }
    }
    private fun approve(doc: DocumentSnapshot) {
        val company = EditText(this).apply {
            hint = "승인할 회사 코드"; setText(doc.getString("companyCode")); id = R.id.adminAssignedCompany
            setSingleLine(true); setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        var role = doc.getString("requestedRole")?.takeIf { it in SignupApproval.roles } ?: "LOADER"
        val roleButton = MaterialButton(this).apply { text = SignupApproval.roleName(role); id = R.id.adminAssignedRole }
        WarehouseMenus.decorateSelector(roleButton)
        roleButton.setOnClickListener {
            choose("승인할 역할", SignupApproval.roleNames, SignupApproval.roles.indexOf(role)) {
                role = SignupApproval.roles[it]; roleButton.text = SignupApproval.roleName(role)
            }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(company); addView(roleButton)
        }
        val dialog = AlertDialog.Builder(this).setTitle("${doc.getString("userId")} 가입 승인")
            .setView(layout).setPositiveButton("승인", null).setNegativeButton("취소", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = company.text.toString().trim().uppercase(Locale.ROOT)
            if (!SignupApproval.validCompany(value)) { company.error = "유효한 회사 코드를 입력하세요."; return@setOnClickListener }
            dialog.dismiss(); review(doc, value, role)
        } }
        dialog.show()
    }
    private fun reject(doc: DocumentSnapshot) {
        val reason = EditText(this).apply { hint = "반려 사유 (1~500자)"; id = R.id.adminRejectionReason }
        val dialog = AlertDialog.Builder(this).setTitle("가입 요청 반려").setView(reason)
            .setPositiveButton("반려", null).setNegativeButton("취소", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = reason.text.toString().trim()
            if (text.length !in 1..500) { reason.error = "반려 사유를 1~500자로 입력하세요."; return@setOnClickListener }
            dialog.dismiss(); review(doc, "", "", text)
        } }
        dialog.show()
    }
    private fun review(doc: DocumentSnapshot, company: String, role: String, reason: String? = null) {
        busy = true
        SignupApproval.review(doc.id, SignupApproval.revision(doc), company, role, reason).addOnCompleteListener {
            busy = false
            notifyUser(if (it.isSuccessful) if (reason == null) "가입을 승인했습니다." else "가입 요청을 반려했습니다."
                else "처리하지 못했습니다. 요청이 변경되었거나 연결·권한에 문제가 있습니다. 목록을 다시 확인하세요.")
        }
    }
}
