package com.example.myapp.overlay

import com.example.myapp.R

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.widget.*
import org.json.JSONObject

/** F-05는 표시를 맡고, 조치 상태/검수 승인은 이 모듈에서 처리합니다.
 * 현재는 한 기기에서 A/B/C 역할을 바꿔보는 로컬 시연입니다.
 * F-06 서버, Firebase, 실제 계정에는 요청을 보내지 않습니다.
 */
class F07ReinspectionController(
    private val context: Context,
    private val items: List<OverlayItem>,
    private val inspection: InspectionCase,
    private val onChanged: () -> Unit
) {
    private val prefs = inspection.preferences(context)
    private val state = F07State()
    private val actors = listOf(F07Actor("demo-a", "작업자 A", "worker"),
        F07Actor("demo-b", "작업자 B", "worker"), F07Actor("demo-c", "검수자 C", "inspector"))
    var actor: F07Actor = actors.first()
        private set

    init {
        actor = actors.firstOrNull { it.id == prefs.getString("action_actor", "demo-a") } ?: actors.first()
        state.closed = prefs.getBoolean("action_closed", false)
        items.forEach { item ->
            val raw = prefs.getString("action_record_${item.location}", null)
            if (raw != null) {
                try {
                    val j = JSONObject(raw)
                    val phase = j.getString("phase")
                    require(phase in setOf("open", "working", "recheck_pending", "resolved", "needs_action"))
                    state.records[item.location] = F07Record(phase, j.optString("owner_id"),
                        j.optString("owner_name"), j.optString("submitted_id"), j.optString("original_id"),
                        j.optString("reviewer_id"), j.optInt("revision"), j.optLong("updated_at"))
                } catch (_: Exception) {
                    state.closed = false
                    state.records[item.location] = F07Record(phase = "needs_action", originalId = item.recognizedId)
                }
            } else if (prefs.getString("f07_record_${item.location}", null) != null) {
                // 이전 버전의 검수 대기 상태를 이어받습니다.
                try {
                    val old = JSONObject(prefs.getString("f07_record_${item.location}", "{}") ?: "{}")
                    val phase = old.optString("phase")
                    if (phase in setOf("recheck_pending", "needs_action", "resolved"))
                        state.records[item.location] = F07Record(phase = phase, ownerName = "이전 테스트 작업자",
                            submittedId = item.recognizedId, originalId = item.recognizedId)
                } catch (_: Exception) { /* 기존 F-05 판정은 유지 */ }
            }
        }
    }

    fun pendingLocations(): Set<String> = state.pendingLocations()
    fun workingLocations(): Set<String> = state.records.filterValues { it.phase == "working" }.keys.toSet()
    fun needsActionLocations(): Set<String> = state.records.filterValues { it.phase == "needs_action" }.keys.toSet()
    fun isClosed(): Boolean = state.closed
    fun canFinish(): Boolean = actor.role == "inspector" && state.canFinish(items.map { it.status })
    fun labelFor(item: OverlayItem): String = when (state.records[item.location]?.phase) {
        "working" -> "조치 중"
        "recheck_pending" -> "검수 대기"
        "needs_action" -> "${statusName(item.status)} · 재조치 필요"
        else -> statusName(item.status)
    }

    fun chooseActor() {
        AlertDialog.Builder(context).setTitle("시연 계정 선택 · 이 기기에서만 전환")
            .setSingleChoiceItems(actors.map { it.name }.toTypedArray(), actors.indexOf(actor)) { dialog, index ->
                actor = actors[index]
                prefs.edit().putString("action_actor", actor.id).apply()
                dialog.dismiss(); onChanged()
            }.setNegativeButton("닫기", null).show()
    }

    private fun persist(item: OverlayItem? = null) {
        val edit = prefs.edit().putBoolean("action_closed", state.closed)
            .putLong("case_updated_at", System.currentTimeMillis())
        if (state.closed) edit.putLong("case_completed_at", System.currentTimeMillis())
        state.records.forEach { (location, r) ->
            edit.putString("action_record_$location", JSONObject().put("phase", r.phase)
                .put("owner_id", r.ownerId).put("owner_name", r.ownerName).put("submitted_id", r.submittedId)
                .put("original_id", r.originalId).put("reviewer_id", r.reviewerId)
                .put("revision", r.revision).put("updated_at", r.updatedAt).toString())
        }
        items.forEach {
            edit.putString("id_${it.location}", it.recognizedId).putInt("status_${it.location}", it.status)
                .putBoolean("edited_${it.location}", it.manuallyEdited)
        }
        edit.apply(); onChanged()
        if (item != null) {
            val record = state.records[item.location]
            val detail = when (record?.phase) {
                "working" -> "${record.ownerName}가 조치 중입니다."
                "recheck_pending" -> "${record.ownerName} 수정 완료 · 검수자 확인이 필요합니다."
                "resolved" -> "${actor.name}가 정상으로 확인했습니다."
                "needs_action" -> "${statusName(item.status)} · 재조치가 필요합니다."
                else -> "조치가 해제되었습니다. 다른 작업자가 시작할 수 있습니다."
            }
            // 시연 중인 이 기기의 동일 알림을 교체합니다. 실제 다중 기기 발송은 F-06 담당.
            F07DeviceNotice.update(context, inspection, item.location, labelFor(item), detail)
        }
    }

    fun showDetail(item: OverlayItem) {
        val record = state.records[item.location]
        val column = panel()
        val color = when (record?.phase) {
            "recheck_pending" -> Color.WHITE
            "working" -> Color.rgb(190, 112, 0)
            else -> statusColor(item.status)
        }
        column.addView(TextView(context).apply {
            text = labelFor(item); textSize = 20f; setTextColor(color)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            if (color == Color.WHITE) {
                setBackgroundColor(Color.rgb(36, 49, 65)); setPadding(16, 12, 16, 12)
            }
        })
        column.addView(label("전산 예상 ID: ${item.expectedId.ifBlank { "없음" }}\n최초 인식 ID: ${(record?.originalId ?: item.recognizedId).ifBlank { "없음" }}"))
        if (record != null && record.ownerName.isNotBlank()) column.addView(label("조치자: ${record.ownerName}"))
        if (record != null && record.submittedId.isNotBlank()) column.addView(label("수정 보고 ID: ${record.submittedId}"))
        val builder = AlertDialog.Builder(context).setTitle(displayLocation(item.location)).setView(ScrollView(context).apply { addView(column) })
            .setNegativeButton("닫기", null)
        if (state.closed || item.status == 1 && record?.phase !in setOf("working", "recheck_pending", "needs_action")) {
            builder.show(); return
        }
        if (record?.phase == "recheck_pending") {
            column.addView(label(if (actor.role == "inspector") "작업자가 수정 완료를 보고했습니다. 실물과 바코드를 확인해주세요." else "수정 완료 보고가 접수되었습니다. 검수자의 확인을 기다려주세요."))
            if (actor.role == "inspector") builder.setPositiveButton("수동으로 확인") { _, _ -> showReview(item) }
            builder.show(); return
        }
        if (actor.role == "inspector") {
            column.addView(label(if (record?.phase == "working") "${record.ownerName}가 조치 중입니다." else "작업자의 수정 완료 보고를 기다리고 있습니다."))
            builder.show(); return
        }
        val mine = record?.phase == "working" && record.ownerId == actor.id
        val occupied = record?.phase == "working" && !mine
        val toggle = Switch(context).apply {
            text = if (occupied) "${record?.ownerName} 조치 중" else "내가 조치하기"
            textSize = 18f; isChecked = record?.phase == "working"; isEnabled = !occupied
            setPadding(0, 16, 0, 16)
        }
        val input = EditText(context).apply {
            hint = "실물 바코드 / 수정한 ID"; textSize = 18f; setSingleLine(true)
            isEnabled = mine
            if (mine) setText(prefs.getString("action_draft_${item.location}_${actor.id}", ""))
        }
        column.addView(toggle)
        column.addView(label(if (occupied) "다른 작업자가 처리 중이므로 수정할 수 없습니다."
            else "스위치를 켜면 조치 담당자로 표시됩니다. 창을 닫아도 조치 중 상태는 유지됩니다."))
        column.addView(input)
        builder.setPositiveButton("수정 완료", null)
        val dialog = builder.create()
        toggle.setOnCheckedChangeListener { _, checked ->
            val ok = if (checked) state.claim(item.location, actor, item.status, item.recognizedId)
                else state.release(item.location, actor)
            if (!ok) { message("다른 작업자가 처리 중이거나 상태가 변경됐습니다."); dialog.dismiss(); showDetail(item) }
            else {
                if (!checked) prefs.edit().remove("action_draft_${item.location}_${actor.id}").apply()
                persist(item); dialog.dismiss(); showDetail(item)
            }
        }
        // 대화상자를 닫거나 앱을 재시작해도 초안과 담당 상태를 유지합니다.
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (mine) prefs.edit().putString("action_draft_${item.location}_${actor.id}", s?.toString() ?: "").apply()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = mine
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (input.text.toString().isBlank()) { input.error = "확인한 바코드 ID를 입력해주세요."; return@setOnClickListener }
                if (state.submit(item.location, actor, input.text.toString())) {
                    // 제출만으로 기존 판정/인식 ID를 정상으로 변경하지 않습니다.
                    persist(item); dialog.dismiss(); message("수정 완료 보고 · 검수 대기로 변경했습니다.")
                } else { message("조치 담당자와 상태를 다시 확인해주세요."); dialog.dismiss() }
            }
        }
        dialog.show()
    }

    private fun showReview(item: OverlayItem) {
        val record = state.records[item.location] ?: return
        val revision = record.revision
        val column = panel()
        column.addView(label("전산 예상 ID: ${item.expectedId.ifBlank { "없음" }}\n작업자 보고 ID: ${record.submittedId}\n\n실물과 적재 위치를 확인한 후, 직접 확인한 바코드를 입력해주세요."))
        val input = EditText(context).apply { hint = "검수자가 확인한 ID"; setSingleLine(true); textSize = 18f }
        val confirm = CheckBox(context).apply { text = "실물과 적재 위치를 직접 확인했습니다"; textSize = 16f }
        column.addView(input); column.addView(confirm)
        val dialog = AlertDialog.Builder(context).setTitle("${displayLocation(item.location)} · 검수자 확인")
            .setView(ScrollView(context).apply { addView(column) }).setNegativeButton("취소", null)
            .setNeutralButton("재조치 요청", null).setPositiveButton("정상 확인", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val id = input.text.toString().trim()
                when {
                    !confirm.isChecked -> message("실물과 적재 위치를 확인한 뒤 체크해주세요.")
                    item.expectedId.isBlank() -> message("전산 예상 정보가 없어 정상 확정할 수 없습니다.")
                    id != item.expectedId -> { input.error = "예상 ID와 다릅니다. 재조치를 요청해주세요." }
                    else -> {
                        if (state.review(item.location, actor, revision, true)) {
                            item.recognizedId = id; item.status = 1; item.manuallyEdited = true
                            persist(item); message("정상 확인 완료"); dialog.dismiss()
                        } else { message("이미 처리되었거나 상태가 바뀌었습니다."); dialog.dismiss() }
                    }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                AlertDialog.Builder(context).setTitle("재조치를 요청할까요?")
                    .setMessage("정상으로 확정하지 않고 작업자에게 다시 조치가 필요하다고 표시합니다.")
                    .setNegativeButton("취소", null).setPositiveButton("요청") { _, _ ->
                        if (state.review(item.location, actor, revision, false)) {
                            if (item.status == 1) item.status = 2
                            persist(item); message("${statusName(item.status)} · 재조치 필요")
                        }
                        dialog.dismiss()
                    }.show()
            }
        }
        dialog.show()
    }

    fun showDashboard() {
        val targets = items.filter { state.records[it.location]?.phase in setOf("working", "recheck_pending", "needs_action") }
        val builder = AlertDialog.Builder(context).setTitle("조치 현황").setNegativeButton("닫기", null)
        if (targets.isEmpty()) builder.setMessage("진행 중인 조치가 없습니다.")
        else builder.setItems(targets.map {
            "${displayLocation(it.location)} · ${labelFor(it)}\n${state.records[it.location]?.ownerName.orEmpty()}"
        }.toTypedArray()) { _, index -> showDetail(targets[index]) }
        builder.show()
    }

    fun confirmFinish() {
        if (!canFinish()) { message("검수자가 모든 항목을 정상 확인한 뒤 완료할 수 있습니다."); return }
        AlertDialog.Builder(context).setTitle("검수를 완료할까요?")
            .setMessage("전체 정상 결과를 이 기기에 저장합니다. 서버·ERP 반영은 아직 연결되지 않았습니다.")
            .setNegativeButton("취소", null).setPositiveButton("완료") { _, _ ->
                if (state.finish(actor, items.map { it.status })) { persist(); message("검수 완료 · 기기에 저장했습니다.") }
            }.show()
    }

    private fun panel() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = (20 * context.resources.displayMetrics.density).toInt(); setPadding(p, p/2, p, p/2)
    }
    private fun label(value: String) = TextView(context).apply {
        text = value; textSize = 16f; setTextColor(Color.rgb(22, 39, 60)); setPadding(0, 12, 0, 12)
    }
    private fun message(value: String) { Toast.makeText(context, value, Toast.LENGTH_LONG).show() }
}