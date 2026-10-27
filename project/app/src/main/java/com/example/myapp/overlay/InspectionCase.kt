package com.example.myapp.overlay

import android.content.Context
import android.content.Intent
import java.io.File
import java.security.MessageDigest

/** A stable warehouse ID and photo filename identify one local inspection. */
data class InspectionCase(val warehouseId: Int, val photoName: String) {
    init {
        require(warehouseId > 0)
        require(photoName.isNotBlank() && photoName == File(photoName).name && !photoName.contains('\\'))
    }
    val key: String get() = MessageDigest.getInstance("SHA-256")
        .digest("$warehouseId/$photoName".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    val isDemo: Boolean get() = warehouseId == 1 && photoName == "warehouse_sample.jpg"
    fun photo(context: Context) = File(context.filesDir, "warehouse_photos/$warehouseId/$photoName")
    fun title(context: Context): String = context.getSharedPreferences("warehouse_catalog", Context.MODE_PRIVATE)
        .getString("name_$warehouseId", "창고 $warehouseId") ?: "창고 $warehouseId"
    fun attach(intent: Intent): Intent = intent.putExtra("inspection_warehouse", warehouseId)
        .putExtra("inspection_photo", photoName)
    fun preferences(context: Context): android.content.SharedPreferences {
        val prefs = context.getSharedPreferences("inspection_$key", Context.MODE_PRIVATE)
        if (!prefs.contains("case_created_at")) {
            val edit = prefs.edit()
            if (isDemo) {
                // Preserve the existing photo-1 demo's saved actions once.
                context.getSharedPreferences("f05_warehouse_photo1_45_v2", Context.MODE_PRIVATE).all.forEach { (k,v) ->
                    when (v) {
                        is String -> edit.putString(k,v)
                        is Boolean -> edit.putBoolean(k,v)
                        is Int -> edit.putInt(k,v)
                        is Long -> edit.putLong(k,v)
                        is Float -> edit.putFloat(k,v)
                    }
                }
            }
            edit.putLong("case_created_at", System.currentTimeMillis())
                .putInt("case_warehouse", warehouseId).putString("case_photo", photoName).apply()
        }
        return prefs
    }
    fun status(context: Context): String {
        val prefs = context.getSharedPreferences("inspection_$key", Context.MODE_PRIVATE)
        if (!isDemo) return "분석 대기"
        if (prefs.getBoolean("action_closed", false)) return "검수 완료"
        val phases = prefs.all.filterKeys { it.startsWith("action_record_") }.values.mapNotNull {
            runCatching { org.json.JSONObject(it as String).optString("phase") }.getOrNull()
        }
        return when {
            "recheck_pending" in phases -> "검수 대기"
            "working" in phases -> "조치 중"
            "needs_action" in phases -> "재조치 필요"
            else -> "검수 가능 · 데모"
        }
    }
    companion object {
        fun from(intent: Intent): InspectionCase = InspectionCase(
            intent.getIntExtra("inspection_warehouse", 1),
            intent.getStringExtra("inspection_photo") ?: "warehouse_sample.jpg")
    }
}