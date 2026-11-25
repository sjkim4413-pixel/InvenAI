package com.example.myapp

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.ListenerRegistration

class InventoryList : WarehouseScreen() {
    private var listener: ListenerRegistration? = null
    private var inventory = emptyList<InventoryItem>()
    private var warehouse: String? = null
    private var zone: String? = null
    private var normalOnly = false
    private var status: String? = "재고를 불러오는 중입니다."
    private lateinit var list: LinearLayout
    private lateinit var warehouseButton: MaterialButton
    private lateinit var zoneButton: MaterialButton
    private lateinit var filter: MaterialButton
    private var search = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openScreen("실시간 재고 목록")
        warehouse = savedInstanceState?.getString("warehouse") ?: intent.getStringExtra("WAREHOUSE_CODE")
        zone = savedInstanceState?.getString("zone")
        normalOnly = savedInstanceState?.getBoolean("normalOnly") ?: false
        search = savedInstanceState?.getString("search").orEmpty()
        field("상품명, 코드, 랙 위치 검색").apply {
            setText(search)
            doAfterTextChanged { search = it.toString().trim(); render() }
        }
        warehouseButton = selector("") {
            val warehouses = inventory.map { it.warehouse }.filter(String::isNotBlank).distinct().sorted()
            choose("창고 선택", listOf("전체 창고") + warehouses, warehouses.indexOf(warehouse) + 1) { index ->
                warehouse = warehouses.getOrNull(index - 1)
                zone = null
                render()
            }
        }
        zoneButton = selector("") {
            val zones = warehouseItems().mapNotNull { it.effectiveZone() }.distinct().sorted()
            choose("구역 선택", listOf("전체 구역") + zones, zones.indexOf(zone) + 1) { index ->
                zone = zones.getOrNull(index - 1)
                render()
            }
        }
        actionRow(warehouseButton, zoneButton)
        filter = button("", icon = R.drawable.ic_inven_check_circle) { normalOnly = !normalOnly; render() }
            .apply { isCheckable = true }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; body.addView(this) }
        render()
    }

    override fun onStart() {
        super.onStart()
        listener = InventoryManager.listenToRealtimeInventory(this, onError = {
            status = it
            render()
        }) {
            inventory = it
            status = null
            render()
        }
    }

    override fun onStop() {
        listener?.remove()
        listener = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("warehouse", warehouse)
        outState.putString("zone", zone)
        outState.putString("search", search)
        outState.putBoolean("normalOnly", normalOnly)
        super.onSaveInstanceState(outState)
    }

    private fun warehouseItems() = inventory.filter { warehouse == null || it.warehouse == warehouse }

    private fun render() {
        warehouseButton.text = warehouse ?: "전체 창고"
        zoneButton.text = zone ?: "전체 구역"
        filter.text = if (normalOnly) "정상 재고만 표시 중" else "모든 상태 표시 중"
        filter.isChecked = normalOnly
        list.removeAllViews()
        val items = warehouseItems().filter { item ->
            (zone == null || item.effectiveZone() == zone) &&
                (!normalOnly || item.statusLabel() == "정상") &&
                listOf(item.itemCode, item.itemName, item.location).any { it.contains(search, ignoreCase = true) }
        }.sortedWith(compareBy({ it.warehouse }, { it.location }, { it.itemName }))
        if (status != null || items.isEmpty()) {
            list.addView(TextView(this).apply {
                text = status ?: "조건에 맞는 재고가 없습니다."
                textSize = 14f
                setTextColor(WarehouseAppearance.MUTED)
                setPadding(dp(4), dp(20), dp(4), dp(20))
            })
        } else items.forEach { item ->
            val location = "${item.warehouse.ifBlank { "창고 미등록" }} · ${item.location}"
            list.addView(warehouseRow(this, "${item.itemName} · ${item.itemCode}",
                "$location\n${item.quantity}개 · ${item.statusLabel()}") {
                startActivity(inventoryIntent(this, InventoryDetail::class.java, item))
            })
        }
    }

    private fun InventoryItem.effectiveZone(): String? =
        zone.ifBlank { Regex("([A-Za-z0-9]+)\\s*구역").find(location)?.value.orEmpty() }.ifBlank { null }
}
