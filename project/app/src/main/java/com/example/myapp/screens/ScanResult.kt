package com.example.myapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide

class ScanResult : AppCompatActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_scan_result)

		// 이미지 로딩
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/a6r81hrn_expires_30_days.png").into(findViewById(R.id.rs501264yjoh))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/huika3vs_expires_30_days.png").into(findViewById(R.id.rtyhqvwzh9v))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/4jf0l1ha_expires_30_days.png").into(findViewById(R.id.rehogsrtzjtt))

		val targetLocation = intent.getStringExtra("TARGET_LOCATION") ?: "B구역 R05-3층"
		val scannedLocation = intent.getStringExtra("SCANNED_LOCATION") ?: "B구역 R05-3층"
		val isMatch = targetLocation == scannedLocation

		if (isMatch) {
			Toast.makeText(this, "🟢 적재 위치 일치: $scannedLocation", Toast.LENGTH_SHORT).show()
		} else {
			Toast.makeText(this, "🔴 ⚠️ 오적재 경고! 목표: $targetLocation / 스캔: $scannedLocation", Toast.LENGTH_LONG).show()
		}

		// 상단 뒤로가기 버튼
		findViewById<View>(R.id.rs501264yjoh)?.setOnClickListener { finish() }

		// 하단 네비게이션 바
		findViewById<View>(R.id.r8amufvebl0f)?.setOnClickListener {
			val intent = Intent(this, HomeDashboard::class.java)
			intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
			startActivity(intent)
			finish()
		}
		findViewById<View>(R.id.r44bpz2b5xd)?.setOnClickListener {
			startActivity(Intent(this, InventoryList::class.java))
		}
		findViewById<View>(R.id.rt6mzo7uanci)?.setOnClickListener {
			startActivity(Intent(this, WorkHistory::class.java))
		}
		findViewById<View>(R.id.r8cwkejnored)?.setOnClickListener {
			MenuHelper.showMoreMenu(this)
		}

		// [적재 완료 증빙 사진 촬영] 버튼
		val btnConfirm: View? = findViewById(R.id.rezvoesofeq)
		btnConfirm?.setOnClickListener {
			val intent = Intent(this, PhotoConfirmation::class.java)
			intent.putExtra("TARGET_LOCATION", targetLocation)
			intent.putExtra("SCANNED_LOCATION", scannedLocation)
			startActivity(intent)
		}
	}
}