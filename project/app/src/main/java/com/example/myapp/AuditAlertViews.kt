package com.example.myapp

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

internal object AuditAlertViews {
    private fun incomingMessage(alerts: List<AuditMisplacementAlert>) = alerts.take(3).joinToString("\n\n") {
        "${if (it.isTest) "[테스트] " else ""}${it.warehouse} · ${it.location}\n기준 ${it.expectedItemName} / 사진 ${it.observedItemName}"
    }
    fun updateIncoming(dialog: AlertDialog, alerts: List<AuditMisplacementAlert>) {
        dialog.setTitle("실사 오적재 알림 ${alerts.size}건")
        dialog.setMessage(incomingMessage(alerts))
    }
    fun incoming(activity: Activity, alerts: List<AuditMisplacementAlert>): AlertDialog =
        MaterialAlertDialogBuilder(activity).setTitle("실사 오적재 알림 ${alerts.size}건")
            .setMessage(incomingMessage(alerts)).setPositiveButton("오적재 확인") { _, _ ->
                activity.startActivity(Intent(activity, AnomalyInventory::class.java))
            }.setNegativeButton("나중에", null).show()

    fun card(activity: Activity, alert: AuditMisplacementAlert): View {
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(14))
            background = WarehouseAppearance.shape(activity, Color.WHITE, 16,
                if (alert.status == "OPEN") 0xFFFECACA.toInt() else 0xFFFDE68A.toInt())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
            addView(TextView(activity).apply {
                text = "${if (alert.isTest) "테스트 · " else ""}실사 오적재 · ${if (alert.status == "OPEN") "조치 필요" else "재검수 대기"}"
                textSize = 13f;setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (alert.status == "OPEN") 0xFFB91C1C.toInt() else 0xFFB45309.toInt())
            })
            addView(TextView(activity).apply {
                text = "${alert.warehouse} · ${alert.location}\n기준 상품 · ${alert.expectedItemName}\n사진 상품 · ${alert.observedItemName}" +
                    if (alert.targetLocation.isNotBlank()) "\n이동할 위치 · ${alert.targetLocation}" else ""
                textSize = 14f;setTextColor(WarehouseAppearance.INK)
                setPadding(0, dp(10), 0, dp(12));setLineSpacing(dp(4).toFloat(), 1f)
            })
            addView(MaterialButton(activity).apply {
                text = "알림 상세 및 조치";isAllCaps = false
                setOnClickListener { details(activity, alert) }
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }
    }

    fun details(activity: Activity, alert: AuditMisplacementAlert): AlertDialog {
        val loader = InventoryManager.role(activity) == "LOADER"
        val note = EditText(activity).apply {
            hint = if (loader) "조치 내용 (선택)" else "검수 내용 (선택)"
            filters = arrayOf(android.text.InputFilter.LengthFilter(500))
            maxLines = 4
            setText(alert.note)
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt();setPadding(pad, 0, pad, 0)
            addView(TextView(activity).apply {
                text = "창고 · ${alert.warehouse}\n발생 위치 · ${alert.location}\n기준 상품 · ${alert.expectedItemName} (${alert.expectedItemCode})\n" +
                    "사진 상품 · ${alert.observedItemName} (${alert.observedItemCode})\n" +
                    "이동할 위치 · ${alert.targetLocation.ifBlank { "검수자 확인 필요" }}\n\n" +
                    if (alert.isTest) "테스트 이미지에서 발생한 알림입니다. 실제 재고는 변경하지 않습니다." else
                        "조치 결과를 회신하면 검수자가 다시 확인합니다. 재고 정정은 검수 후 진행합니다."
                setTextColor(WarehouseAppearance.INK);textSize = 14f
                setLineSpacing(4 * resources.displayMetrics.density, 1f)
            })
            addView(note)
        }
        val builder = MaterialAlertDialogBuilder(activity).setTitle(if (alert.isTest) "테스트 오적재 알림" else "실사 오적재 알림")
            .setView(content).setNegativeButton("닫기", null)
        val action = when { loader && alert.status == "OPEN" -> "REVIEW_REQUESTED"; !loader -> "RESOLVED"; else -> null }
        if (action != null) builder.setPositiveButton(if (loader) "조치 완료·재검수 요청" else "재검수 완료", null)
        if (!loader && alert.status == "REVIEW_REQUESTED") builder.setNeutralButton("재조치 요청", null)
        val dialog = builder.show()
        fun update(status: String) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = false
            AuditAlertManager.changeStatus(activity, alert, status, note.text.toString().trim(), onSuccess = {
                dialog.dismiss();Toast.makeText(activity, "알림 처리 상태를 저장했습니다.", Toast.LENGTH_SHORT).show()
            }, onFailure = {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = true
                Toast.makeText(activity, it, Toast.LENGTH_LONG).show()
            })
        }
        if (action != null) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { update(action) }
        if (!loader && alert.status == "REVIEW_REQUESTED") dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { update("OPEN") }
        return dialog
    }
}
