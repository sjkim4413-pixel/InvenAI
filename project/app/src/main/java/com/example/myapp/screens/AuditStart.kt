package com.example.myapp

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.ListenerRegistration

class AuditStart : WarehouseScreen() {
    private var listener: ListenerRegistration? = null
    private var warehouse: String? = null
    private var choices = emptyList<String>()
    private var images = emptyList<AuditTestImage>()
    private var ready = false
    private var problem: String? = null
    private lateinit var status: TextView
    private lateinit var warehouseSelector: MaterialButton
    private lateinit var openCamera: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openScreen("AI 재고 실사")
        warehouse = savedInstanceState?.getString("warehouse") ?: intent.getStringExtra("WAREHOUSE_CODE")
        label("랙 사진을 촬영하거나 앨범에서 선택해 전산 재고와 비교합니다.")
        images = runCatching { loadAuditTestImages(this) }.getOrDefault(emptyList())
        status = label("회사 창고를 불러오는 중입니다.", true).apply { id = R.id.auditWarehouseStatus }
        card(status)
        warehouseSelector = selector("실사 창고 선택") {
            choose("실사 창고 선택", choices, choices.indexOf(warehouse)) { selected ->
                warehouse = choices.getOrNull(selected)
                render()
            }
        }.apply { id = R.id.auditSelectWarehouse }
        openCamera = button("카메라 또는 앨범 열기", primary = true, icon = R.drawable.ic_inven_camera) {
            if (!ready || warehouse !in choices) return@button
            startActivity(Intent(this, AiAuditCamera::class.java).putExtra("WAREHOUSE_CODE", warehouse))
        }.apply { id = R.id.auditOpenCamera }
        render()
    }

    override fun onStart() {
        super.onStart()
        ready = false
        problem = null
        render()
        listener = InventoryManager.listenToRealtimeInventory(this, onError = {
            ready = false
            problem = it
            render()
        }) {
            choices = auditWarehouseChoices(it, images)
            if (warehouse !in choices) warehouse = null
            ready = true
            problem = null
            render()
        }
    }

    private fun render() {
        warehouseSelector.isEnabled = ready && choices.isNotEmpty()
        warehouseSelector.text = warehouse ?: "실사 창고 선택"
        openCamera.isEnabled = ready && warehouse in choices
        status.text = when {
            problem != null -> problem
            !ready -> "회사 창고를 불러오는 중입니다."
            choices.isEmpty() -> "실사할 창고가 없습니다. 회사의 전산 재고를 확인해 주세요."
            warehouse == null -> "실사할 창고를 먼저 선택해 주세요."
            else -> {
                val count = auditImagesForWarehouse(images, warehouse).size
                "실사 창고 · $warehouse\n" + if (count > 0) "연결된 테스트 이미지 ${count}장" else "이 창고의 전산 재고와 사진을 비교합니다."
            }
        }
    }

    override fun onStop() {
        listener?.remove()
        listener = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("warehouse", warehouse)
        super.onSaveInstanceState(outState)
    }
}
