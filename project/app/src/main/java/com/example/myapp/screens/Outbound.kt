package com.example.myapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import com.bumptech.glide.Glide

class Outbound : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_outbound)
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/21vxgrwm_expires_30_days.png").into(findViewById(R.id.ry7xnxvpctk))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/ylzyhmr5_expires_30_days.png").into(findViewById(R.id.rep2pggs7s9))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/uf2i3qat_expires_30_days.png").into(findViewById(R.id.r1v53u96tfpeh))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/928s3ddo_expires_30_days.png").into(findViewById(R.id.r6hug2nahgfo))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/zduxlzsl_expires_30_days.png").into(findViewById(R.id.r5lof4r5ar9x))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/gmf49ujy_expires_30_days.png").into(findViewById(R.id.rd65dghpoomg))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/kyue1ixv_expires_30_days.png").into(findViewById(R.id.rupecyf80hng))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/hh47k4vd_expires_30_days.png").into(findViewById(R.id.rekv9uq0l63))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/m0mp5or3_expires_30_days.png").into(findViewById(R.id.rzc8j53k4txk))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/9efpj0fw_expires_30_days.png").into(findViewById(R.id.r5uucu5qjq35))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/coe3bpvz_expires_30_days.png").into(findViewById(R.id.rh36zxy9qo29))

		// 상단 뒤로가기 버튼
		findViewById<View>(R.id.rep2pggs7s9)?.setOnClickListener { finish() }

		// 하단 네비게이션 바
		// 1. 홈
		findViewById<View>(R.id.rupecyf80hng)?.setOnClickListener {
			val intent = Intent(this, HomeDashboard::class.java)
			intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
			startActivity(intent)
			finish()
		}
		// 2. 재고
		findViewById<View>(R.id.rjw1ckivpyn)?.setOnClickListener {
			startActivity(Intent(this, InventoryList::class.java))
		}
		// 3. 스캔
		val openScanner = View.OnClickListener {
			startActivity(Intent(this, BarcodeScanner::class.java))
		}
		findViewById<View>(R.id.rh36zxy9qo29)?.setOnClickListener(openScanner)
		findViewById<View>(R.id.rql1c1xivai)?.setOnClickListener(openScanner)

		// 4. 작업
		findViewById<View>(R.id.r54ce1bp910u)?.setOnClickListener {
			startActivity(Intent(this, WorkHistory::class.java))
		}
		// 5. 더보기 (드롭바/바텀시트 메뉴)
		findViewById<View>(R.id.rd76lyutvd04)?.setOnClickListener {
			MenuHelper.showMoreMenu(this)
		}

		val button1: View? = findViewById(R.id.rgctjrpsbkzw)
		button1?.setOnClickListener { finish() }
	}
}