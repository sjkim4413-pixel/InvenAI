package com.example.myapp

import android.content.Intent
import android.os.Bundle
import com.google.firebase.firestore.ListenerRegistration

class ScanResult : WarehouseScreen() {
    private var listener: ListenerRegistration? = null
    private lateinit var code: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openScreen("스캔 결과")
        code = intent.getStringExtra(BarcodeScanner.RESULT_CODE).orEmpty()
        label(if (code.isBlank()) "스캔한 코드가 없습니다." else "스캔한 코드 · $code", true)
        button("다시 스캔", icon = R.drawable.ic_inven_barcode) { startActivity(Intent(this, BarcodeScanner::class.java)); finish() }
    }

    override fun onStart() {
        super.onStart()
        if (code.isBlank()) return
        listener = InventoryManager.listenToRealtimeInventory(this, onError = {
            render(emptyList(), it)
        }) { items -> render(items.filter { it.matchesScan(code) }) }
    }

    override fun onStop() {
        listener?.remove()
        listener = null
        super.onStop()
    }

    private fun render(items: List<InventoryItem>, error: String? = null) {
        body.removeAllViews()
        label("스캔한 코드 · $code", true)
        if (error != null) label(error)
        else if (items.isEmpty()) {
            label("현재 회사 재고에서 이 코드를 찾을 수 없습니다.")
            button("이 코드로 신규 입고", primary = true, icon = R.drawable.ic_inven_inventory) {
                startActivity(Intent(this, Inbound::class.java).putExtra("SCANNED_ITEM_CODE", code))
            }
        } else items.forEach { item ->
            body.addView(warehouseRow(this, item.itemName,
                "${item.warehouse.ifBlank { "창고 미등록" }} · ${item.location}\n${item.quantity}개 · ${item.statusLabel()}") {
                startActivity(inventoryIntent(this, InventoryDetail::class.java, item))
            })
        }
        button("다시 스캔", icon = R.drawable.ic_inven_barcode) { startActivity(Intent(this, BarcodeScanner::class.java)); finish() }
    }
}
