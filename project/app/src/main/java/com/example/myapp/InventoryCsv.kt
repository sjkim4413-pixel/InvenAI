package com.example.myapp

internal const val INVENTORY_CSV_HEADER = "itemCode,itemName,quantity,warehouse,location"

internal fun parseInventoryCsv(input: String): List<InventoryItem> {
    val rows = mutableListOf<List<String>>()
    val row = mutableListOf<String>()
    val value = StringBuilder()
    val text = input.removePrefix("\uFEFF")
    var quoted = false
    var afterQuote = false

    fun field() {
        row.add(value.toString().trim())
        value.clear()
        afterQuote = false
    }

    fun line() {
        field()
        if (row.any(String::isNotEmpty)) rows.add(row.toList())
        row.clear()
    }

    var index = 0
    while (index < text.length) {
        val char = text[index++]
        if (quoted) {
            if (char == '"') {
                if (text.getOrNull(index) == '"') {
                    value.append('"')
                    index++
                } else {
                    quoted = false
                    afterQuote = true
                }
            } else value.append(char)
        } else when (char) {
            '"' -> {
                require(value.isEmpty() && !afterQuote) { "CSV 따옴표 형식을 확인해 주세요." }
                quoted = true
            }
            ',' -> field()
            '\r', '\n' -> {
                if (char == '\r' && text.getOrNull(index) == '\n') index++
                line()
            }
            else -> {
                require(!afterQuote) { "CSV 따옴표 뒤에는 쉼표 또는 줄바꿈이 필요합니다." }
                value.append(char)
            }
        }
    }
    require(!quoted) { "닫히지 않은 CSV 따옴표가 있습니다." }
    if (value.isNotEmpty() || row.isNotEmpty() || afterQuote) line()
    require(rows.firstOrNull() == INVENTORY_CSV_HEADER.split(',')) { "CSV 열 이름과 순서를 샘플과 맞춰 주세요." }
    require(rows.size > 1) { "CSV에 재고 데이터가 없습니다." }
    val seen = mutableSetOf<String>()
    return rows.drop(1).mapIndexed { i, fields ->
        val rowNumber = i + 2
        require(fields.size == 5) { "${rowNumber}행의 열 개수가 다릅니다." }
        val (code, name, quantity, warehouse, location) = fields
        val count = quantity.toIntOrNull()
        require(code.length in 1..120 && '/' !in code && name.length in 1..240 &&
            count != null && count >= 0 && warehouse.length in 1..160 && location.length in 1..160 &&
            listOf(code, name, warehouse, location).none { field -> field.any(Char::isISOControl) }) {
            "${rowNumber}행의 상품 코드, 수량, 창고 또는 위치가 올바르지 않습니다."
        }
        require(seen.add(code.uppercase(java.util.Locale.ROOT))) { "${rowNumber}행의 상품 코드가 CSV 내에서 중복됩니다." }
        InventoryItem(itemCode = code, itemName = name, quantity = count, warehouse = warehouse, location = location)
    }
}
