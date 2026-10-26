package com.example.myapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.bumptech.glide.Glide

class Outbound : AppCompatActivity() {
	private var isInspected: Boolean = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_outbound)
		findViewById<View>(R.id.ry7xnxvpctk)?.visibility = View.GONE
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

		// 품목 카드 클릭 시 검수 완료 토글
		val inspectionCard = findViewById<View>(R.id.r5igq6j590t8)
		inspectionCard?.setOnClickListener {
			isInspected = !isInspected
			val progressText = findViewById<TextView>(R.id.r7xhv0u114x)
			if (isInspected) {
				progressText?.text = "3 / 3 완료 (100%)"
				Toast.makeText(this, "모든 출고 품목 검수가 완료되었습니다.", Toast.LENGTH_SHORT).show()
			} else {
				progressText?.text = "1 / 3 완료 (33%)"
				Toast.makeText(this, "검수가 해제되었습니다.", Toast.LENGTH_SHORT).show()
			}
		}

		// 하단 네비게이션 바
		findViewById<View>(R.id.rupecyf80hng)?.setOnClickListener {
			val intent = Intent(this, HomeDashboard::class.java)
			intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
			startActivity(intent)
			finish()
		}
		findViewById<View>(R.id.rjw1ckivpyn)?.setOnClickListener {
			startActivity(Intent(this, InventoryList::class.java))
		}
		val openScanner = View.OnClickListener {
			startActivity(Intent(this, BarcodeScanner::class.java))
		}
		findViewById<View>(R.id.rh36zxy9qo29)?.setOnClickListener(openScanner)
		findViewById<View>(R.id.rql1c1xivai)?.setOnClickListener(openScanner)
		findViewById<View>(R.id.r54ce1bp910u)?.setOnClickListener {
			startActivity(Intent(this, WorkHistory::class.java))
		}
		findViewById<View>(R.id.rd76lyutvd04)?.setOnClickListener {
			MenuHelper.showMoreMenu(this)
		}

		// 출고 완료 처리 버튼
		val button1: View? = findViewById(R.id.rgctjrpsbkzw)
		button1?.setOnClickListener {
			val outboundItem = InventoryItem(
				itemCode = "M-100",
				itemName = "무선 마우스 M-100",
				quantity = 0,
				location = "A구역 R01-1층",
				status = "OUTBOUND_COMPLETE"
			)
			InventoryManager.addOrUpdateInventoryItem(outboundItem, {
				Toast.makeText(this, "출고 지시 검수 및 출고 완료 처리가 완료되었습니다.", Toast.LENGTH_SHORT).show()
				finish()
			}, { err ->
				Toast.makeText(this, "출고 처리 실패: $err", Toast.LENGTH_SHORT).show()
			})
		}
	}
}
