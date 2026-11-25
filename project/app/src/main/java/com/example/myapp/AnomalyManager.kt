package com.example.myapp

import android.content.Context
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

object AnomalyManager {
    private const val COLLECTION = "anomalies"

    fun sendMisplacementAlert(
        context: Context,
        item: InventoryItem,
        targetLocation: String,
        onSuccess: () -> Unit = {
            Toast.makeText(context, "오적재 조치 요청을 기록했습니다.", Toast.LENGTH_SHORT).show()
        },
        onFailure: (String) -> Unit = {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        }
    ) {
        val company = InventoryManager.activeCompanyCode(context) ?: return onFailure("다시 로그인해 주세요.")
        val actor = FirebaseAuth.getInstance().currentUser?.uid ?: return onFailure("다시 로그인해 주세요.")
        if (InventoryManager.role(context) != "QA") return onFailure("검수자 권한이 필요합니다.")
        val destination = targetLocation.trim()
        if (destination.length > 160) return onFailure("목표 위치는 160자 이내로 입력해 주세요.")

        val db = FirebaseFirestore.getInstance()
        val inventory = InventoryManager.document(company, item)
        val alert = db.collection(COLLECTION).document(inventory.id)
        val event = WarehouseHistory.newEvent(company)
        db.runTransaction { transaction ->
            val snapshot = transaction.get(inventory)
            check(snapshot.exists()) { "재고를 찾을 수 없습니다." }
            val latest = InventoryManager.readItem(snapshot)
            check(latest.itemCode == item.itemCode && latest.location == item.location &&
                latest.warehouse == item.warehouse) { "재고 위치가 변경되었습니다. 다시 확인해 주세요." }
            check(latest.status in setOf("NORMAL", "정상", "ANOMALY")) {
                "현재 상태에서는 오적재 조치를 요청할 수 없습니다."
            }
            val updated = latest.copy(status = "ANOMALY", targetLocation = destination)
            transaction.update(inventory, mapOf("status" to "ANOMALY", "targetLocation" to destination) +
                InventoryManager.writeMetadata(actor, event))
            transaction.set(alert, mapOf(
                "inventoryId" to inventory.id, "companyCode" to company, "itemCode" to latest.itemCode,
                "itemName" to latest.itemName, "currentLocation" to latest.location, "targetLocation" to destination,
                "quantity" to latest.quantity, "status" to "ALERTED", "qaUserId" to actor,
                "updatedAt" to FieldValue.serverTimestamp()
            ))
            WarehouseHistory.write(transaction, event, actor, WarehouseAction.ALERT, latest, updated)
        }.addOnSuccessListener { F06PushOutbox.enqueue(context, inventory.id, "inventory");onSuccess() }
            .addOnFailureListener { onFailure(it.localizedMessage ?: "오적재 요청 저장 실패") }
    }

    fun finishReview(
        context: Context,
        item: InventoryItem,
        approved: Boolean,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val company = InventoryManager.activeCompanyCode(context) ?: return onFailure("다시 로그인해 주세요.")
        val actor = FirebaseAuth.getInstance().currentUser?.uid ?: return onFailure("다시 로그인해 주세요.")
        if (InventoryManager.role(context) != "QA") return onFailure("검수자 권한이 필요합니다.")
        val db = FirebaseFirestore.getInstance()
        val inventory = InventoryManager.document(company, item)
        val alert = db.collection(COLLECTION).document(inventory.id)
        val event = WarehouseHistory.newEvent(company)
        db.runTransaction { transaction ->
            val snapshot = transaction.get(inventory)
            check(snapshot.exists()) { "재고를 찾을 수 없습니다." }
            val latest = InventoryManager.readItem(snapshot)
            check(latest.status == "PENDING_QA") { "이미 처리되었거나 승인 대기 중인 재고가 아닙니다." }
            val hasAlert = approved && transaction.get(alert).exists()
            val after = if (approved && latest.pendingLocation.isNotBlank()) {
                latest.copy(location = latest.pendingLocation, quantity = latest.pendingQuantity, status = "NORMAL")
            } else {
                latest.copy(status = if (approved) "NORMAL" else "ANOMALY")
            }
            val changes = mutableMapOf<String, Any>(
                "status" to after.status, "pendingLocation" to "", "pendingQuantity" to 0
            )
            if (approved) {
                changes["location"] = after.location
                changes["quantity"] = after.quantity
                if (after.location != latest.location) changes.putAll(InventoryManager.replaceLocationMetadata())
            }
            transaction.update(inventory, changes + InventoryManager.writeMetadata(actor, event))
            if (hasAlert) transaction.delete(alert)
            val action = if (approved) WarehouseAction.REVIEW_APPROVAL else WarehouseAction.REVIEW_REJECTION
            WarehouseHistory.write(transaction, event, actor, action, latest, after)
        }.addOnSuccessListener {
            if (!approved) F06PushOutbox.enqueue(context, inventory.id, "inventory")
            onSuccess()
        }
            .addOnFailureListener { onFailure(it.localizedMessage ?: "검수 처리 실패") }
    }
}
