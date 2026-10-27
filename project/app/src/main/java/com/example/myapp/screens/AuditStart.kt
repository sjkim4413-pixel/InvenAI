package com.example.myapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide

class AuditStart : AppCompatActivity() {
	private var selectedZone: String = "B구역"
	private var selectedRackId: String = "B구역 R05"

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_audit_start)
		findViewById<View>(R.id.rov44kvu00s)?.visibility = View.GONE
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
		findViewById<View>(R.id.rlis7tw39f7b)?.setOnClickListener {
			val intent = Intent(this, HomeDashboard::class.java)
			intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
			startActivity(intent)
			finish()
		}
		findViewById<View>(R.id.rfgttzwpdgsv)?.setOnClickListener {
			startActivity(Intent(this, InventoryList::class.java))
		}
		val openScanner = View.OnClickListener {
			startActivity(Intent(this, BarcodeScanner::class.java))
		}
		findViewById<View>(R.id.rbacjdf7m65n)?.setOnClickListener(openScanner)
		findViewById<View>(R.id.rygg1q1smwrl)?.setOnClickListener(openScanner)
		findViewById<View>(R.id.r9lvydb052fq)?.setOnClickListener {
			startActivity(Intent(this, WorkHistory::class.java))
		}
		findViewById<View>(R.id.r3sd4rh47xqh)?.setOnClickListener {
			MenuHelper.showMoreMenu(this)
		}

		// 2. 구역 선택 버튼
		findViewById<View>(R.id.rbn1juwucwum)?.setOnClickListener {
			val zones = arrayOf("A구역", "B구역", "C구역", "D구역")
			AlertDialog.Builder(this)
				.setTitle("실사 대상 구역 선택")
				.setItems(zones) { _, which ->
					selectedZone = zones[which]
					selectedRackId = "$selectedZone R05"
					Toast.makeText(this, "[$selectedZone]이 실사 구역으로 지정되었습니다.", Toast.LENGTH_SHORT).show()
				}
				.show()
		}

		// 3. 개별 랙 선택 버튼 (A01~A05 등)
		val rackClick = View.OnClickListener { v ->
			val rackNo = when(v.id) {
				R.id.rgb1sqjwlxjm -> "R01"
				R.id.rj6alsyhivu -> "R02"
				R.id.rtwmfq85qt5 -> "R03"
				R.id.ru26cdip6k2 -> "R04"
				R.id.r4wdp4899azj -> "R05"
				else -> "R05"
			}
			selectedRackId = "$selectedZone $rackNo"
			Toast.makeText(this, "실사 타겟 랙 [$selectedRackId]이 지정되었습니다.", Toast.LENGTH_LONG).show()
		}
		findViewById<View>(R.id.rgb1sqjwlxjm)?.setOnClickListener(rackClick)
		findViewById<View>(R.id.rj6alsyhivu)?.setOnClickListener(rackClick)
		findViewById<View>(R.id.rtwmfq85qt5)?.setOnClickListener(rackClick)
		findViewById<View>(R.id.ru26cdip6k2)?.setOnClickListener(rackClick)
		findViewById<View>(R.id.r4wdp4899azj)?.setOnClickListener(rackClick)

		// 1. 메인 [카메라로 실사 시작] 버튼 클릭 ➔ 선택된 랙 ID를 카메라 화면으로 전달
		findViewById<View>(R.id.roxu0u1g02kd)?.setOnClickListener {
			val intent = Intent(this, AiAuditCamera::class.java).apply {
				putExtra("TARGET_RACK_ID", selectedRackId)
			}
			startActivity(intent)
		}
	}
}
