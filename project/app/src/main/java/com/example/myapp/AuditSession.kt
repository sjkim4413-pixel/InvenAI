package com.example.myapp

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source

data class AuditSession(
    val companyCode: String,
    val userId: String,
    val warehouseId: Int,
    val role: String
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

        val localCompany = InventoryManager.activeCompanyCode(context)

        if (
            localCompany.isNullOrBlank() ||
            localCompany == "DEFAULT"
        ) {
            onResult(null)
            return
        }

        FirebaseFirestore.getInstance()
            .collection(WarehouseMembership.COLLECTION)
            .document(user.uid)
            .get(Source.SERVER)
            .addOnSuccessListener { document ->
                val membership = WarehouseMembership.from(document)

                if (
                    membership == null ||
                    membership.companyCode != localCompany ||
                    FirebaseAuth.getInstance().currentUser?.uid != user.uid
                ) {
                    onResult(null)
                    return@addOnSuccessListener
                }

                onResult(
                    AuditSession(
                        companyCode = membership.companyCode,
                        userId = user.uid,
                        warehouseId = warehouseId,
                        role = membership.role
                    )
                )
            }
            .addOnFailureListener {
                onResult(null)
            }
    }
}
