package com.example.myapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View

class Transfer : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_transfer)
		findViewById<View>(R.id.ryevfqtfn7dn)?.visibility = View.GONE

		// 상단 뒤로가기 버튼
		findViewById<View>(R.id.rf757l1z7cwk)?.setOnClickListener { finish() }

		// 하단 네비게이션 바
		// 1. 홈
		findViewById<View>(R.id.rnvsyv3rx45j)?.setOnClickListener {
			val intent = Intent(this, HomeDashboard::class.java)
			intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
			startActivity(intent)
			finish()
		}
		// 2. 재고
		findViewById<View>(R.id.rdch5ka4s5sm)?.setOnClickListener {
			startActivity(Intent(this, InventoryList::class.java))
		}
		// 3. 스캔
		val openScanner = View.OnClickListener {
			startActivity(Intent(this, BarcodeScanner::class.java))
		}
		findViewById<View>(R.id.rqf9sozej26)?.setOnClickListener(openScanner)
		findViewById<View>(R.id.rgu92wvhmd6n)?.setOnClickListener(openScanner)

		// 4. 작업
		findViewById<View>(R.id.ra9hy0d7k3c)?.setOnClickListener {
			startActivity(Intent(this, WorkHistory::class.java))
		}
		// 5. 더보기 (드롭바/바텀시트 메뉴)
		findViewById<View>(R.id.rcvmn8zbd1hg)?.setOnClickListener {
			MenuHelper.showMoreMenu(this)
		}

		val button1: View? = findViewById(R.id.rzlwjjeiinlf)
		button1?.setOnClickListener { finish() }
	}
}