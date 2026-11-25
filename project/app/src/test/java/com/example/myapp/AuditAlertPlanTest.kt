package com.example.myapp

import org.junit.Assert.*
import org.junit.Test

class AuditAlertPlanTest {
    private fun row(location: String, expected: String, observed: String, code: Int = 2) = F03ComparedPosition(
        "A01", 1, listOf(expected), observed, 0, "finding", aiProductIds = listOf(observed),
        locationVerified = true, locationLabel = location, identityMethod = "TEST_REFERENCE_APPEARANCE",
        f04StatusCode = code, positionId = location, erpInventoryIds = listOf("ABC-002_$expected"))
    private fun result() = F03AnalyzeAndCompareResponse("compared", "REF", 2,
        listOf(row("A01-1F", "ONE", "TWO"), row("A02-1F", "TWO", "ONE")),
        jobId = "a".repeat(32), companyCode = "ABC-002", warehouseCode = "Warehouse",
        dataSource = "FIRESTORE_TEST_ERP", verificationScope = "test_reference_product_locations")

    @Test fun confirmedMisplacementsCreateSeparateFindingsWithCorrectTargetAndTestScope() {
        val alerts = planAuditMisplacementAlerts(result(), "qa-user")
        assertEquals(2, alerts.size)
        assertEquals("A02-1F", alerts[0].targetLocation)
        assertEquals("ABC-002_ONE", alerts[0].inventoryId)
        assertTrue(alerts.all { it.isTest && it.status == "OPEN" && it.qaUid == "qa-user" })
        assertTrue(alerts.all { it.id.matches(Regex("AI_[a-f0-9]{64}")) })
    }

    @Test fun sameJobAndRowsAreIdempotentAndAnotherJobOrOwnerIsIsolated() {
        val source = result()
        val first = planAuditMisplacementAlerts(source, "qa-user").map { it.id }
        assertEquals(first, planAuditMisplacementAlerts(source, "qa-user").map { it.id })
        assertNotEquals(first, planAuditMisplacementAlerts(source.copy(jobId = "b".repeat(32)), "qa-user").map { it.id })
        assertNotEquals(first, planAuditMisplacementAlerts(source, "other-user").map { it.id })
        assertEquals(1, planAuditMisplacementAlerts(source.copy(results = listOf(source.results[0], source.results[0])), "qa-user").size)
    }

    @Test fun greenMatchesOrangeReviewAndIncompletePositionsNeverDispatch() {
        val source = result()
        for (changed in listOf(source.results[0].copy(f04StatusCode = 1), source.results[0].copy(f04StatusCode = 5),
            source.results[0].copy(locationVerified = false), source.results[0].copy(erpInventoryIds = emptyList()),
            source.results[0].copy(aiProductIds = listOf("ONE")), source.results[0].copy(aiProductIds = listOf("TWO", "THREE")))) {
            assertTrue(planAuditMisplacementAlerts(source.copy(results = listOf(changed)), "qa-user").isEmpty())
        }
    }

    @Test fun unknownTestScopesAndInventoryIssuesAreHeld() {
        val source = result()
        assertTrue(planAuditMisplacementAlerts(source.copy(verificationScope = "test_rack_label_as_item_code"), "qa-user").isEmpty())
        assertTrue(planAuditMisplacementAlerts(source.copy(inventoryIssues = listOf(F03InventoryIssue("invalid"))), "qa-user").isEmpty())
        assertTrue(planAuditMisplacementAlerts(source.copy(erpComparisonAvailable = false), "qa-user").isEmpty())
    }

    @Test fun liveAlertsRequireProductEvidenceAndRemainOutsideTestScope() {
        val source = result().copy(dataSource = "FIREBASE_READ_ONLY",
            verificationScope = "read_product_code_and_verified_location_quantity_unverified")
        assertTrue(planAuditMisplacementAlerts(source, "qa-user").isEmpty())
        val actual = source.copy(results = source.results.map { it.copy(identityMethod = "OCR_PRODUCT_CODE") })
        assertTrue(planAuditMisplacementAlerts(actual, "qa-user").all { !it.isTest })
        assertEquals(2, planAuditMisplacementAlerts(actual, "qa-user").size)
    }
}
