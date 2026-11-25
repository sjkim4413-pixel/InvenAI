package com.example.myapp

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ManualAuditReviewTest {
    @Test fun barcodePreservesLeadingZerosAndAnEmptyStockCountIsValid() {
        assertEquals(ManualAuditValues("001234567890", 0), manualAuditValues("001234567890", "0"))
    }

    @Test fun missingBarcodeAndInvalidStockCountsAreRejected() {
        assertNull(manualAuditValues("  ", "1"))
        assertNull(manualAuditValues("A".repeat(257), "1"))
        assertNull(manualAuditValues("A\u0000B", "1"))
        for (invalid in listOf("", "-1", "3.5", "1e2", "2147483648")) {
            assertNull(manualAuditValues("0001", invalid))
        }
    }

    @Test fun identicalPhotoBytesKeepTheSameKeyAcrossUploads() {
        val photo = byteArrayOf(1, 2, 3)
        assertEquals(64, auditPhotoDigest(photo).length)
        assertEquals(auditPhotoDigest(photo), auditPhotoDigest(photo.copyOf()))
        assertNotEquals(auditPhotoDigest(photo), auditPhotoDigest(byteArrayOf(1, 2, 4)))
    }

    @Test fun onlyServerVerifiedPositionIdsCanBeUsedWithoutOperatorSelection() {
        val json = """{"rack":"R08","floor":1,"erp_product_ids":[],"ai_product_id":"",
            "yolo_candidates":1,"review_status":"REVIEW","location_verified":true,
            "position_id":"B/R08/1/-","location_label":"B R08-1F",
            "review_reasons":[],"detection_ids":[]}"""
        val row = Gson().fromJson(json, F03ComparedPosition::class.java)
        assertEquals(ManualAuditPosition("B/R08/1/-", "B R08-1F"), row.manualAuditPosition())
        assertNull(row.copy(locationVerified = false).manualAuditPosition())
        assertNull(row.copy(positionId = null).manualAuditPosition())
    }
}
