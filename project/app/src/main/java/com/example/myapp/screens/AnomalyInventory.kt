package com.example.myapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import com.bumptech.glide.Glide

class AnomalyInventory : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_anomaly_inventory)
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/ca2uyjlh_expires_30_days.png").into(findViewById(R.id.rvr5zkk72xae))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/jn2255fo_expires_30_days.png").into(findViewById(R.id.r4dlso48svyt))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/tz57phta_expires_30_days.png").into(findViewById(R.id.rp42d4osxz4h))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/piax8y50_expires_30_days.png").into(findViewById(R.id.reyi6z9lj9u))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/4fjizdgl_expires_30_days.png").into(findViewById(R.id.rmau6uv0jsms))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/vy5hudv0_expires_30_days.png").into(findViewById(R.id.rpl9g04uz6f))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/78vttzi5_expires_30_days.png").into(findViewById(R.id.rrb0wh93puaf))

		// 상단 뒤로가기 버튼
		findViewById<View>(R.id.r4dlso48svyt)?.setOnClickListener { finish() }

		// 하단 네비게이션 바
		// 1. 홈
		findViewById<View>(R.id.rp42d4osxz4h)?.setOnClickListener {
			val intent = Intent(this, HomeDashboard::class.java)
			intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
			startActivity(intent)
			finish()
		}
		// 2. 재고
		findViewById<View>(R.id.rzxbmf3ceeq)?.setOnClickListener {
			startActivity(Intent(this, InventoryList::class.java))
		}
		// 3. 스캔
		val openScanner = View.OnClickListener {
			startActivity(Intent(this, BarcodeScanner::class.java))
		}
		findViewById<View>(R.id.rrb0wh93puaf)?.setOnClickListener(openScanner)
		findViewById<View>(R.id.rpi1ocgd4f2)?.setOnClickListener(openScanner)

		// 4. 작업
		findViewById<View>(R.id.rjmjgrnlxxw)?.setOnClickListener {
			startActivity(Intent(this, WorkHistory::class.java))
		}
		// 5. 더보기 (드롭바/바텀시트 메뉴)
		findViewById<View>(R.id.ro4g6n5thjl)?.setOnClickListener {
			MenuHelper.showMoreMenu(this)
		}

		val button1: View? = findViewById(R.id.rgaffdocilc)
		button1?.setOnClickListener {
			AnomalyManager.resolveMisplacement(this, "ANO_SAMPLE_001")
		}
	}
}