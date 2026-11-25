package com.example.myapp

import org.junit.Assert.*
import org.junit.Test

class AuditTestImagesTest {
    private val files = listOf("normal.png", "misplaced.png", "old.png")
    private val json = """{"version":1,"images":[
        {"file":"normal.png","title":"테스트 01 · 정상","group":"comparison","mode":"test_product_regions","warehouseCode":"Comparison"},
        {"file":"misplaced.png","title":"테스트 02 · 오적재","group":"comparison","mode":"test_product_regions","warehouseCode":"Comparison"},
        {"file":"old.png","title":"테스트 03 · 기존","group":"reference","mode":"test_rack_labels"}]}"""

    @Test fun comparisonUsesTheSameAuthoritativeWarehouseInsteadOfRackOnlyComparison() {
        val entries = parseAuditTestImages(json, files.reversed())
        assertEquals(listOf("normal.png", "misplaced.png", "old.png"), entries.map { it.file })
        val comparison = entries.filter { it.group == "comparison" }
        assertEquals(2, comparison.size)
        assertTrue(comparison.all { !it.testRackLabels && it.mode == "test_product_regions" && it.warehouseCode == "Comparison" })
        assertTrue(entries.last().testRackLabels)
    }

    @Test(expected = IllegalArgumentException::class)
    fun anUnlistedPhotoCannotSilentlyGetAnIncorrectModeOrTitle() {
        parseAuditTestImages(json, files + "unlisted.png")
    }

    @Test fun disabledPhotosStayRegisteredWithoutAppearingInThePicker() {
        val disabled = json.replace("\"file\":\"old.png\"", "\"file\":\"old.png\",\"enabled\":false")
        assertEquals(listOf("normal.png", "misplaced.png"), parseAuditTestImages(disabled, files).map { it.file })
    }

    @Test(expected = IllegalArgumentException::class)
    fun aComparisonMustIdentifyItsAuthoritativeWarehouse() {
        parseAuditTestImages(json.replace("\"warehouseCode\":\"Comparison\"", "\"warehouseCode\":\"\""), files)
    }

    @Test fun completeReferencePairUsesOneWarehouseAndKeepsTheTwoVariantsTogether() {
        val paired = """{"version":1,"images":[
            {"file":"normal.png","title":"테스트 01 · 정상","group":"paired","mode":"test_reference_pairs","warehouseCode":"Warehouse","pairId":"pair_01","variant":"normal"},
            {"file":"misplaced.png","title":"테스트 02 · 오적재","group":"paired","mode":"test_reference_pairs","warehouseCode":"Warehouse","pairId":"pair_01","variant":"misplaced"}]}"""
        val result = parseAuditTestImages(paired, files.take(2))
        assertEquals(listOf("normal", "misplaced"), result.map { it.variant })
        assertTrue(result.all { it.mode == "test_reference_pairs" && !it.testRackLabels })
        try {
            parseAuditTestImages(paired.replace("\"variant\":\"misplaced\"", "\"variant\":\"normal\""), files.take(2))
            fail("Two normal variants must not masquerade as a pair")
        } catch (_: IllegalArgumentException) { }
    }
}
