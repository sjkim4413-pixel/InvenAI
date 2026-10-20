package com.example.myapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import android.widget.Toast
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
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/uzofup9p_expires_30_days.png").into(findViewById(R.id.r0ygmbl1hl7wq))

		// 상단 뒤로가기
		findViewById<View>(R.id.r3qa1ncjec6t)?.setOnClickListener { finish() }

		// [🚨 적재자에게 오적재 알림 발송] 버튼
		val btnSendAlert: View? = findViewById(R.id.rn409sprmgg)
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
				finish()
			}
		}

		val button2: View? = findViewById(R.id.r0e5j960n87jp)
		button2?.setOnClickListener {
			Toast.makeText(this, "실사 보고서가 보관함에 저장되었습니다.", Toast.LENGTH_SHORT).show()
			finish()
		}
	}
}