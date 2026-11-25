package com.example.myapp

import java.security.MessageDigest

/** An audit finding is separate from stock quantities and stock correction. */
internal data class AuditMisplacementAlert(
    val id: String,
    val companyCode: String,
    val qaUid: String,
    val jobId: String,
    val inventoryId: String,
    val warehouse: String,
    val location: String,
    val expectedItemCode: String,
    val observedItemCode: String,
    val expectedItemName: String,
    val observedItemName: String,
    val targetLocation: String,
    val isTest: Boolean,
    val status: String = "OPEN",
    val responderUid: String = "",
    val note: String = "",
    val updatedAt: Long = 0
)

internal fun planAuditMisplacementAlerts(result: F03AnalyzeAndCompareResponse, actor: String): List<AuditMisplacementAlert> {
    val company = result.companyCode?.takeIf(String::isNotBlank) ?: return emptyList()
    val job = result.jobId?.takeIf { it.matches(Regex("[a-f0-9]{32}")) } ?: return emptyList()
    val warehouse = (result.warehouseCode ?: result.erpWarehouse)?.takeIf(String::isNotBlank) ?: return emptyList()
    val test = result.verificationScope == "test_reference_product_locations"
    val live = result.verificationScope == "read_product_code_and_verified_location_quantity_unverified" &&
        result.dataSource == "FIREBASE_READ_ONLY"
    if ((!test && !live) || actor.isBlank() || result.erpComparisonAvailable == false || !result.inventoryIssues.isNullOrEmpty()) return emptyList()
    return result.results.orEmpty().mapNotNull { row ->
        if (row.f04StatusCode != 2 || !row.locationVerified) return@mapNotNull null
        if (test && row.identityMethod != "TEST_REFERENCE_APPEARANCE") return@mapNotNull null
        if (live && row.identityMethod !in setOf("OCR_PRODUCT_CODE", "REGISTERED_BARCODE")) return@mapNotNull null
        val expected = row.erpProductIds.singleOrNull()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val observed = row.aiProductIds.orEmpty().singleOrNull()?.takeIf { it.isNotBlank() && it != expected } ?: return@mapNotNull null
        val inventoryId = row.erpInventoryIds.orEmpty().singleOrNull()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val location = row.locationLabel?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val target = result.results.filter { it.erpProductIds == listOf(observed) }
            .mapNotNull { it.locationLabel }.distinct().singleOrNull().orEmpty()
        val key = listOf(company, actor, job, warehouse, row.positionId ?: location, expected, observed, test.toString())
            .joinToString("\u0000")
        val id = "AI_" + MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        AuditMisplacementAlert(id, company, actor, job, inventoryId, warehouse, location, expected, observed,
            row.productNames?.get(expected) ?: expected, row.productNames?.get(observed) ?: observed, target, test)
    }.distinctBy { it.id }
}
