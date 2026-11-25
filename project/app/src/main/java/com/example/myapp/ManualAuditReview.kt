package com.example.myapp

import java.security.MessageDigest

internal data class ManualAuditScope(
    val companyCode: String,
    val reviewerUid: String,
    val warehouseCode: String,
    val photoDigest: String,
    val testRackLabels: Boolean
)

internal data class ManualAuditPosition(val id: String, val label: String)

internal data class ManualAuditValues(val barcode: String, val quantity: Int)

internal data class ManualAuditReview(val position: ManualAuditPosition, val values: ManualAuditValues)

internal data class ManualAuditState(
    val scope: ManualAuditScope,
    val records: Map<String, ManualAuditReview>
)

internal fun manualAuditValues(barcode: String, quantity: String): ManualAuditValues? {
    val code = barcode.trim()
    if (code.isEmpty() || code.length > 256 || code.any { it.isISOControl() }) return null
    val countText = quantity.trim()
    if (countText.isEmpty() || countText.any { it !in '0'..'9' }) return null
    val count = countText.toIntOrNull() ?: return null
    return ManualAuditValues(code, count)
}

internal fun auditPhotoDigest(bytes: ByteArray): String {
    val hex = "0123456789abcdef"
    return buildString(64) {
        for (byte in MessageDigest.getInstance("SHA-256").digest(bytes)) {
            val value = byte.toInt() and 0xff
            append(hex[value shr 4])
            append(hex[value and 0xf])
        }
    }
}

internal fun F03ComparedPosition.manualAuditPosition(): ManualAuditPosition? {
    if (!locationVerified) return null
    val id = positionId?.takeIf { it.isNotBlank() } ?: return null
    val label = locationLabel?.takeIf { it.isNotBlank() } ?: id
    return ManualAuditPosition(id, label)
}
