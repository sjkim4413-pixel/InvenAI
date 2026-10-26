package com.example.myapp

import android.app.Activity
import android.app.Dialog
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

// F-06 적재자 기기 푸시/팝업 표출 및 '재검증 대기' API 회신 다이얼로그
object F06AlertDeepLinkDialog {

    fun show(
        activity: Activity,
        payload: MisplacementAlertPayload,
        onReplied: () -> Unit = {}
    ) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view: View = LayoutInflater.from(activity).inflate(R.layout.dialog_f06_deeplink, null)
        dialog.setContentView(view)
        dialog.setCancelable(true)

        val tvLocation = view.findViewById<TextView>(R.id.tvAlertLocation)
        val tvItemInfo = view.findViewById<TextView>(R.id.tvAlertItemInfo)
        val btnCancel = view.findViewById<Button>(R.id.btnF06Cancel)
        val btnComplete = view.findViewById<Button>(R.id.btnF06Complete)

        tvLocation.text = "📍 발생 위치: " + payload.location
        tvItemInfo.text = "정상 ID: " + payload.expectedItemCode + "  |  오적재 ID: " + payload.currentItemCode

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        // F-06 명세서 규격: [수정 완료] 클릭 ➔ 해당 로케이션 상태를 '재검증 대기'(WAITING_REVERIFICATION)로 회신
        btnComplete.setOnClickListener {
            val updatedItem = InventoryItem(
                itemCode = payload.expectedItemCode,
                itemName = "품목: " + payload.expectedItemCode,
                quantity = 1,
                location = payload.location,
                status = "WAITING_REVERIFICATION"
            )

            InventoryManager.addOrUpdateInventoryItem(
                item = updatedItem,
                onSuccess = {
                    Toast.makeText(activity, "✅ 시정 완료 회신! 로케이션 상태가 '재검증 대기'로 변경되었습니다.", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                    onReplied()
                },
                onFailure = { err ->
                    Toast.makeText(activity, "회신 실패: " + err, Toast.LENGTH_SHORT).show()
                }
            )
        }

        dialog.show()
    }
}