package com.example.myapp

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide

class AiAuditResults : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_ai_audit_results)

		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/19op4ea3_expires_30_days.png").into(findViewById(R.id.r92jaxcy3c4))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/lxazxxpk_expires_30_days.png").into(findViewById(R.id.r3qa1ncjec6t))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/87expjfj_expires_30_days.png").into(findViewById(R.id.rs6psw8xg03l))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/qmh90k2b_expires_30_days.png").into(findViewById(R.id.rjt4y2gmsgjf))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/e9gzahm3_expires_30_days.png").into(findViewById(R.id.rl54k66oqeuj))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/obbizl46_expires_30_days.png").into(findViewById(R.id.rktb3cut3f88))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/dqo0yxnb_expires_30_days.png").into(findViewById(R.id.rte7pemp4q98))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/fvw57hul_expires_30_days.png").into(findViewById(R.id.r9hjk1laagf4))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/4k7aazjx_expires_30_days.png").into(findViewById(R.id.re4xglprgpaq))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/x5hjnz9j_expires_30_days.png").into(findViewById(R.id.rh7vg1bgh24r))

		// 카메라로 촬영한 실제 사진 수신 및 표출
		val photoBytes = intent.getByteArrayExtra("CAPTURED_PHOTO")
		val ivPhoto = findViewById<ImageView>(R.id.r0ygmbl1hl7wq)
		if (photoBytes != null && ivPhoto != null) {
			val bitmap = BitmapFactory.decodeByteArray(photoBytes, 0, photoBytes.size)
			ivPhoto.setImageBitmap(bitmap)
			ivPhoto.scaleType = ImageView.ScaleType.CENTER_CROP
		} else if (ivPhoto != null) {
			Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/uzofup9p_expires_30_days.png").into(ivPhoto)
		}

		// 상단 뒤로가기
		findViewById<View>(R.id.r3qa1ncjec6t)?.setOnClickListener { finish() }

		// 1. [재촬영] 버튼
		val btnRetake: View? = findViewById(R.id.rn409sprmgg)
		btnRetake?.setOnClickListener {
			startActivity(Intent(this, AiAuditCamera::class.java))
			finish()
		}

		// 2. [🚨 오적재 보고서 저장 및 적재자 알림 발송] 버튼
		val btnSendAlert: View? = findViewById(R.id.r0e5j960n87jp)
		btnSendAlert?.setOnClickListener {
			AnomalyManager.sendMisplacementAlert(
				context = this,
				itemCode = "M-100",
				itemName = "무선 마우스 M-100",
				currentLocation = "A구역 R01-1층 (오적재)",
				targetLocation = "B구역 R05-3층 (올바른 위치)",
				quantity = 50,
				loaderUserId = "loader"
			) {
				Toast.makeText(this, "AI 실사 보고서 저장 및 오적재 알림 발송 완료!", Toast.LENGTH_SHORT).show()
				finish()
			}
		}
	}
}