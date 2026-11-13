package com.example.myapp

data class WarehouseMapping(
    val companyCode: String,
    val warehouseId: Int,
    val firebaseWarehouseCode: String
)

object AuditWarehouseMapper {

    fun resolve(
        session: AuditSession,
        mappings: List<WarehouseMapping>
    ): String? {

        val matches = mappings.filter { mapping ->
            mapping.companyCode.trim().uppercase() ==
                session.companyCode.trim().uppercase() &&
            mapping.warehouseId == session.warehouseId &&
            mapping.firebaseWarehouseCode.isNotBlank()
        }

        // 매핑이 없거나 중복되면 자동 연결 차단
        if (matches.size != 1) return null

        return matches.first()
            .firebaseWarehouseCode
            .trim()
    }
}
