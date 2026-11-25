package com.example.myapp

import android.content.Context
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source

object InventoryManager {
    private const val COLLECTION = "inventory"
    private val locationMetadata = listOf("loc", "zone", "rack", "rackId", "tier", "floor", "side")

    internal fun activeCompanyCode(context: Context): String? {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return null
        val session = context.getSharedPreferences("app_user_session", Context.MODE_PRIVATE)
        if (session.getString("FIREBASE_UID", null) != uid) return null
        return session.getString("COMPANY_CODE", null)?.takeIf { it.isNotBlank() }
    }

    internal fun role(context: Context): String? = if (activeCompanyCode(context) == null) null
        else context.getSharedPreferences("app_user_session", Context.MODE_PRIVATE).getString("ROLE", null)

    internal fun document(company: String, item: InventoryItem): DocumentReference =
        FirebaseFirestore.getInstance().collection(COLLECTION)
            .document(item.documentId.ifEmpty { "${company}_${item.itemCode}" })

    internal fun writeMetadata(actor: String, event: DocumentReference): Map<String, Any> =
        mapOf("userId" to actor, "updatedAt" to FieldValue.serverTimestamp(), "lastEventId" to event.id)

    internal fun replaceLocationMetadata(): Map<String, Any> =
        locationMetadata.associateWith { FieldValue.delete() }

    private fun companyInventory(company: String) =
        FirebaseFirestore.getInstance().collection(COLLECTION).whereEqualTo("companyCode", company)

    fun listenToRealtimeInventory(
        context: Context,
        onError: (String) -> Unit = {},
        onUpdate: (List<InventoryItem>) -> Unit
    ): ListenerRegistration? {
        val company = activeCompanyCode(context) ?: run {
            onError("다시 로그인해 주세요.")
            return null
        }
        return companyInventory(company).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (activeCompanyCode(context) != company) return@addSnapshotListener
            if (error != null) {
                Log.w("InventoryManager", "Inventory snapshot listen failed", error)
                onError("실시간 재고를 불러오지 못했습니다.")
            } else if (snapshot != null && !snapshot.metadata.isFromCache && !snapshot.metadata.hasPendingWrites()) {
                onUpdate(snapshot.documents.map(::readItem))
            }
        }
    }

    internal fun readItem(doc: DocumentSnapshot): InventoryItem {
        fun text(field: String) = doc.get(field) as? String
        fun number(field: String) = doc.get(field) as? Number
        fun timestamp(field: String) = (doc.get(field) as? Timestamp)?.toDate()?.time
        val code = text("itemCode") ?: text("code") ?: doc.id
        return InventoryItem(
            itemCode = code,
            itemName = text("itemName") ?: text("name") ?: text("title") ?: code,
            quantity = (number("quantity") ?: number("qty"))?.toInt() ?: 0,
            location = text("location") ?: text("loc") ?: "미지정 위치",
            status = text("status") ?: "NORMAL",
            pendingQuantity = number("pendingQuantity")?.toInt() ?: 0,
            pendingLocation = text("pendingLocation").orEmpty(),
            updatedAtTimestamp = timestamp("updatedAt") ?: number("updatedAtTimestamp")?.toLong()
                ?: timestamp("createdAt") ?: 0L,
            userId = text("userId") ?: text("user").orEmpty(),
            targetLocation = text("targetLocation").orEmpty(),
            relocationDone = doc.get("relocationDone") == true,
            warehouse = text("warehouse").orEmpty(),
            zone = text("zone").orEmpty(),
            rack = text("rack").orEmpty(),
            tier = text("tier") ?: number("tier")?.toString().orEmpty(),
            rackId = text("rackId").orEmpty(),
            documentId = doc.id,
            palletId = text("palletId").orEmpty(),
            receivedAtTimestamp = timestamp("firstReceivedAt") ?: timestamp("createdAt") ?: 0L,
            barcodes = (listOfNotNull(text("barcode"), text("ean"), text("upc")) +
                (doc.get("barcodes") as? List<*>).orEmpty().filterIsInstance<String>())
                .filter(String::isNotBlank).distinct(),
            isTestData = doc.get("isTestData") == true
        )
    }

    internal fun findStockAtLocation(
        context: Context,
        warehouse: String,
        location: String,
        onResult: (List<InventoryItem>) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val company = activeCompanyCode(context) ?: return onFailure("다시 로그인해 주세요.")
        companyInventory(company).whereEqualTo("warehouse", warehouse).whereEqualTo("location", location)
            .get(Source.SERVER)
            .addOnSuccessListener { matches ->
                if (activeCompanyCode(context) == company) onResult(matches.documents.map(::readItem))
                else onFailure("다시 로그인해 주세요.")
            }
            .addOnFailureListener { onFailure("전산 위치의 재고를 불러오지 못했습니다.") }
    }

    internal fun saveStock(
        context: Context,
        item: InventoryItem,
        action: WarehouseAction,
        quantity: Int,
        destination: String = item.location,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val company = activeCompanyCode(context) ?: return onFailure("다시 로그인해 주세요.")
        val actor = FirebaseAuth.getInstance().currentUser?.uid ?: return onFailure("다시 로그인해 주세요.")
        if (action in setOf(WarehouseAction.OUTBOUND, WarehouseAction.CORRECTION, WarehouseAction.IMPORT) &&
            role(context) != "QA"
        ) return onFailure("검수자 권한이 필요합니다.")
        if (item.itemCode.isBlank() || item.itemCode.length > 120 || '/' in item.itemCode ||
            item.itemName.isBlank() || item.itemName.length > 240 || item.warehouse.length > 160 ||
            destination.isBlank() || destination.length > 160 || quantity < 0
        ) return onFailure("상품 코드, 상품명, 수량과 위치를 확인해 주세요.")

        val db = FirebaseFirestore.getInstance()
        val target = document(company, item)
        val event = WarehouseHistory.newEvent(company)
        db.runTransaction { transaction ->
            val snapshot = transaction.get(target)
            val previous = if (snapshot.exists()) {
                check(snapshot.getString("companyCode") == company) { "다른 회사의 재고에는 접근할 수 없습니다." }
                readItem(snapshot)
            } else null
            val after = applyStockChange(previous, item.copy(documentId = target.id), action, quantity, destination)
            if (action == WarehouseAction.IMPORT && previous == after) return@runTransaction
            val fields = mapOf(
                "companyCode" to company, "itemCode" to after.itemCode, "itemName" to after.itemName,
                "quantity" to after.quantity, "location" to after.location, "warehouse" to after.warehouse,
                "status" to after.status, "pendingQuantity" to 0, "pendingLocation" to ""
            ) + writeMetadata(actor, event)
            if (previous == null) {
                transaction.set(target, fields + ("firstReceivedAt" to FieldValue.serverTimestamp()))
            } else {
                val changes = if (previous.location != after.location) fields + replaceLocationMetadata() else fields
                transaction.update(target, changes)
            }
            WarehouseHistory.write(transaction, event, actor, action, previous, after)
        }.addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it.localizedMessage ?: "재고를 저장하지 못했습니다.") }
    }

    fun requestReview(
        context: Context,
        item: InventoryItem,
        pendingLocation: String,
        pendingQuantity: Int,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val company = activeCompanyCode(context) ?: return onFailure("다시 로그인해 주세요.")
        val actor = FirebaseAuth.getInstance().currentUser?.uid ?: return onFailure("다시 로그인해 주세요.")
        if (role(context) != "LOADER") return onFailure("적재자 권한이 필요합니다.")
        if (pendingLocation.isBlank() || pendingLocation.length > 160 || pendingQuantity < 0)
            return onFailure("조치한 위치와 수량을 확인해 주세요.")
        val inventory = document(company, item)
        val event = WarehouseHistory.newEvent(company)
        FirebaseFirestore.getInstance().runTransaction { transaction ->
            val snapshot = transaction.get(inventory)
            check(snapshot.exists()) { "재고를 찾을 수 없습니다." }
            val latest = readItem(snapshot)
            check(latest.status == "ANOMALY") { "조치 요청 상태가 변경되었습니다." }
            val data = mapOf(
                "status" to "PENDING_QA", "pendingLocation" to pendingLocation, "pendingQuantity" to pendingQuantity
            ) + writeMetadata(actor, event)
            transaction.update(inventory, data)
            WarehouseHistory.write(transaction, event, actor, WarehouseAction.REVIEW_REQUEST, latest,
                latest.copy(location = pendingLocation, quantity = pendingQuantity))
        }.addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it.localizedMessage ?: "승인 요청 실패") }
    }

    fun requestReverification(
        context: Context,
        itemCode: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val company = activeCompanyCode(context) ?: return onFailure("다시 로그인해 주세요.")
        companyInventory(company).whereEqualTo("itemCode", itemCode).limit(2).get(Source.SERVER)
            .addOnSuccessListener { matches ->
                val snapshot = matches.documents.singleOrNull()
                if (snapshot == null || activeCompanyCode(context) != company) {
                    onFailure("재검증할 재고를 한 건으로 확인하지 못했습니다.")
                } else {
                    val item = readItem(snapshot)
                    requestReview(context, item, item.location, item.quantity, onSuccess, onFailure)
                }
            }
            .addOnFailureListener { onFailure(it.localizedMessage ?: "품목 조회 실패") }
    }
}
