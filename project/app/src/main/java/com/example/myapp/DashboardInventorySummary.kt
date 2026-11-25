package com.example.myapp

internal data class DashboardInventorySummary(
    val totalQuantity: Long,
    val normalQuantity: Long,
    val reviewQuantity: Long,
    val reviewPositions: Int,
    val activeAuditAlerts: Int,
    val unlinkedAuditAlerts: Int
)

/** Current ERP quantities with unresolved audit findings applied as a display overlay. */
internal fun dashboardInventorySummary(
    inventory: List<InventoryItem>,
    alerts: List<AuditMisplacementAlert>,
    warehouse: String? = null
): DashboardInventorySummary {
    val items = inventory.filter { warehouse == null || it.warehouse == warehouse }
    val active = alerts.filter { it.status in setOf("OPEN", "REVIEW_REQUESTED") &&
        (warehouse == null || it.warehouse == warehouse) }.distinctBy { it.id }
    val byId = items.filter { it.documentId.isNotBlank() }.associateBy { it.documentId }
    val linked = active.filter { alert -> byId[alert.inventoryId]?.let {
        it.warehouse == alert.warehouse && it.itemCode == alert.expectedItemCode
    } == true }
    val flaggedIds = linked.map { it.inventoryId }.toSet()
    fun flagged(item: InventoryItem) = item.needsReview || item.documentId in flaggedIds
    return DashboardInventorySummary(
        totalQuantity = items.sumOf { it.quantity.toLong() },
        normalQuantity = items.filter { it.status in setOf("NORMAL", "정상") && !flagged(it) }.sumOf { it.quantity.toLong() },
        reviewQuantity = items.filter(::flagged).sumOf { it.quantity.toLong() },
        reviewPositions = items.count(::flagged),
        activeAuditAlerts = active.size,
        unlinkedAuditAlerts = active.size - linked.size
    )
}
