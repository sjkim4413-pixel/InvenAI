package com.example.myapp

import android.content.Context
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Transaction
import com.google.firebase.auth.FirebaseAuth

internal enum class WarehouseAction(val label: String, val anomaly: Boolean = false) {
    INBOUND("입고"), OUTBOUND("출고"), TRANSFER("이동"), CORRECTION("수량 수정"), IMPORT("CSV 반영"),
    ALERT("이상 조치 요청", true), REVIEW_REQUEST("재검수 요청", true),
    REVIEW_APPROVAL("검수 승인", true), REVIEW_REJECTION("검수 반려", true),
    AUDIT_DETECTION("실사 오적재 발견", true), AUDIT_RELOCATION("재적재 회신·재검수 요청", true),
    AUDIT_APPROVAL("실사 재검수 승인", true), AUDIT_REJECTION("실사 재조치 요청", true)
}

internal data class WarehouseEvent(
    val documentId: String,
    val itemName: String,
    val itemCode: String,
    val warehouse: String,
    val fromLocation: String,
    val toLocation: String,
    val beforeQuantity: Int,
    val afterQuantity: Int,
    val actorUid: String,
    val occurredAt: Long,
    val action: WarehouseAction,
    val recordId: String = "",
    val alertId: String = "",
    val quantityKnown: Boolean = true,
    val isTest: Boolean = false,
    val note: String = "",
    val detail: String = "",
    val inferred: Boolean = false
) {
    val isWork: Boolean get() = !action.anomaly || action == WarehouseAction.AUDIT_RELOCATION ||
        action == WarehouseAction.REVIEW_REQUEST ||
        (action == WarehouseAction.REVIEW_APPROVAL && (fromLocation != toLocation || beforeQuantity != afterQuantity))
    val isResolved: Boolean get() = action in setOf(WarehouseAction.REVIEW_APPROVAL, WarehouseAction.AUDIT_APPROVAL)
}

internal object WarehouseHistory {
    private fun events(company: String) = FirebaseFirestore.getInstance()
        .collection("stockHistory").document(company).collection("events")

    fun newEvent(company: String): DocumentReference = events(company).document()

    fun write(
        transaction: Transaction,
        event: DocumentReference,
        actor: String,
        action: WarehouseAction,
        before: InventoryItem?,
        after: InventoryItem
    ) {
        transaction.set(event, mapOf(
            "inventoryId" to after.documentId, "actorUid" to actor, "type" to action.name,
            "itemCode" to after.itemCode, "itemName" to after.itemName, "warehouse" to after.warehouse,
            "fromLocation" to (before?.location ?: ""), "toLocation" to after.location,
            "beforeQuantity" to (before?.quantity ?: 0), "afterQuantity" to after.quantity,
            "occurredAt" to FieldValue.serverTimestamp()
        ))
    }

    fun listen(
        context: Context,
        limit: Long = 100,
        onError: (String) -> Unit,
        onEvents: (List<WarehouseEvent>) -> Unit
    ): ListenerRegistration? {
        val company = InventoryManager.activeCompanyCode(context) ?: run {
            onError("다시 로그인해 주세요.")
            return null
        }
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return null
        var stock = emptyList<WarehouseEvent>()
        var audit = emptyList<WarehouseEvent>()
        var fallback = emptyList<WarehouseEvent>()
        val ready = mutableSetOf<String>()
        val errors = mutableSetOf<String>()
        fun active() = InventoryManager.activeCompanyCode(context) == company && FirebaseAuth.getInstance().currentUser?.uid == uid
        fun publish() {
            if (!active()) return
            if (errors.isNotEmpty()) onError("일부 현장 이력을 불러오지 못했습니다. 연결 상태와 권한을 확인해 주세요.")
            else if (ready.size == 3) onEvents(mergeWarehouseEvents(stock, audit, fallback, limit.toInt()))
        }
        val stockRegistration = events(company).orderBy("occurredAt", Query.Direction.DESCENDING).limit(limit)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (!active()) return@addSnapshotListener
                if (error != null) {
                    errors.add("stock"); publish()
                } else if (snapshot != null && !snapshot.metadata.isFromCache && !snapshot.metadata.hasPendingWrites()) {
                    errors.remove("stock"); ready.add("stock")
                    stock = snapshot.documents.mapNotNull { row ->
                        val type = WarehouseAction.entries.firstOrNull { it.name == row.getString("type") }
                            ?: return@mapNotNull null
                        WarehouseEvent(
                            row.getString("inventoryId").orEmpty(),
                            row.getString("itemName").orEmpty(),
                            row.getString("itemCode").orEmpty(),
                            row.getString("warehouse").orEmpty(),
                            row.getString("fromLocation").orEmpty(),
                            row.getString("toLocation").orEmpty(),
                            row.getLong("beforeQuantity")?.toInt() ?: return@mapNotNull null,
                            row.getLong("afterQuantity")?.toInt() ?: return@mapNotNull null,
                            row.getString("actorUid").orEmpty(),
                            row.getTimestamp("occurredAt")?.toDate()?.time ?: return@mapNotNull null,
                            type, recordId = row.id
                        )
                    }
                    publish()
                }
            }
        val auditRegistration = AuditHistory.events(company).orderBy("occurredAt", Query.Direction.DESCENDING).limit(limit)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (!active()) return@addSnapshotListener
                if (error != null) { errors.add("audit"); publish() }
                else if (snapshot != null && !snapshot.metadata.isFromCache && !snapshot.metadata.hasPendingWrites()) {
                    errors.remove("audit"); ready.add("audit")
                    audit = snapshot.documents.mapNotNull(AuditHistory::read)
                    publish()
                }
            }
        val fallbackRegistration = FirebaseFirestore.getInstance().collection(AuditAlertManager.COLLECTION)
            .whereEqualTo("companyCode", company).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (!active()) return@addSnapshotListener
                if (error != null) { errors.add("alerts"); publish() }
                else if (snapshot != null && !snapshot.metadata.isFromCache && !snapshot.metadata.hasPendingWrites()) {
                    errors.remove("alerts"); ready.add("alerts")
                    fallback = snapshot.documents.flatMap(AuditHistory::fallback)
                    publish()
                }
            }
        return ListenerRegistration {
            stockRegistration.remove(); auditRegistration.remove(); fallbackRegistration.remove()
        }
    }
}
