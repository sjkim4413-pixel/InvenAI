package com.example.myapp

import org.junit.Assert.*
import org.junit.Test

class AuditWarehouseSelectionTest {
    private fun image(warehouse: String, variant: String) = AuditTestImage(
        "$warehouse-$variant.png", "$warehouse $variant", "paired", "test_reference_pairs", false, warehouse,
        "pair_01", variant)
    private val images = listOf(image("Active", "normal"), image("Active", "misplaced"),
        image("Second", "normal"), image("Second", "misplaced"))

    @Test fun retiredTestWarehousesAreHiddenWhileOrdinaryStockIsPreserved() {
        val inventory = listOf(InventoryItem(warehouse = "Retired", isTestData = true),
            InventoryItem(warehouse = "Active", isTestData = true), InventoryItem(warehouse = "Ordinary"),
            InventoryItem(warehouse = "Active", isTestData = true), InventoryItem(warehouse = ""))
        assertEquals(listOf("Active", "Ordinary"), auditWarehouseChoices(inventory, images))
    }

    @Test fun catalogAloneCannotAddAnotherCompanyWarehouseWithoutItsStock() {
        assertEquals(listOf("Active"), auditWarehouseChoices(listOf(InventoryItem(warehouse = "Active", isTestData = true)), images))
        assertTrue(auditWarehouseChoices(emptyList(), images).isEmpty())
    }

    @Test fun aWarehouseWithOrdinaryStockIsRetainedEvenIfItsTestPhotosWereRetired() {
        val inventory = listOf(InventoryItem(warehouse = "Mixed", isTestData = true), InventoryItem(warehouse = "Mixed"))
        assertEquals(listOf("Mixed"), auditWarehouseChoices(inventory, images))
    }

    @Test fun testPickerOnlyReturnsTheSelectedWarehouseAndPreservesBothVariants() {
        assertEquals(listOf("normal", "misplaced"), auditImagesForWarehouse(images, "Second").map { it.variant })
        assertTrue(auditImagesForWarehouse(images, "Ordinary").isEmpty())
        assertTrue(auditImagesForWarehouse(images, null).isEmpty())
    }

    @Test fun aPhotoCannotStartAnalysisUntilAWarehouseHasBeenChosen() {
        assertNotNull(auditPhotoWarehouseProblem(null, "live", null))
        assertNotNull(auditPhotoWarehouseProblem("  ", "test_reference_pairs", "Active"))
        assertNull(auditPhotoWarehouseProblem("Ordinary", "live", null))
    }

    @Test fun testPhotoCannotSilentlyReplaceOrOmitTheSelectedWarehouse() {
        for (mode in listOf("test_reference_pairs", "test_product_regions", "test_rack_labels")) {
            assertNotNull(auditPhotoWarehouseProblem("Second", mode, "Active"))
            assertNotNull(auditPhotoWarehouseProblem("Second", mode, null))
            assertNull(auditPhotoWarehouseProblem("Second", mode, "Second"))
        }
    }
}
