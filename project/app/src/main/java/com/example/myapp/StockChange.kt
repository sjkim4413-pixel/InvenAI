package com.example.myapp

internal val InventoryItem.needsReview: Boolean
    get() = status in setOf("ANOMALY", "PENDING_QA")

internal fun applyStockChange(
    previous: InventoryItem?,
    selected: InventoryItem,
    action: WarehouseAction,
    amount: Int,
    destination: String
): InventoryItem {
    require(action in setOf(WarehouseAction.INBOUND, WarehouseAction.OUTBOUND, WarehouseAction.TRANSFER,
        WarehouseAction.CORRECTION, WarehouseAction.IMPORT))
    if (previous == null) {
        check(action == WarehouseAction.IMPORT || action == WarehouseAction.INBOUND && amount > 0) {
            "저장된 재고를 선택해 주세요."
        }
        check(selected.warehouse.isNotBlank()) { "창고를 입력해 주세요." }
        return selected.copy(quantity = amount, location = destination, status = "NORMAL")
    }
    check(previous.status in setOf("NORMAL", "정상", "OUTBOUND_COMPLETE")) { "이상 재고는 검수 절차에서 처리해 주세요." }
    check(previous.documentId == selected.documentId && previous.itemCode == selected.itemCode) {
        "선택한 재고 정보가 변경되었습니다."
    }
    if (action in setOf(WarehouseAction.INBOUND, WarehouseAction.IMPORT)) {
        check(previous.location == destination && previous.warehouse == selected.warehouse) {
            "현재 등록된 위치의 재고를 선택해 주세요. 위치 변경은 재고 이동에서 처리합니다."
        }
    }
    if (action == WarehouseAction.TRANSFER) {
        check(previous.quantity > 0 && destination != previous.location && previous.warehouse.isNotBlank()) {
            "보유 수량과 현재 창고를 확인하고 다른 목적지를 지정해 주세요."
        }
    }
    val quantity = when (action) {
        WarehouseAction.INBOUND -> {
            check(amount > 0 && amount <= Int.MAX_VALUE - previous.quantity) { "입고 수량을 확인해 주세요." }
            previous.quantity + amount
        }
        WarehouseAction.OUTBOUND -> {
            check(amount in 1..previous.quantity) { "현재고 범위에서 출고 수량을 입력해 주세요." }
            previous.quantity - amount
        }
        WarehouseAction.TRANSFER -> previous.quantity
        else -> amount
    }
    return previous.copy(
        itemName = if (action == WarehouseAction.IMPORT) selected.itemName else previous.itemName,
        quantity = quantity,
        location = if (action == WarehouseAction.TRANSFER) destination else previous.location,
        status = if (action == WarehouseAction.OUTBOUND && quantity == 0) "OUTBOUND_COMPLETE" else "NORMAL"
    )
}
