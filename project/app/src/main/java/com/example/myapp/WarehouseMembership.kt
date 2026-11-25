package com.example.myapp

import com.google.firebase.firestore.DocumentSnapshot

internal data class WarehouseMembership(val companyCode: String, val role: String) {
    companion object {
        const val COLLECTION = "warehouseMemberships"
        private val companyCodePattern = Regex("[A-Z0-9][A-Z0-9_-]{0,63}")

        fun from(document: DocumentSnapshot): WarehouseMembership? {
            val company = document.get("companyCode") as? String ?: return null
            val role = document.get("role") as? String ?: return null
            if (!companyCodePattern.matches(company) || company == "DEFAULT") return null
            if (role != "QA" && role != "LOADER") return null
            return WarehouseMembership(company, role)
        }
    }
}
