package com.example.myapp

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

// Firebase Cloud Firestore 실시간 재고 데이터 매니저
object InventoryManager {
    private const val TAG = "InventoryManager"
    private const val COLLECTION_NAME = "inventory"

    // 1. Firestore에서 실시간 재고 데이터 수신 (addSnapshotListener)
    fun listenToRealtimeInventory(
        onUpdate: (List<InventoryItem>) -> Unit
    ): ListenerRegistration? {
        return try {
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

                                item = InventoryItem(itemCode, itemName, quantity, location, status, pendingQty, pendingLoc)
                            }
                            items.add(item)
                        }
                        Log.d(TAG, "Realtime Firestore inventory loaded: ${items.size} items")
                        onUpdate(items)
                    } else {
                        Log.d(TAG, "Firestore 'inventory' collection is empty.")
                        onUpdate(emptyList())
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Firebase Firestore connection error: ${e.message}")
            null
        }
    }

    // 2. 재고 항목 추가 또는 업데이트
    fun addOrUpdateInventoryItem(
        item: InventoryItem,
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) {
        try {
            val db = FirebaseFirestore.getInstance()
            val itemMap = hashMapOf(
                "itemCode" to item.itemCode,
                "itemName" to item.itemName,
                "quantity" to item.quantity,
                "location" to item.location,
                "status" to item.status,
                "pendingQuantity" to item.pendingQuantity,
                "pendingLocation" to item.pendingLocation,
                "updatedAt" to FieldValue.serverTimestamp()
            )

            db.collection(COLLECTION_NAME).document(item.itemCode)
                .set(itemMap)
                .addOnSuccessListener {
                    Log.d(TAG, "Inventory item updated successfully: ${item.itemCode}")
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