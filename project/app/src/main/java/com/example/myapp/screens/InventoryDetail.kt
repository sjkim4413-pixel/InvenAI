package com.example.myapp

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.google.firebase.firestore.ListenerRegistration

class InventoryDetail : WarehouseScreen() {
    private var listener: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openScreen("재고 상세")
        label("재고 정보를 불러오는 중입니다.")
    }

    override fun onStart() {
        super.onStart()
        listener = InventoryManager.listenToRealtimeInventory(this, onError = {
            body.removeAllViews()
            label(it)
        }) { items ->
            val id = intent.getStringExtra("INVENTORY_ID")
            val sku = intent.getStringExtra("ITEM_CODE")
            val item = if (id.isNullOrBlank()) items.singleOrNull { sku != null && it.itemCode == sku }
                else items.firstOrNull { it.documentId == id }
            render(item)
        }
    }

    override fun onStop() {
        listener?.remove()
        listener = null
        super.onStop()
    }

    private fun render(item: InventoryItem?) {
        body.removeAllViews()
        if (item == null) {
            label("현재 회사 재고에서 해당 항목을 확인할 수 없습니다.")
            return
        }
        val name = label(item.itemName, true)
        val summary = label("상품 코드 · ${item.itemCode}\n현재고 · ${item.quantity}개\n상태 · ${item.statusLabel()}")
        card(name, summary)
        val positionTitle = label("보관 정보", true)
        val position = label(buildString {
            append("창고 · ${item.warehouse.ifBlank { "미등록" }}\n위치 · ${item.location}")
            if (item.rack.isNotBlank()) append("\n랙 · ${item.rack}")
            if (item.tier.isNotBlank()) append("\n단 · ${item.tier}")
            if (item.palletId.isNotBlank()) append("\n팔레트 ID · ${item.palletId}")
        })
        val updated = label(buildString {
            if (item.receivedAtTimestamp > 0) append("등록 · ${inventoryDate(item.receivedAtTimestamp)}\n")
            append("최근 갱신 · ${inventoryDate(item.updatedAtTimestamp)}")
        }).apply { textSize = 12f }
        card(positionTitle, position, updated)

        if (item.needsReview) {
            button("이상 재고 처리", primary = true, icon = R.drawable.ic_inven_alert) { startActivity(Intent(this, AnomalyInventory::class.java)) }
            return
        }
        val inbound = button("입고 등록", primary = true, icon = R.drawable.ic_inven_inventory) { startActivity(inventoryIntent(this, Inbound::class.java, item)) }
        if (item.quantity > 0 && item.warehouse.isNotBlank()) {
            val transfer = button("재고 이동", icon = R.drawable.ic_inven_transfer) { startActivity(inventoryIntent(this, Transfer::class.java, item)) }
            actionRow(inbound, transfer)
        }
        if (InventoryManager.role(this) == "QA") {
            val outbound = if (item.quantity > 0) button("출고 처리") { startActivity(inventoryIntent(this, Outbound::class.java, item)) } else null
            val correction = button("실측 수량 수정") { startActivity(inventoryIntent(this, StockCorrection::class.java, item)) }
            if (outbound != null) actionRow(outbound, correction)
            if (item.status in setOf("NORMAL", "정상")) button("오적재 조치 요청", icon = R.drawable.ic_inven_alert) { requestAction(item) }
        }
    }

    private fun requestAction(item: InventoryItem) {
        val destination = EditText(this).apply { hint = "목표 위치 (확인된 경우 입력)"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("${item.itemName} · 오적재 조치 요청")
            .setView(destination).setNegativeButton("취소", null)
            .setPositiveButton("요청") { _, _ ->
                AnomalyManager.sendMisplacementAlert(this, item, destination.text.toString())
            }.show()
    }
}
