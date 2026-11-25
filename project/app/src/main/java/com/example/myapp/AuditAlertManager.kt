package com.example.myapp

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

internal data class AuditAlertQueueState(val owner: String, val pending: Set<String>, val sending: Set<String>)

internal object AuditAlertManager {
    const val COLLECTION = "auditAlerts"
    private val gson = Gson()
    private val sending = mutableSetOf<String>()
    private val state = MutableLiveData<AuditAlertQueueState>()
    val queueState: LiveData<AuditAlertQueueState> = state
    private fun store(context: Context) = context.getSharedPreferences("audit_alert_outbox", Context.MODE_PRIVATE)
    private fun owner(context: Context): String? {
        val company = InventoryManager.activeCompanyCode(context) ?: return null
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return null
        return "$company:$uid"
    }
    private fun queued(context: Context, key: String): List<AuditMisplacementAlert> = runCatching {
        gson.fromJson<List<AuditMisplacementAlert>>(store(context).getString(key, "[]"),
            object : TypeToken<List<AuditMisplacementAlert>>() {}.type).orEmpty().filter {
                it.id.matches(Regex("AI_[a-f0-9]{64}")) && it.companyCode.isNotBlank() && it.qaUid.isNotBlank() &&
                    it.jobId.matches(Regex("[a-f0-9]{32}"))
            }
    }.getOrDefault(emptyList())
    private fun publish(context: Context, key: String) {
        state.value = AuditAlertQueueState(key, queued(context, key).map { it.id }.toSet(), sending.toSet())
    }
    private fun save(context: Context, key: String, alerts: List<AuditMisplacementAlert>) {
        store(context).edit().putString(key, gson.toJson(alerts)).apply()
        publish(context, key)
    }

    fun send(context: Context, alerts: List<AuditMisplacementAlert>) {
        val key = owner(context) ?: return
        if (InventoryManager.role(context) != "QA") return
        val allowed = alerts.filter { "${it.companyCode}:${it.qaUid}" == key }
        save(context, key, (queued(context, key) + allowed).distinctBy { it.id })
        flush(context)
    }

    fun flush(context: Context) {
        val key = owner(context) ?: return
        if (InventoryManager.role(context) != "QA") return
        for (alert in queued(context, key)) {
            if ("${alert.companyCode}:${alert.qaUid}" != key || !sending.add(alert.id)) continue
            publish(context, key)
            val ref = FirebaseFirestore.getInstance().collection(COLLECTION).document(alert.id)
            val history = AuditHistory.newEvent(alert.companyCode)
            FirebaseFirestore.getInstance().runTransaction { transaction ->
                val existing = transaction.get(ref)
                if (existing.exists()) {
                    check(existing.getString("companyCode") == alert.companyCode && existing.getString("qaUid") == alert.qaUid &&
                        existing.getString("jobId") == alert.jobId && existing.getString("location") == alert.location &&
                        existing.getString("expectedItemCode") == alert.expectedItemCode &&
                        existing.getString("observedItemCode") == alert.observedItemCode) { "알림 기준이 변경되었습니다." }
                } else {
                    transaction.set(ref, documentFields(alert) + ("lastHistoryEventId" to history.id))
                    AuditHistory.write(transaction, history, alert, alert.qaUid, "", "OPEN", "")
                }
                true
            }.addOnSuccessListener {
                sending.remove(alert.id)
                F06PushOutbox.enqueue(context, alert.id)
                save(context, key, queued(context, key).filterNot { it.id == alert.id })
            }.addOnFailureListener {
                sending.remove(alert.id)
                publish(context, key)
            }
        }
    }

    internal fun documentFields(alert: AuditMisplacementAlert): Map<String, Any> = mapOf(
                        "companyCode" to alert.companyCode, "qaUid" to alert.qaUid, "jobId" to alert.jobId,
                        "inventoryId" to alert.inventoryId, "warehouse" to alert.warehouse, "location" to alert.location,
                        "expectedItemCode" to alert.expectedItemCode, "observedItemCode" to alert.observedItemCode,
                        "expectedItemName" to alert.expectedItemName, "observedItemName" to alert.observedItemName,
                        "targetLocation" to alert.targetLocation, "isTest" to alert.isTest,
                        "status" to "OPEN", "responderUid" to "", "note" to "",
                        "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp()
                    )

    fun listen(context: Context, onError: (String) -> Unit = {}, onUpdate: (List<AuditMisplacementAlert>) -> Unit): ListenerRegistration? {
        val company = InventoryManager.activeCompanyCode(context) ?: return null
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return null
        return FirebaseFirestore.getInstance().collection(COLLECTION).whereEqualTo("companyCode", company)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (InventoryManager.activeCompanyCode(context) != company || FirebaseAuth.getInstance().currentUser?.uid != uid) return@addSnapshotListener
                if (error != null) onError("실사 오적재 알림을 불러오지 못했습니다.")
                else if (snapshot != null && !snapshot.metadata.isFromCache && !snapshot.metadata.hasPendingWrites()) {
                    onUpdate(snapshot.documents.mapNotNull(::read).filter { it.status != "RESOLVED" }.sortedByDescending { it.updatedAt })
                }
            }
    }

    fun changeStatus(context: Context, alert: AuditMisplacementAlert, status: String, note: String,
                     onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return onFailure("다시 로그인해 주세요.")
        if (InventoryManager.activeCompanyCode(context) != alert.companyCode) return onFailure("같은 회사의 알림만 처리할 수 있습니다.")
        val loader = InventoryManager.role(context) == "LOADER"
        if ((loader && (alert.status != "OPEN" || status != "REVIEW_REQUESTED")) ||
            (!loader && InventoryManager.role(context) != "QA") || note.length > 500) return onFailure("알림 상태와 권한을 확인해 주세요.")
        val ref = FirebaseFirestore.getInstance().collection(COLLECTION).document(alert.id)
        val history = AuditHistory.newEvent(alert.companyCode)
        FirebaseFirestore.getInstance().runTransaction { transaction ->
            val current = transaction.get(ref)
            check(current.getString("companyCode") == alert.companyCode && current.getString("status") == alert.status &&
                (alert.updatedAt == 0L || current.getTimestamp("updatedAt")?.toDate()?.time == alert.updatedAt)) {
                "이미 처리되었거나 알림 상태가 변경되었습니다."
            }
            transaction.update(ref, mapOf("status" to status, "note" to note,
                "responderUid" to if (loader) uid else if (status == "OPEN") "" else current.getString("responderUid").orEmpty(),
                "updatedAt" to FieldValue.serverTimestamp(), "lastHistoryEventId" to history.id))
            AuditHistory.write(transaction, history, requireNotNull(read(current)), uid, alert.status, status, note)
        }.addOnSuccessListener { onSuccess() }.addOnFailureListener { onFailure(it.localizedMessage ?: "알림 처리 실패") }
    }

    internal fun read(doc: DocumentSnapshot): AuditMisplacementAlert? = runCatching {
        fun text(key: String) = requireNotNull(doc.getString(key))
        AuditMisplacementAlert(doc.id, text("companyCode"), text("qaUid"), text("jobId"), text("inventoryId"),
            text("warehouse"), text("location"), text("expectedItemCode"), text("observedItemCode"),
            text("expectedItemName"), text("observedItemName"), text("targetLocation"), doc.getBoolean("isTest") == true,
            text("status"), text("responderUid"), text("note"), (doc.get("updatedAt") as? Timestamp)?.toDate()?.time ?: 0L)
    }.getOrNull()
}
