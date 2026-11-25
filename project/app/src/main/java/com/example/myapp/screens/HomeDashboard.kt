package com.example.myapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.ListenerRegistration
import java.text.NumberFormat

class HomeDashboard : AppCompatActivity() {
    private var inventoryListener: ListenerRegistration? = null
    private var eventListener: ListenerRegistration? = null
    private var auditListener: ListenerRegistration? = null
    private var inventory = emptyList<InventoryItem>()
    private var events = emptyList<WarehouseEvent>()
    private var auditAlerts = emptyList<AuditMisplacementAlert>()
    private var auditProblem: String? = "실사 알림을 불러오는 중입니다."
    private var warehouse: String? = null
    private var inventoryProblem: String? = "실시간 재고를 불러오는 중입니다."
    private var historyProblem: String? = "작업 이력을 불러오는 중입니다."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home_dashboard)
        if (InventoryManager.role(this) != "QA") {
            openWarehouseHome(this)
            return
        }
        warehouse = savedInstanceState?.getString("warehouse")
        findViewById<View>(R.id.ry7p7pqvxka).visibility = View.GONE
        val employeeId = getSharedPreferences("app_user_session", MODE_PRIVATE).getString("USER_ID", "").orEmpty()
        findViewById<TextView>(R.id.rk36yxt85npk).apply {
            text = employeeId.take(2).ifBlank { "QA" }
            contentDescription = "검수자 계정"
            (parent as? View)?.setOnClickListener { MenuHelper.showProfile(this@HomeDashboard) }
        }
        val warehouseText = findViewById<TextView>(R.id.r5cnj6sku8uh)
        (warehouseText.parent as? View)?.let(WarehouseMenus::decorateAnchor)
        warehouseText.maxWidth = (resources.displayMetrics.widthPixels * .45f).toInt()
        warehouseText.maxLines = 1
        warehouseText.ellipsize = android.text.TextUtils.TruncateAt.END
        (warehouseText.parent as? View)?.setOnClickListener { chooseWarehouse() }
        warehouseText.setOnClickListener { chooseWarehouse() }

        fun navigate(id: Int, screen: Class<*>, filterWarehouse: Boolean = false) {
            findViewById<View>(id)?.setOnClickListener {
                val route = Intent(this, screen)
                if (filterWarehouse) route.putExtra("WAREHOUSE_CODE", warehouse)
                startActivity(route)
            }
        }
        navigate(R.id.ror0ls8e8rs, Inbound::class.java)
        navigate(R.id.r5nn11xsmxdh, Transfer::class.java)
        navigate(R.id.r58m6zkpupi8, AuditStart::class.java, true)
        navigate(R.id.rcgbksuzw3mr, InventoryList::class.java, true)
        navigate(R.id.r0da5dco3z0cj, AnomalyInventory::class.java, true)
        (findViewById<TextView>(R.id.rs6kkinbc3pk).parent as? View)?.setOnClickListener {
            startActivity(Intent(this, AnomalyInventory::class.java).putExtra("WAREHOUSE_CODE", warehouse))
        }
        navigate(R.id.rabmoab0ddco, WorkHistory::class.java, true)
        findViewById<LinearLayout>(R.id.warehouseBottomNavigation).addView(warehouseNavigation(this))
        render()
    }

    override fun onStart() {
        super.onStart()
        if (isFinishing) return
        auditProblem = "실사 알림을 불러오는 중입니다."
        render()
        auditListener = AuditAlertManager.listen(this, onError = {
            auditProblem = it
            render()
        }) {
            auditAlerts = it
            auditProblem = null
            render()
        }
        inventoryListener = InventoryManager.listenToRealtimeInventory(this, onError = {
            inventoryProblem = it
            render()
        }) {
            inventory = it
            inventoryProblem = null
            render()
        }
        eventListener = WarehouseHistory.listen(this, onError = {
            historyProblem = it
            render()
        }) {
            events = it
            historyProblem = null
            render()
        }
    }

    override fun onStop() {
        inventoryListener?.remove()
        eventListener?.remove()
        auditListener?.remove()
        inventoryListener = null
        eventListener = null
        auditListener = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("warehouse", warehouse)
        super.onSaveInstanceState(outState)
    }

    private fun chooseWarehouse() {
        val names = (inventory.map { it.warehouse } + auditAlerts.map { it.warehouse })
            .filter(String::isNotBlank).distinct().sorted()
        WarehouseMenus.show(this, "창고 선택", listOf("전체 창고") + names, names.indexOf(warehouse) + 1) { index ->
                warehouse = names.getOrNull(index - 1)
                render()
            }
    }

    private fun render() {
        findViewById<TextView>(R.id.r5cnj6sku8uh).text = warehouse ?: "전체 창고"
        val summary = dashboardInventorySummary(inventory, auditAlerts, warehouse)
        fun quantity(value: Long) = "${NumberFormat.getIntegerInstance().format(value)}개"
        findViewById<TextView>(R.id.r8y8i9t5s33h).text = if (inventoryProblem == null) quantity(summary.totalQuantity) else "—"
        val ready = inventoryProblem == null && auditProblem == null
        findViewById<TextView>(R.id.rhfbnn8zu2se).text = if (ready) quantity(summary.normalQuantity) else "—"
        findViewById<TextView>(R.id.rs6kkinbc3pk).text = if (ready) quantity(summary.reviewQuantity) else "—"
        findViewById<TextView>(R.id.homeAnomalySummary).text = if (!ready) {
            auditProblem ?: inventoryProblem
        } else buildString {
            append(if (summary.reviewPositions > 0) "이상 재고 ${summary.reviewPositions}곳" else "미처리 이상 재고 없음")
            if (summary.activeAuditAlerts > 0) append(" · 실사 알림 ${summary.activeAuditAlerts}건")
            if (summary.unlinkedAuditAlerts > 0) append("\n재고 연결 확인 ${summary.unlinkedAuditAlerts}건")
        }
        val container = findViewById<LinearLayout>(R.id.llHomeRecentLogsContainer)
        container.removeAllViews()
        val recent = events.filter { warehouse == null || it.warehouse == warehouse }.take(5)
        val status = historyProblem ?: inventoryProblem
        if (status != null || recent.isEmpty()) {
            container.addView(TextView(this).apply { text = status ?: "최근 작업 기록이 없습니다." })
        } else recent.forEach { container.addView(warehouseEventCard(this, it)) }
    }
}
