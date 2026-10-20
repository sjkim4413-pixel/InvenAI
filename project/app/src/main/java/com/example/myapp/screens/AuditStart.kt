package com.example.myapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import com.bumptech.glide.Glide

class AuditStart : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_audit_start)
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/duxv9i3h_expires_30_days.png").into(findViewById(R.id.rov44kvu00s))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/lyyd6k3q_expires_30_days.png").into(findViewById(R.id.r96weuqy0tyj))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/ovrqq02f_expires_30_days.png").into(findViewById(R.id.ry0z6jgfecc9))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/dqyrj138_expires_30_days.png").into(findViewById(R.id.ry0qbvc6ckr))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/hsimni44_expires_30_days.png").into(findViewById(R.id.rlis7tw39f7b))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/nvldjhp1_expires_30_days.png").into(findViewById(R.id.rbpi51zl9dq))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/6nbtlxxd_expires_30_days.png").into(findViewById(R.id.rnzddmx1gmt))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/rl1lf336_expires_30_days.png").into(findViewById(R.id.rrvyode0kr7))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/8pk2yyg6_expires_30_days.png").into(findViewById(R.id.rbacjdf7m65n))

		// 상단 뒤로가기 버튼
		findViewById<View>(R.id.r96weuqy0tyj)?.setOnClickListener { finish() }

		// 하단 네비게이션 바
		// 1. 홈
		findViewById<View>(R.id.rlis7tw39f7b)?.setOnClickListener {
			val intent = Intent(this, HomeDashboard::class.java)
			intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
			startActivity(intent)
			finish()
		}
		// 2. 재고
		findViewById<View>(R.id.rfgttzwpdgsv)?.setOnClickListener {
			startActivity(Intent(this, InventoryList::class.java))
		}
		// 3. 스캔
		val openScanner = View.OnClickListener {
			startActivity(Intent(this, BarcodeScanner::class.java))
		}
		findViewById<View>(R.id.rbacjdf7m65n)?.setOnClickListener(openScanner)
		findViewById<View>(R.id.rygg1q1smwrl)?.setOnClickListener(openScanner)

		// 4. 작업
		findViewById<View>(R.id.r9lvydb052fq)?.setOnClickListener {
			startActivity(Intent(this, WorkHistory::class.java))
		}
		// 5. 더보기 (드롭바/바텀시트 메뉴)
		findViewById<View>(R.id.r3sd4rh47xqh)?.setOnClickListener {
			MenuHelper.showMoreMenu(this)
		}

		val button1: View? = findViewById(R.id.rbn1juwucwum)
		button1?.setOnClickListener {
			startActivity(Intent(this, AiAuditCamera::class.java))
		}
		val button2: View? = findViewById(R.id.rgb1sqjwlxjm)
		button2?.setOnClickListener {
			startActivity(Intent(this, AiAuditCamera::class.java))
		}
	}
}