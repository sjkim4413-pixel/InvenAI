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
import com.google.firebase.firestore.FirebaseFirestore

object EditInventoryDialog {

    fun show(
        activity: Activity,
        item: InventoryItem,
        onSaved: () -> Unit = {}
    ) {
        showInternal(activity, item, true, onSaved)
    }

    fun showForEditOnly(
        activity: Activity,
        item: InventoryItem,
        onSaved: () -> Unit = {}
    ) {
        showInternal(activity, item, false, onSaved)
    }

    private fun showInternal(
        activity: Activity,
        item: InventoryItem,
        requestApprovalDirectly: Boolean,
        onSaved: () -> Unit
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

        val initialLoc = when {
            item.targetLocation.isNotEmpty() -> item.targetLocation
            item.pendingLocation.isNotEmpty() -> item.pendingLocation
            else -> ""
        }

        if (initialLoc.isNotEmpty()) {
            etLocation.setText(initialLoc)
        } else {
            val sharedPref = activity.getSharedPreferences("app_user_session", android.content.Context.MODE_PRIVATE)
            val companyCode = sharedPref.getString("COMPANY_CODE", "ABC-001") ?: "ABC-001"

            FirebaseFirestore.getInstance().collection("anomalies")
                .whereEqualTo("companyCode", companyCode)
                .whereEqualTo("itemCode", item.itemCode)
                .get()
                .addOnSuccessListener { snapshot ->
                    var foundTarget = ""
                    for (doc in snapshot.documents) {
                        val stat = doc.getString("status") ?: ""
                        val tLoc = doc.getString("targetLocation") ?: ""
                        if (stat == "ALERTED" && tLoc.isNotEmpty()) {
                            foundTarget = tLoc
                            break
                        }
                    }
                    if (foundTarget.isNotEmpty()) {
                        etLocation.setText(foundTarget)
                    } else {
                        etLocation.setText("B구역 R05-3층")
                    }
                }
                .addOnFailureListener {
                    etLocation.setText("B구역 R05-3층")
                }
        }

        etQuantity.setText(if (item.pendingQuantity > 0) item.pendingQuantity.toString() else item.quantity.toString())
        btnSave.text = if (requestApprovalDirectly) "수정 및 검수자 승인 요청" else "수정사항 저장"

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

            val updatedItem = if (requestApprovalDirectly) {
                item.copy(
                    status = "PENDING_QA",
                    pendingLocation = newLocation,
                    pendingQuantity = newQty
                )
            } else {
                item.copy(
                    pendingLocation = newLocation,
                    pendingQuantity = newQty
                )
            }

            InventoryManager.addOrUpdateInventoryItem(
                context = activity,
                item = updatedItem,
                onSuccess = {
                    val msg = if (requestApprovalDirectly) "✅ 수치 수정 및 검수자 승인 요청 완료!" else "✅ 수치 수정이 저장되었습니다. [승인 요청] 버튼을 눌러주세요."
                    Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
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
