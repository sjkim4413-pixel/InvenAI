package com.example.myapp

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

// Firebase Cloud Firestore 실시간 재고 데이터 매니저 (사업장/회사 코드별 분리 관리 지원)
object InventoryManager {
    private const val TAG = "InventoryManager"
    private const val COLLECTION_NAME = "inventory"

    private fun getCompanyCode(context: Context?): String {
        if (context == null) return "ABC-001"
        val prefs = context.getSharedPreferences("app_user_session", Context.MODE_PRIVATE)
        val code = prefs.getString("COMPANY_CODE", "ABC-001") ?: "ABC-001"
        return if (code.isBlank() || code == "DEFAULT") "ABC-001" else code
    }

    // 1. 실시간 재고 데이터 수신 (오버로드: Context 없음)
    fun listenToRealtimeInventory(
        onUpdate: (List<InventoryItem>) -> Unit
    ): ListenerRegistration? {
        return listenToRealtimeInventory(null, onUpdate)
    }

    // 1-1. 실시간 재고 데이터 수신 (Context 있음 - 엄격한 companyCode 필터링 및 자동 시드)
    fun listenToRealtimeInventory(
        context: Context?,
        onUpdate: (List<InventoryItem>) -> Unit
    ): ListenerRegistration? {
        return try {
            val companyCode = getCompanyCode(context)
            val db = FirebaseFirestore.getInstance()
            db.collection(COLLECTION_NAME)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Inventory snapshot listen failed: ${error.message}")
                        return@addSnapshotListener
                    }

                    if (snapshot != null && !snapshot.isEmpty) {
                        val items = mutableListOf<InventoryItem>()
                        for (doc in snapshot.documents) {
                            val actualCompany = doc.getString("companyCode") ?: ""
                            // 엄격한 회사별 분리 필터링:
                            // 1. 로그인한 코드가 "ABC-001"인 경우: "ABC-001"이거나 companyCode가 없는 레거시 데이터 허용
                            // 2. 다른 회사 코드로 로그인한 경우: 정확히 일치하는 companyCode 데이터만 허용
                            val matches = if (companyCode == "ABC-001") {
                                actualCompany == "ABC-001" || actualCompany.isEmpty()
                            } else {
                                actualCompany == companyCode
                            }

                            if (!matches) {
                                continue
                            }

                            var item = try { doc.toObject(InventoryItem::class.java) } catch (e: Exception) { null }

                            if (item == null || item.itemName.isEmpty()) {
                                val itemCode = doc.getString("itemCode") ?: doc.getString("code") ?: doc.id
                                val itemName = doc.getString("itemName") ?: doc.getString("name") ?: doc.getString("title") ?: itemCode
                                val quantity = doc.getLong("quantity")?.toInt()
                                    ?: doc.getLong("qty")?.toInt()
                                    ?: (doc.get("quantity") as? Number)?.toInt()
                                    ?: 0
                                val location = doc.getString("location") ?: doc.getString("loc") ?: "미지정 위치"
                                val status = doc.getString("status") ?: "NORMAL"
                                val pendingQty = doc.getLong("pendingQuantity")?.toInt() ?: 0
                                val pendingLoc = doc.getString("pendingLocation") ?: ""
                                val updatedAtTs = doc.getTimestamp("updatedAt")?.toDate()?.time
                                    ?: doc.getLong("updatedAtTimestamp")
                                    ?: doc.getTimestamp("createdAt")?.toDate()?.time
                                    ?: System.currentTimeMillis()

                                item = InventoryItem(itemCode, itemName, quantity, location, status, pendingQty, pendingLoc, updatedAtTs)
                            }
                            items.add(item)
                        }

                        if (items.isEmpty()) {
                            seedDefaultInventory(db, companyCode) { seeded ->
                                onUpdate(seeded)
                            }
                        } else {
                            Log.d(TAG, "Realtime Firestore inventory loaded for company [$companyCode]: ${items.size} items")
                            onUpdate(items)
                        }
                    } else {
                        seedDefaultInventory(db, companyCode) { seeded ->
                            onUpdate(seeded)
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Firebase Firestore connection error: ${e.message}")
            null
        }
    }

    private fun seedDefaultInventory(db: FirebaseFirestore, companyCode: String, onSeeded: (List<InventoryItem>) -> Unit) {
        val now = System.currentTimeMillis()
        val defaultItems = listOf(
            InventoryItem("M-100", "무선 마우스 M-100", 120, "B구역 R05-3층", "NORMAL", updatedAtTimestamp = now - 300000),
            InventoryItem("BT-200", "블루투스 이어폰 BT-200", 50, "A구역 R01-1층 (오적재 감지)", "ANOMALY", updatedAtTimestamp = now - 2100000),
            InventoryItem("MC-300", "고속 C타입 멀티 충전기", 80, "A03-3단", "PENDING_QA", updatedAtTimestamp = now - 10800000),
            InventoryItem("KB-400", "기계식 키보드 K-90", 200, "A04-1단", "NORMAL", updatedAtTimestamp = now - 172800000)
        )

        for (item in defaultItems) {
            val itemMap = hashMapOf(
                "companyCode" to companyCode,
                "itemCode" to item.itemCode,
                "itemName" to item.itemName,
                "quantity" to item.quantity,
                "location" to item.location,
                "status" to item.status,
                "pendingQuantity" to item.pendingQuantity,
                "pendingLocation" to item.pendingLocation,
                "updatedAtTimestamp" to item.updatedAtTimestamp,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.collection(COLLECTION_NAME).document("${companyCode}_${item.itemCode}").set(itemMap)
        }
        onSeeded(defaultItems)
    }

    // 2. 재고 항목 추가 또는 업데이트 (오버로드: Context 없음)
    fun addOrUpdateInventoryItem(
        item: InventoryItem,
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) {
        addOrUpdateInventoryItem(null, item, onSuccess, onFailure)
    }

    // 2-1. 재고 항목 추가 또는 업데이트 (Context 있음 - companyCode 태그 저장)
    fun addOrUpdateInventoryItem(
        context: Context?,
        item: InventoryItem,
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) {
        try {
            val companyCode = getCompanyCode(context)
            val db = FirebaseFirestore.getInstance()
            val nowTs = System.currentTimeMillis()
            val itemMap = hashMapOf(
                "companyCode" to companyCode,
                "itemCode" to item.itemCode,
                "itemName" to item.itemName,
                "quantity" to item.quantity,
                "location" to item.location,
                "status" to item.status,
                "pendingQuantity" to item.pendingQuantity,
                "pendingLocation" to item.pendingLocation,
                "updatedAtTimestamp" to nowTs,
                "updatedAt" to FieldValue.serverTimestamp()
            )

            // 회사별로 문서 ID 구분 저장
            db.collection(COLLECTION_NAME).document("${companyCode}_${item.itemCode}")
                .set(itemMap)
                .addOnSuccessListener {
                    Log.d(TAG, "Inventory item updated successfully for company [$companyCode]: ${item.itemCode}")
                    onSuccess()
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Inventory update failed: ${e.message}")
                    onFailure(e.message ?: "저장 실패")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during inventory save: ${e.message}")
            onFailure(e.message ?: "연동 오류")
        }
    }
}
