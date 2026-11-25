package com.example.myapp

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Transaction

/** Immutable physical-work reports; these events never change ERP stock balances. */
internal object AuditHistory {
    const val COLLECTION = "auditHistory"
    fun events(company: String) = FirebaseFirestore.getInstance().collection(COLLECTION)
        .document(company).collection("events")
    fun newEvent(company: String) = events(company).document()

    fun write(tx: Transaction, event: DocumentReference, alert: AuditMisplacementAlert,
              actor: String, before: String, after: String, note: String) {
        tx.set(event, mapOf(
            "alertId" to alert.id, "inventoryId" to alert.inventoryId, "actorUid" to actor,
            "type" to action(before, after).name, "beforeStatus" to before, "afterStatus" to after,
            "expectedItemCode" to alert.expectedItemCode, "expectedItemName" to alert.expectedItemName,
            "observedItemCode" to alert.observedItemCode, "observedItemName" to alert.observedItemName,
            "warehouse" to alert.warehouse, "fromLocation" to alert.location,
            "toLocation" to alert.targetLocation, "isTest" to alert.isTest, "note" to note,
            "occurredAt" to FieldValue.serverTimestamp()
        ))
    }

    private fun action(before: String, after: String) = when {
        before.isEmpty() && after == "OPEN" -> WarehouseAction.AUDIT_DETECTION
        before == "OPEN" && after == "REVIEW_REQUESTED" -> WarehouseAction.AUDIT_RELOCATION
        after == "RESOLVED" -> WarehouseAction.AUDIT_APPROVAL
        before == "REVIEW_REQUESTED" && after == "OPEN" -> WarehouseAction.AUDIT_REJECTION
        else -> error("지원하지 않는 실사 이력 상태입니다.")
    }

    fun read(doc: DocumentSnapshot): WarehouseEvent? = runCatching {
        val type = WarehouseAction.valueOf(requireNotNull(doc.getString("type")))
        require(type in auditActions)
        WarehouseEvent(
            doc.getString("inventoryId").orEmpty(), doc.getString("observedItemName").orEmpty(),
            doc.getString("observedItemCode").orEmpty(), doc.getString("warehouse").orEmpty(),
            doc.getString("fromLocation").orEmpty(), doc.getString("toLocation").orEmpty(),
            0, 0, doc.getString("actorUid").orEmpty(),
            requireNotNull(doc.getTimestamp("occurredAt")).toDate().time, type,
            recordId = doc.id, alertId = doc.getString("alertId").orEmpty(), quantityKnown = false,
            isTest = doc.getBoolean("isTest") == true, note = doc.getString("note").orEmpty(),
            detail = "전산 기준 ${doc.getString("expectedItemName")} / 발견 ${doc.getString("observedItemName")}"
        )
    }.getOrNull()

    /** Existing alerts retain their creation and final-state timestamps, not a full past trail. */
    fun fallback(doc: DocumentSnapshot): List<WarehouseEvent> {
        val alert = AuditAlertManager.read(doc) ?: return emptyList()
        val created = doc.getTimestamp("createdAt")?.toDate()?.time ?: return emptyList()
        fun event(type: WarehouseAction, actor: String, time: Long) = WarehouseEvent(
            alert.inventoryId, alert.observedItemName, alert.observedItemCode, alert.warehouse,
            alert.location, alert.targetLocation, 0, 0, actor, time, type,
            alertId = alert.id, quantityKnown = false, isTest = alert.isTest,
            note = if (type == WarehouseAction.AUDIT_DETECTION) "" else alert.note,
            detail = "전산 기준 ${alert.expectedItemName} / 발견 ${alert.observedItemName}", inferred = true
        )
        val result = mutableListOf(event(WarehouseAction.AUDIT_DETECTION, alert.qaUid, created))
        if (alert.updatedAt > created) when (alert.status) {
            "REVIEW_REQUESTED" -> result.add(event(WarehouseAction.AUDIT_RELOCATION, alert.responderUid, alert.updatedAt))
            "RESOLVED" -> result.add(event(WarehouseAction.AUDIT_APPROVAL, "", alert.updatedAt))
            "OPEN" -> result.add(event(WarehouseAction.AUDIT_REJECTION, "", alert.updatedAt))
        }
        return result
    }

    val auditActions = setOf(WarehouseAction.AUDIT_DETECTION, WarehouseAction.AUDIT_RELOCATION,
        WarehouseAction.AUDIT_APPROVAL, WarehouseAction.AUDIT_REJECTION)
}

internal fun mergeWarehouseEvents(stock: List<WarehouseEvent>, audit: List<WarehouseEvent>,
                                  fallback: List<WarehouseEvent>, limit: Int): List<WarehouseEvent> {
    fun factKey(event: WarehouseEvent) = "${event.alertId}:${event.action}:${event.occurredAt}"
    val saved = (stock + audit).distinctBy {
        "${if (it.alertId.isBlank()) "stock" else "audit"}:${it.recordId.ifBlank { factKey(it) }}"
    }
    val recorded = audit.map(::factKey).toSet()
    return (saved + fallback.distinctBy(::factKey).filter { factKey(it) !in recorded })
        .sortedWith(compareByDescending<WarehouseEvent> { it.occurredAt }.thenBy { it.recordId }).take(limit)
}
