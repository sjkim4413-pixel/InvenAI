package com.example.myapp

import org.junit.Assert.*
import org.junit.Test

class AuditTestScopeTest {
    private val good = F03ComparedPosition(
        rack = "A01", floor = 1, erpProductIds = listOf("A01-1F"), aiProductId = "A01-1F",
        yoloCandidates = 1, reviewStatus = "테스트 랙 라벨 일치", locationVerified = true,
        f04StatusCode = 1, testLabelMatch = true, detectionIds = listOf("good")
    )
    private val response = F03AnalyzeAndCompareResponse(
        status = "compared", rack = "LIVE", totalPositions = 1, results = listOf(good),
        verificationScope = "test_rack_label_as_item_code", detections = listOf(
            F03Detection("good", listOf(.1f,.1f,.4f,.5f), .9f, emptyList(), locationVerified = true, testLabelMatch = true),
            F03Detection("unmapped", listOf(.6f,.1f,.9f,.5f), .6f, emptyList())
        )
    )

    @Test fun unknownDetectorCandidatesCanBeShownWithoutBecomingInventoryPositions() {
        assertEquals(listOf("good"), response.photoMarks().map { it.detection.id })
        assertEquals(listOf("good", "unmapped"), response.photoMarks(true).map { it.detection.id })
        assertEquals(1, response.results.count { it.isMatch })
        assertEquals(2, response.copy(verificationScope = "read_product_code_and_verified_location_quantity_unverified").photoMarks().size)
    }

    @Test fun aMissingRegisteredPositionStillLowersTheMatchRate() {
        val missing = good.copy(rack = "A02", aiProductId = "", testLabelMatch = false,
            locationVerified = false, f04StatusCode = 5, detectionIds = emptyList())
        val result = response.copy(totalPositions = 2, results = listOf(good, missing))
        assertEquals(2, result.results.size)
        assertEquals(1, result.results.count { it.isMatch })
        assertFalse(missing.isMatch)
    }
}
