package com.example.myapp

import android.content.Context

internal const val EXTRA_AUDIT_TEST_WAREHOUSE = "com.example.myapp.AUDIT_TEST_WAREHOUSE"

internal fun loadAuditTestImages(context: Context): List<AuditTestImage> {
    val directory = "audit_test_images"
    val files = context.assets.list(directory).orEmpty().filter { it.endsWith(".png") }
    val manifest = context.assets.open("$directory/test_images.json").bufferedReader().use { it.readText() }
    return parseAuditTestImages(manifest, files)
}

/** Keep ordinary company warehouses and only the test warehouses still used by the picker. */
internal fun auditWarehouseChoices(inventory: List<InventoryItem>, images: List<AuditTestImage>): List<String> {
    val activeTestWarehouses = images.mapNotNull { it.warehouseCode }.toSet()
    return inventory.filter { it.warehouse.isNotBlank() &&
        (!it.isTestData || it.warehouse in activeTestWarehouses) }
        .map { it.warehouse }.distinct().sorted()
}

internal fun auditImagesForWarehouse(images: List<AuditTestImage>, warehouse: String?): List<AuditTestImage> =
    if (warehouse.isNullOrBlank()) emptyList() else images.filter { it.warehouseCode == warehouse }

internal fun auditPhotoWarehouseProblem(warehouse: String?, mode: String?, testWarehouse: String?): String? = when {
    warehouse.isNullOrBlank() -> "실사할 창고를 먼저 선택해 주세요."
    mode in setOf("test_reference_pairs", "test_product_regions", "test_rack_labels") &&
        (testWarehouse.isNullOrBlank() || testWarehouse != warehouse) ->
        "선택한 창고와 테스트 사진의 창고가 다릅니다. 해당 창고의 테스트 사진을 다시 선택해 주세요."
    else -> null
}
