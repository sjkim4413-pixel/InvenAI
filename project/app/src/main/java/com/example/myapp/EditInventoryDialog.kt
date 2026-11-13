package com.example.myapp

import android.app.Activity
import android.app.Dialog
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

object EditInventoryDialog {

    fun show(
        activity: Activity,
        item: InventoryItem,
        onSaved: () -> Unit = {}
    ) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view: View = LayoutInflater.from(activity).inflate(R.layout.dialog_edit_inventory, null)
        dialog.setContentView(view)
        dialog.setCancelable(true)

        val tvTitle = view.findViewById<TextView>(R.id.tvEditItemName)
        val etLocation = view.findViewById<EditText>(R.id.etEditLocation)
        val etQuantity = view.findViewById<EditText>(R.id.etEditQuantity)

        val btnCancel = view.findViewById<Button>(R.id.btnCancelEdit)
        val btnSave = view.findViewById<Button>(R.id.btnSaveEdit)

        tvTitle.text = "${item.itemName} (${item.itemCode})"
        etLocation.setText(if (item.pendingLocation.isNotEmpty()) item.pendingLocation else item.location)
        etQuantity.setText(if (item.pendingQuantity > 0) item.pendingQuantity.toString() else item.quantity.toString())
        btnSave.text = "수정 및 검수자 승인 요청"

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val newLocation = etLocation.text.toString().trim()
            val newQtyStr = etQuantity.text.toString().trim()

            if (newLocation.isEmpty()) {
                Toast.makeText(activity, "올바른 랙 위치를 입력하세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val newQty = newQtyStr.toIntOrNull() ?: item.quantity

            val updatedItem = item.copy(
                status = "PENDING_QA", // 검수자 승인 대기 상태
                pendingLocation = newLocation,
                pendingQuantity = newQty
            )

            // Firestore에 검수자 승인 대기 상태(PENDING_QA)로 업데이트
            InventoryManager.addOrUpdateInventoryItem(
                item = updatedItem,
                onSuccess = {
                    Toast.makeText(activity, "✅ 수치 수정(위치: $newLocation, 수량: ${newQty}개) 및 검수자 승인 요청 완료!", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                    onSaved()
                },
                onFailure = { err ->
                    Toast.makeText(activity, "저장 실패: $err", Toast.LENGTH_SHORT).show()
                }
            )
        }

        dialog.show()
    }
}