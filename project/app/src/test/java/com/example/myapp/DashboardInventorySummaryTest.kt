package com.example.myapp

import org.junit.Assert.*
import org.junit.Test

class DashboardInventorySummaryTest {
    private val first = InventoryItem("ONE", "상품 1", 7, "A01-1F", warehouse = "창고 A", documentId = "ABC_ONE")
    private val second = InventoryItem("TWO", "상품 2", 3, "A02-1F", warehouse = "창고 A", documentId = "ABC_TWO")
    private val other = InventoryItem("THREE", "상품 3", 5, "B01-1F", warehouse = "창고 B", documentId = "ABC_THREE")
    private fun alert(id: String = "issue", item: InventoryItem = first, status: String = "OPEN") = AuditMisplacementAlert(
        id, "ABC", "qa", "a".repeat(32), item.documentId, item.warehouse, item.location, item.itemCode,
        "OTHER", item.itemName, "다른 상품", "A03-1F", true, status)

    @Test fun normalInventoryHasNoReviewQuantityBeforeAnAlert() {
        val summary = dashboardInventorySummary(listOf(first, second, other), emptyList())
        assertEquals(15L, summary.totalQuantity)
        assertEquals(15L, summary.normalQuantity)
        assertEquals(0L, summary.reviewQuantity)
    }

    @Test fun anAuditFindingAffectsHomeEvenWhileItsInventoryStatusRemainsNormal() {
        val summary = dashboardInventorySummary(listOf(first, second, other), listOf(alert()))
        assertEquals(15L, summary.totalQuantity)
        assertEquals(8L, summary.normalQuantity)
        assertEquals(7L, summary.reviewQuantity)
        assertEquals(1, summary.reviewPositions)
        assertEquals("NORMAL", first.status)
        assertEquals(7, first.quantity)
    }

    @Test fun repeatedJobsAndLegacyAnomaliesDoNotCountTheSameInventoryTwice() {
        val legacy = first.copy(status = "ANOMALY")
        val summary = dashboardInventorySummary(listOf(legacy, second), listOf(alert("one"), alert("two"), alert("one")))
        assertEquals(7L, summary.reviewQuantity)
        assertEquals(1, summary.reviewPositions)
        assertEquals(2, summary.activeAuditAlerts)
        assertEquals(3L, summary.normalQuantity)
    }

    @Test fun reviewRequestedRemainsFlaggedUntilQaResolvesIt() {
        val pending = dashboardInventorySummary(listOf(first), listOf(alert(status = "REVIEW_REQUESTED")))
        assertEquals(7L, pending.reviewQuantity)
        val closed = dashboardInventorySummary(listOf(first), listOf(alert(status = "RESOLVED")))
        assertEquals(0L, closed.reviewQuantity)
        assertEquals(7L, closed.normalQuantity)
        assertEquals(0, closed.activeAuditAlerts)
    }

    @Test fun resolvingAnAuditDoesNotClearAnIndependentLegacyReview() {
        val summary = dashboardInventorySummary(listOf(first.copy(status = "PENDING_QA")), listOf(alert(status = "RESOLVED")))
        assertEquals(7L, summary.reviewQuantity)
        assertEquals(0L, summary.normalQuantity)
    }

    @Test fun warehouseScopeAppliesToInventoryAndAuditFindings() {
        val alerts = listOf(alert(), alert("other", other))
        val selected = dashboardInventorySummary(listOf(first, second, other), alerts, "창고 B")
        assertEquals(5L, selected.totalQuantity)
        assertEquals(5L, selected.reviewQuantity)
        assertEquals(1, selected.activeAuditAlerts)
        assertEquals(0L, selected.normalQuantity)
    }

    @Test fun missingOrChangedInventoryIsReportedWithoutInventingAQuantity() {
        val summary = dashboardInventorySummary(listOf(second), listOf(alert()))
        assertEquals(3L, summary.totalQuantity)
        assertEquals(0L, summary.reviewQuantity)
        assertEquals(1, summary.unlinkedAuditAlerts)
        val changed = dashboardInventorySummary(listOf(first.copy(itemCode = "REPLACED")), listOf(alert()))
        assertEquals(1, changed.unlinkedAuditAlerts)
        assertEquals(0L, changed.reviewQuantity)
    }
}
