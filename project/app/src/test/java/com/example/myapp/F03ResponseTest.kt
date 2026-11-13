package com.example.myapp

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class F03ResponseTest {
    @Test fun photoMarksKeepAmbiguousOrUnmappedDetectionsInReview() {
        val normal = F03ComparedPosition(
            "R1", 1, listOf("SKU1"), "SKU1", 1, "PASS",
            verificationPassed = true, f04StatusCode = 1, detectionIds = listOf("shared", "normal")
        )
        val anomaly = normal.copy(
            rack = "R2", verificationPassed = false, f04StatusCode = 2,
            detectionIds = listOf("shared", "wrong", "unmapped")
        )
        val detection = F03Detection(
            "normal", listOf(.1f, .2f, .3f, .4f), .9f, listOf("SKU1"),
            locationVerified = true, verificationPassed = true
        )
        val result = F03AnalyzeAndCompareResponse(
            "analyzed", "MAPPED", 2, listOf(normal, anomaly),
            detections = listOf(
                detection, detection.copy(id = "shared"),
                detection.copy(id = "wrong", verificationPassed = false),
                detection.copy(id = "unmapped", locationVerified = false, verificationPassed = false),
                detection.copy(id = "invalid", bbox = listOf(.3f, .2f, .1f, .4f))
            )
        )
        val marks = result.photoMarks().associateBy { it.detection.id }
        assertEquals(normal, marks.getValue("normal").position)
        assertEquals(0xFF059669.toInt(), marks.getValue("normal").color)
        assertNull(marks.getValue("shared").position)
        assertEquals(0xFFD97706.toInt(), marks.getValue("shared").color)
        assertEquals(0xFFDC2626.toInt(), marks.getValue("wrong").color)
        assertNull(marks.getValue("unmapped").position)
        assertEquals(0xFFD97706.toInt(), marks.getValue("unmapped").color)
        assertFalse("invalid" in marks)
    }

    @Test fun liveProductLocationMatchDoesNotMeanQuantityVerified() {
        val json="""{"status":"compared","rack":"LIVE","total_positions":1,"data_source":"FIREBASE_READ_ONLY",
            "revision":{"pipeline":"2026-11-04.8"},"results":[{"rack":"R08","floor":1,
            "erp_product_ids":["BT-200"],"ai_product_id":"BT-200","yolo_candidates":1,
            "review_status":"상품·위치일치_수량확인필요","verification_passed":false,"location_verified":true,
            "product_location_verified":true,"quantity_verified":false,"f04_status_code":1,"expected_quantity":50,"location_method":"operator_confirmed",
            "location_label":"B구역 R08-1층","review_reasons":[]}],"detections":[],"codes":[],"unregistered_codes":[],
            "registered_code_observations":[{"id":"c1","format":"QR_CODE","value":"data","bbox":[0.1,0.1,0.3,0.3],
                "sku":"BT-200","identity_method":"registered_product_qr"}],
            "inventory_issues":[{"reason":"창고 확인 필요"}]}"""
        val result=Gson().fromJson(json,F03AnalyzeAndCompareResponse::class.java)
        assertTrue(result.results[0].productLocationVerified)
        assertFalse(result.results[0].verificationPassed)
        assertEquals(50.0,result.results[0].expectedQuantity!!,0.0)
        assertEquals("operator_confirmed",result.results[0].locationMethod)
        assertEquals("창고 확인 필요",result.inventoryIssues!![0].reason)
        assertEquals("BT-200",result.registeredCodes!![0].sku)
        assertTrue(result.results[0].isVerifiedMatch)
        assertEquals(false,result.results[0].quantityVerified)
    }
    @Test fun normalDisplayRequiresBothF04DecisionAndVerifiedEvidence() {
        val row = F03ComparedPosition("R1", 1, listOf("SKU1"), "SKU1", 1, "PASS")
        assertFalse(row.copy(f04StatusCode = 1).isVerifiedMatch)
        assertFalse(row.copy(verificationPassed = true, f04StatusCode = 5).isVerifiedMatch)
        assertTrue(row.copy(verificationPassed = true, f04StatusCode = 1).isVerifiedMatch)
    }
    @Test fun observedTextProductAndTestErpScopeArePreserved() {
        val json = """{"status":"analyzed","rack":"MAPPED","total_positions":1,
            "data_source":"SIMULATED_TRADERS_ERP","verification_scope":"simulated_visible_bundle",
            "ocr_status":"completed","ocr_tokens":[{"id":"t1","text":"TROMM","confidence":0.99,"bbox":[0.2,0.2,0.3,0.3]}],
            "detections":[{"id":"d1","bbox":[0.1,0.1,0.8,0.8],"confidence":0.8,"product_ids":["SIM-001"],
                "rack":"R1","floor":1,"location_verified":true,"verification_passed":false,"review_status":"미등록_또는_오적재의심",
                "detection_stable":true,"support_scales":[640,1024,1280]}],
            "results":[{"rack":"R1","floor":1,"erp_product_ids":["OTHER"],"ai_product_id":"SIM-001",
                "yolo_candidates":1,"review_status":"미등록_또는_오적재의심","verification_passed":false,"location_verified":true,"review_reasons":[]}]}"""
        val result=Gson().fromJson(json,F03AnalyzeAndCompareResponse::class.java)
        assertEquals("TROMM",result.ocrTokens!![0].text)
        assertEquals("SIM-001",result.detections[0].productIds[0])
        assertEquals("SIMULATED_TRADERS_ERP",result.dataSource)
        assertTrue(result.detections[0].locationVerified)
        assertFalse(result.detections[0].verificationPassed)
        assertTrue(result.detections[0].detectionStable)
        assertEquals(listOf(640,1024,1280),result.detections[0].supportScales)
        assertEquals("미등록_또는_오적재의심",result.detections[0].reviewStatus)
    }
    @Test fun unmappedLocationAndCandidateEvidenceArePreserved() {
        val json = """{"status":"review_required","rack":"UNMAPPED","total_positions":1,
            "job_id":"job1","filename":"photo.jpg","total_candidates":1,"image_width":100,"image_height":200,
            "detections":[{"id":"d1","bbox":[0.1,0.2,0.8,0.9],"confidence":0.7,"product_ids":[]}],
            "results":[{"rack":"UNMAPPED","floor":null,"erp_product_ids":[],"ai_product_id":"",
                "yolo_candidates":1,"review_status":"위치매핑_확인필요","verification_passed":false,
                "review_reasons":["위치 확인 필요"]}]}"""
        val result = Gson().fromJson(json,F03AnalyzeAndCompareResponse::class.java)
        assertNull(result.results[0].floor)
        assertFalse(result.results[0].verificationPassed)
        assertEquals("job1",result.jobId)
        assertEquals(4,result.detections[0].bbox.size)
        assertEquals(0.7f,requireNotNull(result.detections[0].confidence),0.001f)
        assertEquals("위치 확인 필요",result.results[0].reviewReasons[0])
    }
}
