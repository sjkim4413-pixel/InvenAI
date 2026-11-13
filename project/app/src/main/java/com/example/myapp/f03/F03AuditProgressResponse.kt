
package com.example.myapp

import com.google.gson.annotations.SerializedName

// F-03 실사 진행 현황 전체 응답
data class F03AuditProgressResponse(
    val project: String,
    val module: String,

    @SerializedName("data_source")
    val dataSource: String,

    val summary: F03AuditSummary,

    val locations: List<F03AuditLocation>
)

// 실사 진행 현황 요약
data class F03AuditSummary(
    val total: Int,
    val completed: Int,

    @SerializedName("simulated_completed")
    val simulatedCompleted: Int,

    val recheck: Int,

    @SerializedName("not_checked")
    val notChecked: Int
)

// 랙별 상세 위치 상태
data class F03AuditLocation(
    @SerializedName("location_id")
    val locationId: String,

    @SerializedName("audit_state")
    val auditState: String
)
