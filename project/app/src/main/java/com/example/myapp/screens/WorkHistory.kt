package com.example.myapp

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.ListenerRegistration

class WorkHistory : WarehouseScreen() {
    private var subscription: ListenerRegistration? = null
    private var events = emptyList<WarehouseEvent>()
    private var loadingMessage: String? = "이력을 불러오는 중입니다."
    private var showAnomalies = false
    private var onlyResolved = false
    private var warehouse: String? = null
    private lateinit var workTab: MaterialButton
    private lateinit var anomalyTab: MaterialButton
    private lateinit var list: LinearLayout
    private lateinit var titleLine: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showAnomalies = savedInstanceState?.getBoolean("anomalies") ?: intent.getBooleanExtra("ANOMALIES", false)
        onlyResolved = savedInstanceState?.getBoolean("resolved") ?: intent.getBooleanExtra("RESOLVED", false)
        warehouse = savedInstanceState?.getString("warehouse") ?: intent.getStringExtra("WAREHOUSE_CODE")
        openScreen("실시간 재고 이동 현황")
        workTab = button("작업 이력") { showAnomalies = false; onlyResolved = false; render() }
        anomalyTab = button("이상 이력") { showAnomalies = true; onlyResolved = false; render() }
        workTab.isCheckable = true
        anomalyTab.isCheckable = true
        actionRow(workTab, anomalyTab)
        titleLine = label("")
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; body.addView(this) }
        render()
    }

    override fun onStart() {
        super.onStart()
        subscription = WarehouseHistory.listen(this, onError = {
            loadingMessage = it
            render()
        }) {
            events = it
            loadingMessage = null
            render()
        }
    }

    override fun onStop() {
        subscription?.remove()
        subscription = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("anomalies", showAnomalies)
        outState.putBoolean("resolved", onlyResolved)
        outState.putString("warehouse", warehouse)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        val scoped = events.filter { warehouse == null || it.warehouse == warehouse }
        workTab.text = if (loadingMessage == null) "작업 이력 ${scoped.count { it.isWork }}건" else "작업 이력"
        anomalyTab.text = if (loadingMessage == null) "이상 이력 ${scoped.count { it.action.anomaly }}건" else "이상 이력"
        workTab.isChecked = !showAnomalies
        anomalyTab.isChecked = showAnomalies
        titleLine.text = (warehouse?.let { "$it\n" } ?: "") + when {
            onlyResolved -> "최근 100건 중 검수 승인"
            showAnomalies -> "최근 100건 중 이상 이력"
            else -> "최근 100건 중 작업 이력"
        }
        list.removeAllViews()
        val visible = scoped.filter {
            (if (showAnomalies) it.action.anomaly else it.isWork) && (!onlyResolved || it.isResolved)
        }
        if (loadingMessage != null || visible.isEmpty()) {
            list.addView(TextView(this).apply {
                text = loadingMessage ?: "기록된 이력이 없습니다."
                textSize = 14f
                setTextColor(WarehouseAppearance.MUTED)
                setPadding(0, dp(16), 0, 0)
            })
        } else for (event in visible) {
            list.addView(warehouseEventCard(this, event))
        }
    }
}
