package com.example.myapp

import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.ListenerRegistration
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class FileUpload : WarehouseScreen() {
    private val worker = Executors.newSingleThreadExecutor()
    private var subscription: ListenerRegistration? = null
    private var inventory: List<InventoryItem>? = null
    private var preview = emptyList<InventoryItem>()
    private var applying = false
    private lateinit var summary: TextView
    private lateinit var sourceStatus: TextView
    private lateinit var selectButton: MaterialButton
    private lateinit var applyButton: MaterialButton

    private val chooseFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || applying) return@registerForActivityResult
        preview = emptyList()
        selectButton.isEnabled = false
        updateApply()
        summary.text = "CSV를 검사하는 중입니다."
        worker.execute {
            val result = runCatching {
                val bytes = requireNotNull(contentResolver.openInputStream(uri)) { "파일을 열 수 없습니다." }.use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count == -1) break
                        require(output.size() + count <= 1024 * 1024) { "1MB 이하의 CSV 파일을 선택해 주세요." }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                parseInventoryCsv(bytes.toString(Charsets.UTF_8))
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                selectButton.isEnabled = true
                result.onSuccess {
                    preview = it
                    summary.text = "${it.size}행 확인 · 전체 ${it.sumOf { row -> row.quantity.toLong() }}개\n" +
                        it.take(5).joinToString("\n") { row -> "${row.itemCode} · ${row.warehouse} · ${row.location} · ${row.quantity}개" }
                }.onFailure { summary.text = it.message ?: "파일을 읽지 못했습니다." }
                updateApply()
            }
        }
    }
    private val saveTemplate = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            requireNotNull(contentResolver.openOutputStream(uri)).use {
                it.write(("$INVENTORY_CSV_HEADER\r\n").toByteArray(Charsets.UTF_8))
            }
        }.onFailure { message("CSV 양식을 저장하지 못했습니다.") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openScreen("CSV 재고 가져오기")
        if (InventoryManager.role(this) != "QA") {
            label("검수자 권한이 필요합니다.")
            return
        }
        label("CSV의 수량을 기재된 창고와 위치의 현재고로 반영합니다.")
        sourceStatus = label("전산 재고를 불러오는 중입니다.")
        selectButton = button("CSV 파일 선택", icon = R.drawable.ic_inven_upload) { chooseFile.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel")) }
        val template = button("양식 저장", icon = R.drawable.ic_inven_file) { saveTemplate.launch("inventory-template.csv") }
        actionRow(selectButton, template)
        summary = label("선택한 파일이 없습니다.")
        card(summary)
        applyButton = button("선택한 재고 반영", primary = true, icon = R.drawable.ic_inven_check) { confirmImport() }.apply { isEnabled = false }
    }

    override fun onStart() {
        super.onStart()
        if (!::applyButton.isInitialized) return
        subscription = InventoryManager.listenToRealtimeInventory(this, onError = {
            inventory = null
            sourceStatus.text = it
            updateApply()
        }) {
            inventory = it
            sourceStatus.text = "전산 재고 ${it.size}건을 확인했습니다."
            updateApply()
        }
    }

    override fun onStop() {
        subscription?.remove()
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun updateApply() {
        applyButton.isEnabled = !applying && preview.isNotEmpty() && inventory != null
    }

    private fun confirmImport() {
        val stock = inventory ?: return
        val validated = runCatching {
            preview.map { incoming ->
                val matches = stock.filter { it.itemCode.equals(incoming.itemCode, ignoreCase = true) }
                val location = matches.filter { it.warehouse == incoming.warehouse && it.location == incoming.location }
                when {
                    matches.isEmpty() -> incoming
                    location.size == 1 && !location.single().needsReview -> incoming.copy(
                        documentId = location.single().documentId, itemCode = location.single().itemCode)
                    else -> error("${incoming.itemCode}: 등록 위치 또는 검수 상태를 확인해 주세요.")
                }
            }
        }.getOrElse { return message(it.message ?: "재고를 확인해 주세요.") }
        AlertDialog.Builder(this).setTitle("CSV 현재고 반영")
            .setMessage("${validated.size}개 행을 기재된 현재고 수량으로 반영할까요?")
            .setNegativeButton("취소", null)
            .setPositiveButton("반영") { _, _ -> applyRows(validated) }.show()
    }

    private fun applyRows(rows: List<InventoryItem>) {
        applying = true
        selectButton.isEnabled = false
        updateApply()
        val errors = mutableListOf<String>()
        fun next(index: Int) {
            if (isFinishing || isDestroyed) return
            summary.text = "${index}/${rows.size} 처리 · 실패 ${errors.size}건"
            if (index == rows.size) {
                applying = false
                selectButton.isEnabled = true
                preview = emptyList()
                summary.text = "반영 ${rows.size - errors.size}건 · 실패 ${errors.size}건"
                if (errors.isNotEmpty()) summary.append("\n" + errors.joinToString("\n"))
                updateApply()
                return
            }
            val row = rows[index]
            InventoryManager.saveStock(this, row, WarehouseAction.IMPORT, row.quantity,
                onSuccess = { next(index + 1) },
                onFailure = { error -> errors.add("${row.itemCode}: $error"); next(index + 1) })
        }
        next(0)
    }
}
