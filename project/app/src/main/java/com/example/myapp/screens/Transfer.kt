package com.example.myapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import com.bumptech.glide.Glide

class Transfer : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_transfer)
		findViewById<View>(R.id.ryevfqtfn7dn)?.visibility = View.GONE
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/v7budixr_expires_30_days.png").into(findViewById(R.id.rf757l1z7cwk))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/hd3p86tr_expires_30_days.png").into(findViewById(R.id.ro46eqkipxtc))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/uxav5byw_expires_30_days.png").into(findViewById(R.id.r2nps7iriax2))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/65el9ju0_expires_30_days.png").into(findViewById(R.id.re2pz8xawj9t))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/jf7jtl8y_expires_30_days.png").into(findViewById(R.id.rv2m67iutx2))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/xyiyo96t_expires_30_days.png").into(findViewById(R.id.rqmuq2s4oc4))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/s7t7ldsl_expires_30_days.png").into(findViewById(R.id.rub4inea75tj))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/0zfq9zyj_expires_30_days.png").into(findViewById(R.id.rk5wemuswrz))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/m5suwjsc_expires_30_days.png").into(findViewById(R.id.r9goapcwcgtt))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/5mwdaydu_expires_30_days.png").into(findViewById(R.id.rnvsyv3rx45j))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/ykxmfafw_expires_30_days.png").into(findViewById(R.id.r6l0beuxvtey))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/ga7axn9c_expires_30_days.png").into(findViewById(R.id.r5jxxhau3rdn))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/d8qpsi3d_expires_30_days.png").into(findViewById(R.id.r8hm31vxe5vf))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/rchifkvx_expires_30_days.png").into(findViewById(R.id.rqf9sozej26))

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