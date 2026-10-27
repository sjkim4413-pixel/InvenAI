package com.example.myapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.bumptech.glide.Glide

class Inbound : AppCompatActivity() {
	private var currentQuantity: Int = 50
	private var selectedWarehouse: String = "제1물류센터"
	private var selectedZone: String = "B구역"
	private var currentItemName: String = "무선 마우스 M-100"
	private var currentItemSku: String = "SKU-00213"

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_inbound)
		findViewById<View>(R.id.r2z29mgruyhb)?.visibility = View.GONE
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/v7budixr_expires_30_days.png").into(findViewById(R.id.btn_inbound_back))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/8bmodns0_expires_30_days.png").into(findViewById(R.id.rz06v3snjqw))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/f6027umo_expires_30_days.png").into(findViewById(R.id.rz17npri7jm))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/p7etgoqn_expires_30_days.png").into(findViewById(R.id.r9vqbrvdq3ns))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/2g5xjdzd_expires_30_days.png").into(findViewById(R.id.r5fn4vu8h1zv))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/t5a5ea4r_expires_30_days.png").into(findViewById(R.id.r5brvw8zo9fb))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/22vgv3xf_expires_30_days.png").into(findViewById(R.id.r7cgzthr5y5s))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/5guhpod6_expires_30_days.png").into(findViewById(R.id.rk8n6y9k570b))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/d5uup5ix_expires_30_days.png").into(findViewById(R.id.rwd9isb2oxqe))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/d2334nnp_expires_30_days.png").into(findViewById(R.id.r46gl7w0vf2j))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/exmdfbsn_expires_30_days.png").into(findViewById(R.id.rsf672vle6zd))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/nasp7wvc_expires_30_days.png").into(findViewById(R.id.r8gqcrdwn809))

		// Intent로 전달받은 스캔 정보 확인
		val scanName = intent.getStringExtra("SCANNED_ITEM_NAME")
		val scanCode = intent.getStringExtra("SCANNED_ITEM_CODE")
		if (!scanName.isNullOrEmpty()) {
			currentItemName = scanName
			if (!scanCode.isNullOrEmpty()) {
				currentItemSku = scanCode
			}
		}

		// UI 반영
		findViewById<TextView>(R.id.rjjkv3y17qpj)?.text = currentItemName
		findViewById<TextView>(R.id.riyziqnlx3tr)?.text = "$currentItemSku · 완제품"

		// 상단 뒤로가기 버튼
		findViewById<View>(R.id.btn_inbound_back)?.setOnClickListener { finish() }

		// 상단 바코드 스캔 버튼 또는 상품 카드 클릭 시 선택 다이얼로그(스캔 vs 직접입력) 띄우기
		val openSelectionDialog = View.OnClickListener {
			showProductSelectionDialog()
		}
		findViewById<View>(R.id.btnTopBarcodeScan)?.setOnClickListener(openSelectionDialog)
		findViewById<View>(R.id.rx7jsutz28s)?.setOnClickListener(openSelectionDialog)

		// 수량 조절 버튼 (-)
		findViewById<View>(R.id.r6kcaduuock3)?.setOnClickListener {
			if (currentQuantity > 1) {
				currentQuantity--
				findViewById<TextView>(R.id.r7qhv91u4328)?.text = currentQuantity.toString()
			}
		}

		// 수량 조절 버튼 (+)
		findViewById<View>(R.id.r44de9a9hkn)?.setOnClickListener {
			currentQuantity++
			findViewById<TextView>(R.id.r7qhv91u4328)?.text = currentQuantity.toString()
		}

		// 물류센터 선택 드롭다운
		findViewById<View>(R.id.rw7u54nm2y9d)?.setOnClickListener {
			val warehouses = arrayOf("제1물류센터", "제2물류센터", "제3물류센터")
			AlertDialog.Builder(this)
				.setTitle("물류센터 선택")
				.setItems(warehouses) { _, which ->
					selectedWarehouse = warehouses[which]
					findViewById<TextView>(R.id.raelfay8mt9)?.text = selectedWarehouse
					Toast.makeText(this, "$selectedWarehouse 선택됨", Toast.LENGTH_SHORT).show()
				}
				.show()
		}

		// 구역 선택 드롭다운
		findViewById<View>(R.id.r24o4qvkkzyl)?.setOnClickListener {
			val zones = arrayOf("A구역", "B구역", "C구역", "D구역")
			AlertDialog.Builder(this)
				.setTitle("적재 구역 선택")
				.setItems(zones) { _, which ->
					selectedZone = zones[which]
					findViewById<TextView>(R.id.ryrloh5k3lg)?.text = selectedZone
					val etLocation = findViewById<EditText>(R.id.r9d214a21z9)
					etLocation?.setText("$selectedZone R05-3층")
					Toast.makeText(this, "$selectedZone 선택됨", Toast.LENGTH_SHORT).show()
				}
				.show()
		}

		// 입고 완료 버튼
		findViewById<View>(R.id.rzf39icr5vo)?.setOnClickListener {
			performInbound()
		}

		// 하단 네비게이션 바
		findViewById<View>(R.id.rk8n6y9k570b)?.setOnClickListener {
			val intent = Intent(this, HomeDashboard::class.java)
			intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
			startActivity(intent)
			finish()
		}
		findViewById<View>(R.id.r14gqtmenhf1e)?.setOnClickListener {
			startActivity(Intent(this, InventoryList::class.java))
		}
		val openScanner = View.OnClickListener {
			val scanIntent = Intent(this, BarcodeScanner::class.java).apply {
				putExtra("TARGET_SCREEN", "INBOUND")
			}
			startActivity(scanIntent)
		}
		findViewById<View>(R.id.r8gqcrdwn809)?.setOnClickListener(openScanner)
		findViewById<View>(R.id.rgomt01hrsm)?.setOnClickListener(openScanner)
		findViewById<View>(R.id.r0raiyfo8vnl)?.setOnClickListener {
			startActivity(Intent(this, WorkHistory::class.java))
		}
		findViewById<View>(R.id.rovr3324r3q)?.setOnClickListener {
			MenuHelper.showMoreMenu(this)
		}
	}

	private fun showProductSelectionDialog() {
		val options = arrayOf("📷 카메라 바코드 스캔하기", "✍️ 직접 상품 정보 입력하기")
		AlertDialog.Builder(this)
			.setTitle("상품 등록 방식 선택")
			.setItems(options) { _, which ->
				if (which == 0) {
					// 바코드 스캔 화면으로 이동
					val scanIntent = Intent(this, BarcodeScanner::class.java).apply {
						putExtra("TARGET_SCREEN", "INBOUND")
					}
					startActivity(scanIntent)
				} else {
					// 직접 입력 다이얼로그 오픈
					showProductInputDialog()
				}
			}
			.show()
	}

	private fun showProductInputDialog() {
		val layout = android.widget.LinearLayout(this).apply {
			orientation = android.widget.LinearLayout.VERTICAL
			setPadding(50, 40, 50, 10)
		}

		val inputName = EditText(this).apply {
			hint = "상품명 (예: 무선 키보드 K-90)"
			setText(currentItemName)
			inputType = android.text.InputType.TYPE_CLASS_TEXT
		}
		val inputSku = EditText(this).apply {
			hint = "SKU / 바코드 번호 (예: SKU-99821)"
			setText(currentItemSku)
			inputType = android.text.InputType.TYPE_CLASS_TEXT
		}

		layout.addView(inputName)
		layout.addView(inputSku)

		AlertDialog.Builder(this)
			.setTitle("직접 상품 정보 입력")
			.setView(layout)
			.setPositiveButton("확인") { _, _ ->
				val name = inputName.text.toString().trim()
				val sku = inputSku.text.toString().trim()
				if (name.isNotEmpty()) {
					currentItemName = name
					currentItemSku = if (sku.isNotEmpty()) sku else "SKU-" + (10000..99999).random()
					findViewById<TextView>(R.id.rjjkv3y17qpj)?.text = currentItemName
					findViewById<TextView>(R.id.riyziqnlx3tr)?.text = "$currentItemSku · 완제품"
					Toast.makeText(this, "상품 정보가 업데이트되었습니다.", Toast.LENGTH_SHORT).show()
				}
			}
			.setNegativeButton("취소", null)
			.show()
	}

	private fun performInbound() {
		val locationText = findViewById<EditText>(R.id.r9d214a21z9)?.text?.toString()?.trim() ?: "$selectedZone R05-3층"
		val newItem = InventoryItem(
			itemCode = currentItemSku,
			itemName = currentItemName,
			quantity = currentQuantity,
			location = locationText.ifEmpty { "$selectedZone R05-3층" },
			status = "NORMAL"
		)
		InventoryManager.addOrUpdateInventoryItem(newItem, {
			Toast.makeText(this, "입고 완료: [$currentItemName] $currentQuantity 개가 $locationText 에 등록되었습니다.", Toast.LENGTH_SHORT).show()
			finish()
		}, { err ->
			Toast.makeText(this, "입고 등록 실패: $err", Toast.LENGTH_SHORT).show()
		})
	}
}
