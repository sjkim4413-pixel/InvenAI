package com.example.myapp

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.junit.Assert.*
import org.junit.Test

class WarehouseFlowTest {
    private val item = InventoryItem(
        itemCode = "001234", itemName = "상품", quantity = 10, location = "B구역 R05-3층",
        warehouse = "창고 1", documentId = "ABC-002_001234"
    )

    @Test fun stockChangesUseLatestQuantityAndPreserveItemIdentity() {
        val received = applyStockChange(item, item, WarehouseAction.INBOUND, 5, item.location)
        assertEquals(15, received.quantity)
        val shipped = applyStockChange(received, item, WarehouseAction.OUTBOUND, 4, item.location)
        assertEquals(11, shipped.quantity)
        assertEquals("NORMAL", shipped.status)
        val relocated = applyStockChange(shipped, item, WarehouseAction.TRANSFER, 0, "B구역 R07-1층")
        assertEquals(11, relocated.quantity)
        assertEquals("B구역 R07-1층", relocated.location)
        assertEquals(item.documentId, relocated.documentId)
        assertEquals("OUTBOUND_COMPLETE",
            applyStockChange(relocated, relocated, WarehouseAction.OUTBOUND, 11, relocated.location).status)
    }

    @Test fun impossibleAndAnomalousStockChangesAreRejected() {
        assertThrows(IllegalStateException::class.java) {
            applyStockChange(item, item, WarehouseAction.OUTBOUND, 11, item.location)
        }
        assertThrows(IllegalStateException::class.java) {
            applyStockChange(item.copy(status = "PENDING_QA"), item, WarehouseAction.TRANSFER, 0, "R09-2F")
        }
    }

    @Test fun csvUsesSpecifiedQuantitiesAndPreservesCodesAndQuotedNames() {
        val input = INVENTORY_CSV_HEADER + "\r\n00123,\"케이블, 2m\",0,창고 1,B구역 R07-1층\r\n"
        val parsed = parseInventoryCsv(input).single()
        assertEquals("00123", parsed.itemCode)
        assertEquals("케이블, 2m", parsed.itemName)
        assertEquals(0, parsed.quantity)
        assertThrows(IllegalArgumentException::class.java) {
            parseInventoryCsv(input + "00123,중복,1,창고 1,B구역 R07-1층")
        }
    }

    @Test fun scannerReadsQrAndEanFromLuminance() {
        for ((format, text, mode) in listOf(
            Triple(BarcodeFormat.QR_CODE, "R07-1F", 2),
            Triple(BarcodeFormat.EAN_13, "4006381333931", 1)
        )) {
            val image = MultiFormatWriter().encode(text, format, 250, 150)
            val luma = ByteArray(image.width * image.height) { index ->
                if (image[index % image.width, index / image.width]) 0 else (-1).toByte()
            }
            assertEquals(text, decodeWarehouseBarcode(luma, image.width, image.height, mode))
        }
    }
}
