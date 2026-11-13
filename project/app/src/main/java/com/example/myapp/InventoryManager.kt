package com.example.myapp

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

// Firebase Cloud Firestore 실시간 재고 데이터 매니저 (랙별 정규화 위치 데이터 및 테스트 오적재 시드 지원)
object InventoryManager {
    private const val TAG = "InventoryManager"
    private const val COLLECTION_NAME = "inventory"

    private fun getCompanyCode(context: Context?): String {
        if (context == null) return "ABC-001"
        val prefs = context.getSharedPreferences("app_user_session", Context.MODE_PRIVATE)
        val code = prefs.getString("COMPANY_CODE", "ABC-001") ?: "ABC-001"
        return if (code.isBlank() || code == "DEFAULT") "ABC-001" else code.trim().uppercase()
    }

    private fun getCurrentUserId(context: Context?): String {
        if (context != null) {
            val prefs = context.getSharedPreferences("app_user_session", Context.MODE_PRIVATE)
            val userId = prefs.getString("USER_ID", "") ?: ""
            if (userId.isNotBlank()) return userId
        }
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            val email = currentUser.email
            if (!email.isNullOrEmpty()) {
                return email.substringBefore("@")
            }
            return currentUser.uid.takeLast(6)
        }
        return "관리자"
    }

    // 1. 실시간 재고 데이터 수신 (오버로드: Context 없음)
    fun listenToRealtimeInventory(
        onUpdate: (List<InventoryItem>) -> Unit
    ): ListenerRegistration? {
        return listenToRealtimeInventory(null, onUpdate)
    }

    // 1-1. 실시간 재고 데이터 수신 (Context 있음 - 엄격한 companyCode 필터링, 랙 정규화 및 자동 시드)
    fun listenToRealtimeInventory(
        context: Context?,
        onUpdate: (List<InventoryItem>) -> Unit
    ): ListenerRegistration? {
        return try {
            val companyCode = getCompanyCode(context)
            val activeUserId = getCurrentUserId(context)
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
                                val location = doc.getString("location") ?: doc.getString("loc") ?: "B구역 R05-3층"
                                val status = doc.getString("status") ?: "NORMAL"
                                val pendingQty = doc.getLong("pendingQuantity")?.toInt() ?: 0
                                val pendingLoc = doc.getString("pendingLocation") ?: ""
                                var userId = doc.getString("userId") ?: doc.getString("user") ?: ""
                                if (userId.isBlank() || userId == "시스템") userId = activeUserId
                                val targetLocation = doc.getString("targetLocation") ?: ""
                                val relocationDone = doc.getBoolean("relocationDone") ?: false
                                val warehouse = doc.getString("warehouse") ?: "제1물류센터"
                                val zone = doc.getString("zone") ?: if (location.contains("구역")) location.substringBefore(" ").trim() else "B구역"
                                val rack = doc.getString("rack") ?: if (location.contains(" ")) location.substringAfter(" ").substringBefore("-").trim() else "R05"
                                val tier = doc.getString("tier") ?: if (location.contains("-")) location.substringAfter("-").trim() else "3층"
                                val rackId = doc.getString("rackId") ?: "$zone $rack"

                                val updatedAtTs = doc.getTimestamp("updatedAt")?.toDate()?.time
                                    ?: doc.getLong("updatedAtTimestamp")
                                    ?: doc.getTimestamp("createdAt")?.toDate()?.time
                                    ?: System.currentTimeMillis()

                                item = InventoryItem(itemCode, itemName, quantity, location, status, pendingQty, pendingLoc, updatedAtTs, userId, targetLocation, relocationDone, warehouse, zone, rack, tier, rackId)
                            } else {
                                if (item.userId.isBlank() || item.userId == "시스템") {
                                    val userId = doc.getString("userId") ?: activeUserId
                                    item = item.copy(userId = if (userId.isBlank() || userId == "시스템") activeUserId else userId)
                                }
                                if (item.targetLocation.isEmpty()) {
                                    val targetLoc = doc.getString("targetLocation") ?: ""
                                    if (targetLoc.isNotEmpty()) {
                                        item = item.copy(targetLocation = targetLoc)
                                    }
                                }
                            }
                            items.add(item)
                        }

                        val uniqueItems = items.distinctBy { it.itemCode}

                        if (uniqueItems.isEmpty()) {
                            seedDefaultInventory(db, companyCode, activeUserId) { seeded ->
                                onUpdate(seeded)
                            }
                        } else {
                            Log.d(TAG, "Realtime Firestore inventory loaded for company [$companyCode]: ${uniqueItems.size} items")
                            onUpdate(uniqueItems)
                        }
                    } else {
                        seedDefaultInventory(db, companyCode, activeUserId) { seeded ->
                            onUpdate(seeded)
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Firebase Firestore connection error: ${e.message}")
            null
        }
    }

    private fun seedDefaultInventory(db: FirebaseFirestore, companyCode: String, currentUserId: String, onSeeded: (List<InventoryItem>) -> Unit) {
        val now = System.currentTimeMillis()
        val defaultItems = listOf(
            InventoryItem("M-100", "무선 마우스 M-100", 120, "B구역 R05-3층", "NORMAL", updatedAtTimestamp = now - 300000, userId = currentUserId, targetLocation = "", relocationDone = false, warehouse = "제1물류센터", zone = "B구역", rack = "R05", tier = "3층", rackId = "B구역 R05"),
            InventoryItem("BT-200", "블루투스 이어폰 BT-200", 50, "A구역 R01-1층 (오적재 감지)", "ANOMALY", updatedAtTimestamp = now - 2100000, userId = currentUserId, targetLocation = "B구역 R05-3층", relocationDone = false, warehouse = "제1물류센터", zone = "A구역", rack = "R01", tier = "1층", rackId = "A구역 R01"),
            InventoryItem("HD-500", "고성능 외장 SSD 1TB", 30, "C구역 R02-2층 (오적재 감지)", "ANOMALY", updatedAtTimestamp = now - 1500000, userId = currentUserId, targetLocation = "D구역 R04-1층", relocationDone = false, warehouse = "제1물류센터", zone = "C구역", rack = "R02", tier = "2층", rackId = "C구역 R02"),
            InventoryItem("SP-600", "스마트 블루투스 스피커", 45, "A구역 R03-1층 (오적재 감지)", "ANOMALY", updatedAtTimestamp = now - 900000, userId = currentUserId, targetLocation = "B구역 R02-2층", relocationDone = false, warehouse = "제1물류센터", zone = "A구역", rack = "R03", tier = "1층", rackId = "A구역 R03"),
            InventoryItem("MC-300", "고속 C타입 멀티 충전기", 80, "A03-3단", "PENDING_QA", updatedAtTimestamp = now - 10800000, userId = currentUserId, targetLocation = "", relocationDone = false, warehouse = "제1물류센터", zone = "A구역", rack = "R03", tier = "3단", rackId = "A구역 R03"),
            InventoryItem("KB-400", "기계식 키보드 K-90", 200, "A04-1단", "NORMAL", updatedAtTimestamp = now - 172800000, userId = currentUserId, targetLocation = "", relocationDone = false, warehouse = "제1물류센터", zone = "A구역", rack = "R04", tier = "1단", rackId = "A구역 R04")
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
                "userId" to item.userId,
                "targetLocation" to item.targetLocation,
                "relocationDone" to item.relocationDone,
                "warehouse" to item.warehouse,
                "zone" to item.zone,
                "rack" to item.rack,
                "tier" to item.tier,
                "rackId" to item.rackId,
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

    // 2-1. 재고 항목 추가 또는 업데이트 (Context 있음 - companyCode 및 랙 정규화 저장)
    fun addOrUpdateInventoryItem(
        context: Context?,
        item: InventoryItem,
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) {
        try {
            val companyCode = getCompanyCode(context)
            val currentUserId = getCurrentUserId(context)
            val assignedUserId = if (item.userId.isNotEmpty() && item.userId != "시스템") item.userId else currentUserId

            val loc = item.location
            val zone = item.zone.ifEmpty { if (loc.contains("구역")) loc.substringBefore(" ").trim() else "B구역" }
            val rackPart = if (loc.contains(" ")) loc.substringAfter(" ").substringBefore("-").trim() else "R05"
            val tierPart = if (loc.contains("-")) loc.substringAfter("-").trim() else "3층"
            val rackId = "$zone $rackPart"
            val warehouse = item.warehouse.ifEmpty { "제1물류센터" }

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
                "userId" to assignedUserId,
                "targetLocation" to item.targetLocation,
                "relocationDone" to item.relocationDone,
                "warehouse" to warehouse,
                "zone" to zone,
                "rack" to rackPart,
                "tier" to tierPart,
                "rackId" to rackId,
                "updatedAtTimestamp" to nowTs,
                "updatedAt" to FieldValue.serverTimestamp()
            )

            // 회사별로 문서 ID 구분 저장
            db.collection(COLLECTION_NAME).document("${companyCode}_${item.itemCode}")
                .set(itemMap)
                .addOnSuccessListener {
                    Log.d(TAG, "Inventory item updated successfully for company [$companyCode] by user [$assignedUserId]: ${item.itemCode}")
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
