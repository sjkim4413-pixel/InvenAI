package com.example.myapp

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide

class AuditPhotoPreviewModel : ViewModel() {
    var bytes: ByteArray? = null
}

class PhotoConfirmation : WarehouseScreen() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openScreen("실사 사진 확인", navigation = false)
        val warehouse = intent.getStringExtra("WAREHOUSE_CODE")
        val mode = if (intent.getBooleanExtra(EXTRA_TEST_RACK_LABELS, false)) "test_rack_labels"
            else intent.getStringExtra(EXTRA_AUDIT_TEST_MODE)
        val problem = auditPhotoWarehouseProblem(warehouse, mode, intent.getStringExtra(EXTRA_AUDIT_TEST_WAREHOUSE))
        if (problem != null) {
            CapturedPhotoHolder.getAndClear()
            label(problem, true)
            button("실사 창고 다시 선택", primary = true) {
                startActivity(Intent(this, AuditStart::class.java))
                finish()
            }
            return
        }
        label("실사 창고 · $warehouse", true)
        intent.getStringExtra(EXTRA_AUDIT_TEST_TITLE)?.let { label(it, true) }
        val model = ViewModelProvider(this)[AuditPhotoPreviewModel::class.java]
        if (model.bytes == null) model.bytes = CapturedPhotoHolder.getAndClear()
        val photo = model.bytes
        if (photo == null) {
            label("선택된 사진이 없습니다. 사진을 다시 촬영하거나 선택해 주세요.")
        } else {
            val preview = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "실사 사진"
                background = WarehouseAppearance.shape(this@PhotoConfirmation, WarehouseAppearance.INK)
                clipToOutline = true
            }
            body.addView(preview, android.widget.LinearLayout.LayoutParams(-1, dp(430)).apply { bottomMargin = dp(18) })
            Glide.with(this).load(photo).fitCenter().into(preview)
            button("이 사진 분석", primary = true, icon = R.drawable.ic_inven_audit) {
                CapturedPhotoHolder.photoBytes = photo
                startActivity(Intent(this, AiAuditResults::class.java).putExtras(intent))
                finish()
            }.id = R.id.auditAnalyzePhoto
        }
        button("다시 촬영 또는 선택") {
            startActivity(Intent(this, AiAuditCamera::class.java).putExtras(intent))
            finish()
        }
    }
}
