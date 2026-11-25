package com.example.myapp

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source

internal object SignupApproval {
    const val REQUESTS = "signupRequests"
    const val ADMINS = "warehouseAdmins"
    val roles = listOf("LOADER", "QA")
    val roleNames = listOf("적재자 (LOADER)", "검수자 (QA)")
    fun validCompany(value: String) = value != "DEFAULT" && Regex("[A-Z0-9][A-Z0-9_-]{0,63}").matches(value)
    fun roleName(role: String) = roleNames.getOrElse(roles.indexOf(role)) { role }
    fun revision(doc: DocumentSnapshot): Timestamp? = doc.getTimestamp("submittedAt")

    fun checkAdmin() = FirebaseFirestore.getInstance().collection(ADMINS)
        .document(requireNotNull(FirebaseAuth.getInstance().currentUser).uid).get(Source.SERVER)

    fun submit(company: String, role: String, userId: String) : com.google.android.gms.tasks.Task<Void> {
        require(validCompany(company) && role in roles && userId.length in 1..128)
        val user = requireNotNull(FirebaseAuth.getInstance().currentUser)
        val db = FirebaseFirestore.getInstance()
        val ref = db.collection(REQUESTS).document(user.uid)
        return db.runTransaction { tx ->
            val before = tx.get(ref)
            check(!before.exists() || before.getString("status") in listOf("PENDING", "REJECTED")) {
                "이미 승인된 계정입니다. 다시 로그인하세요."
            }
            val data = mutableMapOf<String, Any?>(
                "userId" to (before.getString("userId") ?: userId), "email" to user.email,
                "companyCode" to company, "requestedRole" to role, "status" to "PENDING",
                "createdAt" to (before.getTimestamp("createdAt") ?: FieldValue.serverTimestamp()),
                "submittedAt" to FieldValue.serverTimestamp(), "reviewedAt" to null,
                "reviewedBy" to "", "assignedCompanyCode" to "", "assignedRole" to "", "rejectionReason" to ""
            )
            tx.set(ref, data)
            null
        }
    }

    fun review(uid: String, revision: Timestamp?, company: String, role: String, rejection: String? = null)
        : com.google.android.gms.tasks.Task<Void> {
        require(rejection != null || (validCompany(company) && role in roles))
        require(rejection == null || rejection.trim().length in 1..500)
        val adminUid = requireNotNull(FirebaseAuth.getInstance().currentUser).uid
        val db = FirebaseFirestore.getInstance()
        val request = db.collection(REQUESTS).document(uid)
        val member = db.collection(WarehouseMembership.COLLECTION).document(uid)
        return db.runTransaction { tx ->
            val before = tx.get(request)
            val membership = tx.get(member)
            check(before.getString("status") == "PENDING" && SignupApproval.revision(before) == revision) {
                "가입 요청이 변경되었습니다. 목록을 새로 확인하세요."
            }
            check(!membership.exists()) { "이미 권한이 등록된 계정입니다. 기존 권한을 확인하세요." }
            tx.update(request, mapOf(
                "status" to if (rejection == null) "APPROVED" else "REJECTED",
                "reviewedAt" to FieldValue.serverTimestamp(), "reviewedBy" to adminUid,
                "assignedCompanyCode" to if (rejection == null) company else "",
                "assignedRole" to if (rejection == null) role else "",
                "rejectionReason" to (rejection?.trim() ?: "")
            ))
            if (rejection == null) tx.set(member, mapOf(
                "companyCode" to company, "role" to role, "approvedBy" to adminUid,
                "approvedAt" to FieldValue.serverTimestamp()
            ))
            null
        }
    }
}
