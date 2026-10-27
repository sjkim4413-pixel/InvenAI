package com.example.myapp

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.bumptech.glide.Glide

class Transfer : AppCompatActivity() {
	private var selectedWarehouse: String = "제1물류센터"
	private var selectedZone: String = "B구역"
	private var selectedRack: String = "R07"
	private var selectedFloor: String = "1층"

	private var currentInventoryList: List<InventoryItem> = emptyList()
	private var selectedItem: InventoryItem? = null

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

		// 1. 실시간 재고 목록 로드
		InventoryManager.listenToRealtimeInventory(this) { items ->
			currentInventoryList = items
			if (selectedItem == null && items.isNotEmpty()) {
				selectedItem = items[0]
				updateSelectedItemUI()
			}
		}

		// 2. 이동 대상 재고 카드 클릭 시 아이템 선택 다이얼로그 오픈
		findViewById<View>(R.id.reb6aq54vfy4)?.setOnClickListener {
			showItemSelectionDialog()
		}

		// 목적지 드롭다운 선택 리스너
		findViewById<View>(R.id.rw6isfw72vt)?.setOnClickListener {
			val warehouses = arrayOf("제1물류센터", "제2물류센터", "제3물류센터")
			AlertDialog.Builder(this)
				.setTitle("물류센터 선택")
				.setItems(warehouses) { _, which ->
					selectedWarehouse = warehouses[which]
					findViewById<TextView>(R.id.r4pmca5blzyx)?.text = selectedWarehouse
					Toast.makeText(this, "$selectedWarehouse 선택됨", Toast.LENGTH_SHORT).show()
				}
				.show()
		}

		findViewById<View>(R.id.rpx0bu1mfhtn)?.setOnClickListener {
			val zones = arrayOf("A구역", "B구역", "C구역", "D구역")
			AlertDialog.Builder(this)
				.setTitle("적재 구역 선택")
				.setItems(zones) { _, which ->
					selectedZone = zones[which]
					findViewById<TextView>(R.id.ryhxv61kkw0d)?.text = selectedZone
					Toast.makeText(this, "$selectedZone 선택됨", Toast.LENGTH_SHORT).show()
				}
				.show()
		}

		findViewById<View>(R.id.rdfwk2hqzv8d)?.setOnClickListener {
			val racks = arrayOf("R01", "R02", "R03", "R04", "R05", "R06", "R07", "R08")
			AlertDialog.Builder(this)
				.setTitle("적재 랙(Rack) 선택")
				.setItems(racks) { _, which ->
					selectedRack = "${racks[which]} (적재랙)"
					findViewById<TextView>(R.id.r0mvfcpk2ick)?.text = selectedRack
					Toast.makeText(this, "$selectedRack 선택됨", Toast.LENGTH_SHORT).show()
				}
				.show()
		}

		findViewById<View>(R.id.rsb02yfu7so)?.setOnClickListener {
			val floors = arrayOf("1층", "2층", "3층", "4층")
			AlertDialog.Builder(this)
				.setTitle("단(층) 선택")
				.setItems(floors) { _, which ->
					selectedFloor = floors[which]
					findViewById<TextView>(R.id.r69xqcawulj6)?.text = selectedFloor
					Toast.makeText(this, "$selectedFloor 선택됨", Toast.LENGTH_SHORT).show()
				}
				.show()
		}

		// 이동 완료 버튼 (DB 반영 - 재고 위치 이동은 검수자(QA) 승인이 필요한 이동 상태(PENDING_QA)로 기록)
		val btnComplete: View? = findViewById(R.id.rzlwjjeiinlf)
		btnComplete?.setOnClickListener {
			val itemToMove = selectedItem
			if (itemToMove == null) {
				Toast.makeText(this, "이동할 재고 품목을 선택해주세요.", Toast.LENGTH_SHORT).show()
				return@setOnClickListener
			}

			val newLocation = "$selectedZone $selectedRack-$selectedFloor ($selectedWarehouse)"
			val updatedItem = itemToMove.copy(
				status = "PENDING_QA", // 재고 위치 이동 시 검수 대기(승인 필요) 상태로 설정
				pendingLocation = newLocation,
				pendingQuantity = itemToMove.quantity,
				updatedAtTimestamp = System.currentTimeMillis()
			)

			InventoryManager.addOrUpdateInventoryItem(this, updatedItem, {
				Toast.makeText(this, "[${itemToMove.itemName}] 재고 위치 이동 요청이 완료되어 검수자(QA) 승인 대기 상태로 반영되었습니다.", Toast.LENGTH_LONG).show()
				finish()
			}, { err ->
				Toast.makeText(this, "위치 이동 반영 실패: $err", Toast.LENGTH_SHORT).show()
			})
		}

		// 하단 네비게이션 바
		findViewById<View>(R.id.rnvsyv3rx45j)?.setOnClickListener {
			val intent = Intent(this, HomeDashboard::class.java)
			intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
			startActivity(intent)
			finish()
		}
		findViewById<View>(R.id.rdch5ka4s5sm)?.setOnClickListener {
			startActivity(Intent(this, InventoryList::class.java))
		}
		val openScanner = View.OnClickListener {
			startActivity(Intent(this, BarcodeScanner::class.java))
		}
		findViewById<View>(R.id.rqf9sozej26)?.setOnClickListener(openScanner)
		findViewById<View>(R.id.rgu92wvhmd6n)?.setOnClickListener(openScanner)
		findViewById<View>(R.id.ra9hy0d7k3c)?.setOnClickListener {
			startActivity(Intent(this, WorkHistory::class.java))
		}
		findViewById<View>(R.id.rcvmn8zbd1hg)?.setOnClickListener {
			MenuHelper.showMoreMenu(this)
		}
	}

	private fun showItemSelectionDialog() {
		if (currentInventoryList.isEmpty()) {
			Toast.makeText(this, "선택 가능한 재고 데이터가 없습니다.", Toast.LENGTH_SHORT).show()
			return
		}

		val context = this
		val listView = ListView(context).apply {
			divider = ColorDrawable(Color.parseColor("#E2E8F0"))
			dividerHeight = 2
			setPadding(20, 16, 20, 16)
		}

		val adapter = object : ArrayAdapter<InventoryItem>(
			context,
			0,
			currentInventoryList
		) {
			override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
				val layout = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
					orientation = LinearLayout.VERTICAL
					setPadding(24, 20, 24, 20)
				}
				layout.removeAllViews()

				val item = getItem(position) ?: return layout

				val text1 = TextView(context).apply {
					text = "${item.itemName}  [${item.itemCode}]"
					setTextColor(Color.parseColor("#0F172A"))
					textSize = 15f
					setTypeface(null, Typeface.BOLD)
				}

				val text2 = TextView(context).apply {
					text = "📍 현재 위치: ${item.location}    ·    보유 수량: ${item.quantity}개"
					setTextColor(Color.parseColor("#475569"))
					textSize = 12f
					setPadding(0, 6, 0, 0)
				}

				layout.addView(text1)
				layout.addView(text2)

				return layout
			}
		}

		listView.adapter = adapter

		val dialog = AlertDialog.Builder(context)
			.setTitle("이동 대상 재고 선택")
			.setView(listView)
			.setNegativeButton("취소", null)
			.create()

		listView.setOnItemClickListener { _, _, position, _ ->
			selectedItem = currentInventoryList[position]
			updateSelectedItemUI()
			Toast.makeText(context, "[${selectedItem?.itemName}] 선택됨", Toast.LENGTH_SHORT).show()
			dialog.dismiss()
		}

		dialog.show()
	}

	private fun updateSelectedItemUI() {
		val item = selectedItem ?: return
		findViewById<TextView>(R.id.ryru1njp5h3g)?.text = item.itemName
		findViewById<TextView>(R.id.r2er48yiief)?.text = "${item.itemCode} · 재고수량: ${item.quantity}개"
		findViewById<TextView>(R.id.rr696f8ly9t)?.text = "현재 위치: ${item.location}"
		findViewById<TextView>(R.id.rolcdvwg5zbr)?.text = "보유고: ${item.quantity}개"
	}
}
