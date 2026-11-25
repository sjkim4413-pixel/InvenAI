package com.example.myapp

import org.junit.Assert.*
import org.junit.Test

class WarehouseEventMergeTest {
    private fun event(action: WarehouseAction, time: Long = 10, id: String = "") = WarehouseEvent(
        "stock", "상품", "SKU", "창고", "A01-1F", "A02-1F", 5, 5, "worker", time, action,
        recordId = id, alertId = if (action.name.startsWith("AUDIT_")) "AI_alert" else "")

    @Test fun savedAuditEntryReplacesFallbackWithoutDuplicatingDetection() {
        val saved = event(WarehouseAction.AUDIT_DETECTION, id = "saved")
        val fallback = saved.copy(recordId = "", inferred = true, actorUid = "")
        val result = mergeWarehouseEvents(emptyList(), listOf(saved), listOf(fallback), 100)
        assertEquals(listOf(saved), result)
        assertFalse(result.single().inferred)
    }
    @Test fun repeatedReworkCyclesRemainDistinctAndChronological() {
        val found = event(WarehouseAction.AUDIT_DETECTION, 1, "found")
        val first = event(WarehouseAction.AUDIT_RELOCATION, 2, "first")
        val rework = event(WarehouseAction.AUDIT_REJECTION, 3, "rework")
        val second = event(WarehouseAction.AUDIT_RELOCATION, 4, "second")
        val approved = event(WarehouseAction.AUDIT_APPROVAL, 5, "approved")
        assertEquals(listOf(approved, second, rework, first, found),
            mergeWarehouseEvents(emptyList(), listOf(found, first, rework, second, approved), emptyList(), 100))
    }
    @Test fun workAndAnomalyFiltersIncludeReportedMovesAndConfirmedMoves() {
        assertTrue(event(WarehouseAction.AUDIT_RELOCATION).isWork)
        assertTrue(event(WarehouseAction.AUDIT_RELOCATION).action.anomaly)
        assertTrue(event(WarehouseAction.REVIEW_REQUEST).isWork)
        assertTrue(event(WarehouseAction.REVIEW_APPROVAL).isWork)
        assertFalse(event(WarehouseAction.REVIEW_APPROVAL).copy(toLocation = "A01-1F").isWork)
        assertTrue(event(WarehouseAction.REVIEW_APPROVAL).copy(toLocation = "A01-1F", afterQuantity = 4).isWork)
        assertFalse(event(WarehouseAction.AUDIT_DETECTION).isWork)
        assertTrue(event(WarehouseAction.AUDIT_APPROVAL).isResolved)
        assertFalse(event(WarehouseAction.AUDIT_REJECTION).isResolved)
    }
    @Test fun globalLimitAppliesAfterMergingAllSources() {
        val stock = event(WarehouseAction.INBOUND, 3, "s")
        val found = event(WarehouseAction.AUDIT_DETECTION, 1, "a")
        val move = event(WarehouseAction.AUDIT_RELOCATION, 4, "b")
        assertEquals(listOf(move, stock), mergeWarehouseEvents(listOf(stock), listOf(found, move), emptyList(), 2))
    }
    @Test fun SeparateStockEventsAtSameTimeRemainDistinct() {
        val first = event(WarehouseAction.INBOUND, id = "one")
        val second = first.copy(recordId = "two")
        assertEquals(2, mergeWarehouseEvents(listOf(first, second), emptyList(), emptyList(), 100).size)
    }
    @Test fun storedAuditEventsRetainTheirIdentityEvenWithinTheSameMillisecond() {
        val first = event(WarehouseAction.AUDIT_RELOCATION, id = "one")
        val second = first.copy(recordId = "two")
        assertEquals(2, mergeWarehouseEvents(emptyList(), listOf(first, second), listOf(first.copy(inferred = true)), 100).size)
    }
}
