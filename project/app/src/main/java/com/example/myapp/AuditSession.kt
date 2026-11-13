package com.example.myapp

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

data class AuditSession(
    val companyCode: String,
    val userId: String,
    val warehouseId: Int
)

object AuditSessionManager {

    fun getVerifiedSession(
        context: Context,
        warehouseId: Int,
        onResult: (AuditSession?) -> Unit
    ) {
        if (warehouseId <= 0) {
            onResult(null)
            return
        }

        val user = FirebaseAuth.getInstance().currentUser

        if (user == null) {
            onResult(null)
            return
        }

        val prefs = context.getSharedPreferences(
            "app_user_session",
            Context.MODE_PRIVATE
        )

        val localCompany = prefs.getString(
            "COMPANY_CODE",
            null
        )?.trim()?.uppercase()

        if (
            localCompany.isNullOrBlank() ||
            localCompany == "DEFAULT"
        ) {
            onResult(null)
            return
        }

        FirebaseFirestore.getInstance()
            .collection("users")
            .document(user.uid)
            .get()
            .addOnSuccessListener { document ->

                val firebaseCompany = document
                    .getString("companyCode")
                    ?.trim()
                    ?.uppercase()

                if (
                    !document.exists() ||
                    firebaseCompany.isNullOrBlank() ||
                    firebaseCompany != localCompany
                ) {
                    onResult(null)
                    return@addOnSuccessListener
                }

                onResult(
                    AuditSession(
                        companyCode = firebaseCompany,
                        userId = user.uid,
                        warehouseId = warehouseId
                    )
                )
            }
            .addOnFailureListener {
                onResult(null)
            }
    }
}
