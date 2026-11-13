package com.example.myapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class AuditStart : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_audit_start)
		findViewById<View>(R.id.rov44kvu00s)?.visibility = View.GONE

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

		// 1. 메인 [카메라로 실사 시작] 버튼 클릭 ➔ 카메라 촬영 화면 실행
		findViewById<View>(R.id.roxu0u1g02kd)?.setOnClickListener {
			startActivity(Intent(this, AiAuditCamera::class.java))
		}

		// 2. 구역 선택 버튼 (A구역 선택)
		findViewById<View>(R.id.rbn1juwucwum)?.setOnClickListener {
			Toast.makeText(this, "A구역 (A01~A05)이 실사 대상으로 선택되었습니다.", Toast.LENGTH_SHORT).show()
		}

		// 3. 개별 랙 선택 버튼 (A01~A05)
		val rackClick = View.OnClickListener { v ->
			val tag = when(v.id) {
				R.id.rgb1sqjwlxjm -> "A01"
				R.id.rj6alsyhivu -> "A02"
				R.id.rtwmfq85qt5 -> "A03"
				R.id.ru26cdip6k2 -> "A04"
				R.id.r4wdp4899azj -> "A05"
				else -> "랙"
			}
			Toast.makeText(this, "$tag 랙이 실사 대상으로 선택되었습니다.", Toast.LENGTH_SHORT).show()
		}
		findViewById<View>(R.id.rgb1sqjwlxjm)?.setOnClickListener(rackClick)
		findViewById<View>(R.id.rj6alsyhivu)?.setOnClickListener(rackClick)
		findViewById<View>(R.id.rtwmfq85qt5)?.setOnClickListener(rackClick)
		findViewById<View>(R.id.ru26cdip6k2)?.setOnClickListener(rackClick)
		findViewById<View>(R.id.r4wdp4899azj)?.setOnClickListener(rackClick)
	}
}