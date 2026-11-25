package com.example.myapp

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import com.google.firebase.firestore.ListenerRegistration

class ErpWmsConnection : WarehouseScreen() {
    private var listener: ListenerRegistration? = null
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openScreen("회사 재고 데이터")
        val source = label("회사 재고 연결", true)
        val company = label("회사 · ${InventoryManager.activeCompanyCode(this) ?: "로그인 필요"}")
        status = label("연결을 확인하는 중입니다.")
        card(source, company, status)
        button("연결 다시 확인", icon = R.drawable.ic_inven_link) { connect() }
        if (InventoryManager.role(this) == "QA") {
            button("CSV 재고 가져오기", primary = true, icon = R.drawable.ic_inven_file) { startActivity(Intent(this, FileUpload::class.java)) }
        }
    }

    override fun onStart() {
        super.onStart()
        connect()
    }

    override fun onStop() {
        listener?.remove()
        listener = null
        super.onStop()
    }

    private fun connect() {
        listener?.remove()
        status.text = "연결을 확인하는 중입니다."
        listener = InventoryManager.listenToRealtimeInventory(this, onError = { status.text = it }) { stock ->
            val updated = stock.maxOfOrNull { it.updatedAtTimestamp } ?: 0L
            status.text = "연결됨 · 재고 위치 ${stock.size}건 · 총 ${stock.sumOf { it.quantity.toLong() }}개\n최근 갱신 · ${inventoryDate(updated)}"
        }
    }
}
