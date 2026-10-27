package com.example.myapp.overlay

import com.example.myapp.R

// 로컬 시연용 역할. 실제 연결에서는 로그인 인증으로 검증된 계정/권한을 사용합니다.
data class F07Actor(val id: String, val name: String, val role: String)
data class F07Record(
    var phase: String = "open",
    var ownerId: String = "",
    var ownerName: String = "",
    var submittedId: String = "",
    var originalId: String = "",
    var reviewerId: String = "",
    var revision: Int = 0,
    var updatedAt: Long = 0L
)

/** 화면과 분리된 상태 규칙. 실제 다중 기기 연결 시 같은 조건을 서버 트랜잭션에서 검사해야 합니다. */
class F07State(
    val records: MutableMap<String, F07Record> = mutableMapOf(),
    var closed: Boolean = false
) {
    private fun changed(r: F07Record) { r.revision++; r.updatedAt = System.currentTimeMillis() }

    fun claim(location: String, actor: F07Actor, status: Int, originalId: String): Boolean {
        if (closed || actor.role != "worker" || actor.id.isBlank() || location.isBlank() || status !in 2..5) return false
        val r = records.getOrPut(location) { F07Record(originalId = originalId) }
        if (r.phase !in setOf("open", "needs_action")) return false
        r.phase = "working"; r.ownerId = actor.id; r.ownerName = actor.name
        r.submittedId = ""; r.reviewerId = ""
        changed(r)
        return true
    }

    fun release(location: String, actor: F07Actor): Boolean {
        val r = records[location] ?: return false
        if (closed || actor.role != "worker" || r.phase != "working" || r.ownerId != actor.id) return false
        r.phase = "open"; r.ownerId = ""; r.ownerName = ""; r.submittedId = ""
        changed(r)
        return true
    }

    fun submit(location: String, actor: F07Actor, id: String): Boolean {
        val r = records[location] ?: return false
        if (closed || actor.role != "worker" || r.phase != "working" || r.ownerId != actor.id || id.isBlank()) return false
        r.submittedId = id.trim(); r.phase = "recheck_pending"
        changed(r)
        return true
    }

    fun review(location: String, actor: F07Actor, expectedRevision: Int, normal: Boolean): Boolean {
        val r = records[location] ?: return false
        if (closed || actor.role != "inspector" || actor.id.isBlank() || r.phase != "recheck_pending" || r.revision != expectedRevision) return false
        r.phase = if (normal) "resolved" else "needs_action"
        r.reviewerId = actor.id
        changed(r)
        return true
    }

    fun canFinish(statuses: List<Int>): Boolean = !closed && statuses.isNotEmpty() &&
        statuses.all { it == 1 } && records.values.all { it.phase == "resolved" }

    fun finish(actor: F07Actor, statuses: List<Int>): Boolean {
        if (actor.role != "inspector" || !canFinish(statuses)) return false
        closed = true
        return true
    }

    fun pendingLocations(): Set<String> = records.filterValues { it.phase == "recheck_pending" }.keys.toSet()
}
