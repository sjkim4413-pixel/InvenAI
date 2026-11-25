package com.example.myapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.ListenerRegistration

abstract class StockOperationScreen : WarehouseScreen() {
    internal abstract val operation: WarehouseAction
    private var inventory = emptyList<InventoryItem>()
    private var listener: ListenerRegistration? = null
    private var selectedId: String? = null
    private var enteringNew = false
    private var loading = true
    private var saving = false
    private var scanDestination = false
    private lateinit var summary: TextView
    private lateinit var sku: EditText
    private lateinit var name: EditText
    private lateinit var warehouse: EditText
    private lateinit var location: EditText
    private lateinit var amount: EditText
    private lateinit var amountLabel: TextView
    private lateinit var submit: MaterialButton
    private lateinit var details: TextView
    private lateinit var warehouseButton: MaterialButton

    private val scanner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { reply ->
        if (reply.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val value = reply.data?.getStringExtra(BarcodeScanner.RESULT_CODE) ?: return@registerForActivityResult
        if (scanDestination) location.setText(value)
        else applyProductCode(value)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openScreen(operation.label)
        selectedId = savedInstanceState?.getString("selectedStock") ?: intent.getStringExtra("INVENTORY_ID")
        enteringNew = savedInstanceState?.getBoolean("enteringNew") ?: false
        scanDestination = savedInstanceState?.getBoolean("scanDestination") ?: false

        summary = label("실시간 재고를 불러오는 중입니다.", true)
        val selectButton = selector("재고 선택") { selectStock(options()) }
        val scanStock = button("바코드 스캔", icon = R.drawable.ic_inven_barcode) {
            scanDestination = false
            scanner.launch(Intent(this, BarcodeScanner::class.java).putExtra(BarcodeScanner.RETURN_RESULT, true))
        }
        actionRow(selectButton, scanStock)
        if (operation == WarehouseAction.INBOUND) button("신규 상품 입력", icon = R.drawable.ic_inven_edit) {
            enteringNew = true
            selectedId = null
            sku.text.clear()
            name.text.clear()
            warehouse.text.clear()
            location.text.clear()
            renderSelection()
        }

        details = label("")
        card(summary, details)
        sku = field("상품 코드 또는 바코드")
        name = field("상품명")
        warehouse = field("창고")
        warehouseButton = selector("등록된 창고 선택") {
            val warehouses = inventory.map { it.warehouse }.filter { it.isNotBlank() }.distinct().sorted()
            choose("창고 선택", warehouses) { warehouse.setText(warehouses[it]) }
        }
        location = field(if (operation == WarehouseAction.TRANSFER) "목적지 (예: B구역 R07-1층)" else "보관 위치 (예: B구역 R07-1층)")
        if (operation == WarehouseAction.TRANSFER) button("목적지 QR / 위치 코드 스캔", icon = R.drawable.ic_inven_barcode) {
            scanDestination = true
            scanner.launch(Intent(this, BarcodeScanner::class.java)
                .putExtra(BarcodeScanner.RETURN_RESULT, true).putExtra(BarcodeScanner.LOCATION_MODE, true))
        }
        val quantityTitle = when (operation) {
                WarehouseAction.CORRECTION -> "확인한 수량"
                WarehouseAction.OUTBOUND -> "출고 수량"
                else -> "입고 수량"
            }
        amountLabel = label(quantityTitle)
        amount = field(quantityTitle, numeric = true)
        submit = button("${operation.label} 저장", primary = true, icon = R.drawable.ic_inven_check) { confirmChange() }
        for (input in listOf(sku, name, warehouse, location, amount)) {
            input.doAfterTextChanged { if (::submit.isInitialized) updateButton() }
        }

        if (savedInstanceState != null) {
            sku.setText(savedInstanceState.getString("sku"))
            name.setText(savedInstanceState.getString("itemName"))
            warehouse.setText(savedInstanceState.getString("warehouse"))
            location.setText(savedInstanceState.getString("location"))
            amount.setText(savedInstanceState.getString("amount"))
        }
        renderSelection()
    }

    override fun onStart() {
        super.onStart()
        listener = InventoryManager.listenToRealtimeInventory(this, onError = {
            loading = true
            summary.text = it
            updateButton()
        }) {
            val firstLoad = loading
            inventory = it
            loading = false
            if (firstLoad && selectedId == null && !enteringNew) {
                intent.getStringExtra("SCANNED_ITEM_CODE")?.let(::applyProductCode)
            }
            renderSelection()
        }
    }

    override fun onStop() {
        listener?.remove()
        listener = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("selectedStock", selectedId)
        outState.putBoolean("enteringNew", enteringNew)
        outState.putBoolean("scanDestination", scanDestination)
        outState.putString("sku", sku.text.toString())
        outState.putString("itemName", name.text.toString())
        outState.putString("warehouse", warehouse.text.toString())
        outState.putString("location", location.text.toString())
        outState.putString("amount", amount.text.toString())
    }

    private fun options(): List<InventoryItem> = inventory.filter { !it.needsReview &&
        (operation !in setOf(WarehouseAction.OUTBOUND, WarehouseAction.TRANSFER) || it.quantity > 0) }

    private fun selectStock(items: List<InventoryItem>) {
        choose("재고 선택", items.map { "${it.itemName} · ${it.itemCode}\n${it.warehouse} · ${it.location} · ${it.quantity}개" }) {
            enteringNew = false
            selectedId = items[it].documentId
            if (operation == WarehouseAction.TRANSFER) location.text.clear()
            renderSelection()
        }
    }

    private fun applyProductCode(code: String) {
        val matches = options().filter { it.matchesScan(code) }
        if (matches.size == 1) {
            enteringNew = false
            selectedId = matches.single().documentId
            renderSelection()
        } else if (matches.isNotEmpty()) {
            selectStock(matches)
        } else if (operation == WarehouseAction.INBOUND && inventory.none { it.matchesScan(code) }) {
            enteringNew = true
            selectedId = null
            sku.setText(code)
            name.text.clear()
            renderSelection()
        } else {
            message("이 작업에 사용할 수 있는 회사 재고에서 해당 코드를 찾지 못했습니다.")
        }
    }

    private fun item(): InventoryItem? = inventory.firstOrNull { it.documentId == selectedId }

    private fun renderSelection() {
        val item = item()
        summary.text = when {
            loading -> "실시간 재고를 불러오는 중입니다."
            enteringNew -> "신규 상품"
            item != null -> item.itemName
            else -> "재고를 선택하거나 상품 바코드를 스캔해 주세요."
        }
        details.text = item?.let {
            "${it.itemCode}\n${it.warehouse.ifBlank { "창고 미등록" }} · ${it.location}\n현재고 ${it.quantity}개 · ${it.statusLabel()}"
        }.orEmpty()
        details.visibility = if (item == null) View.GONE else View.VISIBLE
        listOf(sku, name, warehouse, warehouseButton).forEach {
            it.visibility = if (enteringNew) View.VISIBLE else View.GONE
        }
        location.visibility = if (enteringNew || operation == WarehouseAction.TRANSFER) View.VISIBLE else View.GONE
        amount.visibility = if (operation == WarehouseAction.TRANSFER) View.GONE else View.VISIBLE
        amountLabel.visibility = amount.visibility
        updateButton()
    }

    private fun updateButton() {
        val count = amount.text.toString().toIntOrNull()
        val entered = if (enteringNew) listOf(sku, name, warehouse, location).all { it.text.isNotBlank() } else item() != null
        val valid = when (operation) {
            WarehouseAction.TRANSFER -> location.text.isNotBlank()
            WarehouseAction.CORRECTION -> count != null && count >= 0
            WarehouseAction.OUTBOUND -> count != null && count in 1..(item()?.quantity ?: 0)
            else -> count != null && count > 0
        }
        val canAct = operation !in setOf(WarehouseAction.OUTBOUND, WarehouseAction.CORRECTION) ||
            InventoryManager.role(this) == "QA"
        submit.isEnabled = !loading && !saving && canAct && entered && valid
    }

    private fun confirmChange() {
        val count = if (operation == WarehouseAction.TRANSFER) 0 else amount.text.toString().toIntOrNull() ?: return
        val stock = if (enteringNew) {
            val code = sku.text.toString().trim()
            if (inventory.any { it.matchesScan(code) }) {
                return message("이미 회사 재고에 등록된 상품 코드입니다. 재고 선택에서 해당 항목을 선택해 주세요.")
            }
            InventoryItem(itemCode = code, itemName = name.text.toString().trim(), warehouse = warehouse.text.toString().trim(),
                location = location.text.toString().trim())
        } else item() ?: return
        val destination = if (operation == WarehouseAction.TRANSFER) location.text.toString().trim() else stock.location
        val operationDetail = if (operation == WarehouseAction.TRANSFER) "${stock.location} → $destination" else "$count 개"
        AlertDialog.Builder(this).setTitle("${operation.label} 확인")
            .setMessage("${stock.itemName} (${stock.itemCode})\n$operationDetail")
            .setNegativeButton("취소", null)
            .setPositiveButton("저장") { _, _ ->
                saving = true
                updateButton()
                InventoryManager.saveStock(this, stock, operation, count, destination, {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, "${operation.label}이 저장되었습니다.", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }, { error ->
                    saving = false
                    if (!isFinishing && !isDestroyed) {
                        updateButton()
                        message(error)
                    }
                })
            }.show()
    }
}
