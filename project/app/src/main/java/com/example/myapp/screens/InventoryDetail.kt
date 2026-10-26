package com.example.myapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.firebase.firestore.ListenerRegistration
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class InventoryDetail : AppCompatActivity() {

	private var itemCode: String = "M-100"
	private var itemName: String = "무선 마우스 M-100"
	private var location: String = "B구역 R05-3층"
	private var quantity: Int = 120
	private var status: String = "NORMAL"
	private var updatedAtTimestamp: Long = System.currentTimeMillis() - (1000L * 60 * 60 * 2)

	private var inventoryListener: ListenerRegistration? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_inventory_detail)

		// Intent Extra 수신
		itemCode = intent.getStringExtra("ITEM_CODE") ?: "M-100"
		itemName = intent.getStringExtra("ITEM_NAME") ?: "무선 마우스 M-100"
		location = intent.getStringExtra("LOCATION") ?: "B구역 R05-3층"
		quantity = intent.getIntExtra("QUANTITY", 120)
		status = intent.getStringExtra("STATUS") ?: "NORMAL"
		updatedAtTimestamp = intent.getLongExtra("UPDATED_AT", System.currentTimeMillis() - (1000L * 60 * 60 * 2))

		// 이미지 바인딩
		findViewById<View>(R.id.ruk2l6hsjuh)?.visibility = View.GONE
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/6dzl2y2j_expires_30_days.png").into(findViewById(R.id.rlb7iitjpcch))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/bb3mu2es_expires_30_days.png").into(findViewById(R.id.r1vk1k6pain8j))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/6em8cmgw_expires_30_days.png").into(findViewById(R.id.rrbtmji7ayxp))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/wdnvw2ut_expires_30_days.png").into(findViewById(R.id.r74nucvcdsxa))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/23nnyjuu_expires_30_days.png").into(findViewById(R.id.rdnv3fthexra))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/mqzyo825_expires_30_days.png").into(findViewById(R.id.rel7zzvm5tt9))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/q9x50ewe_expires_30_days.png").into(findViewById(R.id.rfr3dgqydvgt))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/2w56ktx0_expires_30_days.png").into(findViewById(R.id.rjou5xvfm1e))

		// 1. 전달받은 실재 데이터 UI 바인딩
		bindInventoryData()

		// 2. 상단 뒤로가기 버튼
		findViewById<View>(R.id.rlb7iitjpcch)?.setOnClickListener { finish() }

		// 3. [재고 이동] 버튼 -> 랙 위치 변경 다이얼로그
		val btnMove: View? = findViewById(R.id.rl1mtyqbucc)
		btnMove?.setOnClickListener {
			val currentItem = InventoryItem(itemCode, itemName, quantity, location, status)
			EditInventoryDialog.show(this, currentItem) {
				Toast.makeText(this, "재고 이동 요청이 처리되었습니다.", Toast.LENGTH_SHORT).show()
			}
		}

		// 4. [수량 수정] 버튼 -> 수량/위치 변경 다이얼로그
		val btnEditQty: View? = findViewById(R.id.r2ocdj4k8cly)
		btnEditQty?.setOnClickListener {
			val currentItem = InventoryItem(itemCode, itemName, quantity, location, status)
			EditInventoryDialog.show(this, currentItem) {
				Toast.makeText(this, "수치 수정 요청이 처리되었습니다.", Toast.LENGTH_SHORT).show()
			}
		}

		// 5. 하단 네비게이션 바
		findViewById<View>(R.id.r74nucvcdsxa)?.setOnClickListener {
			val intent = Intent(this, HomeDashboard::class.java)
			intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
			startActivity(intent)
			finish()
		}

		findViewById<View>(R.id.rdnv3fthexra)?.setOnClickListener {
			startActivity(Intent(this, InventoryList::class.java))
			finish()
		}

		val openScanner = View.OnClickListener {
			startActivity(Intent(this, BarcodeScanner::class.java))
		}
		findViewById<View>(R.id.rjou5xvfm1e)?.setOnClickListener(openScanner)
		findViewById<View>(R.id.rc16atwgjkqv)?.setOnClickListener(openScanner)

		findViewById<View>(R.id.rel7zzvm5tt9)?.setOnClickListener {
			startActivity(Intent(this, WorkHistory::class.java))
		}

		findViewById<View>(R.id.rfr3dgqydvgt)?.setOnClickListener {
			MenuHelper.showMoreMenu(this)
		}
	}

	override fun onResume() {
		super.onResume()
		// Firestore 실시간 리스너 수신 ➔ 수량 및 위치 변경 시 실시간 UI 갱신
		inventoryListener?.remove()
		inventoryListener = InventoryManager.listenToRealtimeInventory { items ->
			val targetItem = items.find { it.itemCode == itemCode }
			if (targetItem != null) {
				itemName = targetItem.itemName
				location = targetItem.location
				quantity = targetItem.quantity
				status = targetItem.status
				if (targetItem.updatedAtTimestamp > 0) {
					updatedAtTimestamp = targetItem.updatedAtTimestamp
				}
				bindInventoryData()
			}
		}
	}

	override fun onPause() {
		super.onPause()
		inventoryListener?.remove()
	}

	/**
	 * 시스템 등록 메타데이터 및 헤더 수치 실시간 동적 바인딩
	 */
	private fun bindInventoryData() {
		val formatter = DecimalFormat("#,###")

		// 헤더 영역
		findViewById<TextView>(R.id.ryvd7x2osq1)?.text = itemName
		findViewById<TextView>(R.id.rdqwy34t5qvs)?.text = "품목코드: $itemCode  ·  위치: $location"
		findViewById<TextView>(R.id.rn47d4e68uol)?.text = if (status.equals("NORMAL", ignoreCase = true) || status == "정상") "정상 상태" else "이상 감지"

		// 상단 '최근 업데이트' 문구 동적 바인딩
		findViewById<TextView>(R.id.rps4ujt3tdm9)?.text = "최근 업데이트: ${formatRelativeTime(updatedAtTimestamp)}"

		// 시스템 등록 메타데이터 영역 (수량, 위치, 입고일, 이동일시 동적 반영)
		findViewById<TextView>(R.id.ris3q6gbf1sb)?.text = "${formatter.format(quantity)} 개" // 현재 수량
		findViewById<TextView>(R.id.rdsxkthcwsfs)?.text = location                          // 구역 구분 / 위치
		findViewById<TextView>(R.id.r1ck1uax9v6g)?.text = "랙: $location"                   // 랙 번호
		findViewById<TextView>(R.id.rcpf8zg0hcvg)?.text = "PALLET-$itemCode"                 // 팔레트 ID
		findViewById<TextView>(R.id.r70y6844hxkc)?.text = formatDateOnly(updatedAtTimestamp - (1000L * 60 * 60 * 24 * 14)) // 최초 입고일
		findViewById<TextView>(R.id.ruws7gcxkfxe)?.text = formatFullDateTime(updatedAtTimestamp) // 최근 이동일시
	}

	private fun formatRelativeTime(timestamp: Long): String {
		val now = System.currentTimeMillis()
		val diff = now - timestamp

		if (diff <= 0) return "방금 전"

		val seconds = diff / 1000
		val minutes = seconds / 60
		val hours = minutes / 60
		val days = hours / 24
		val months = days / 30

		return when {
			minutes < 1 -> "방금 전"
			minutes < 60 -> "${minutes}분 전"
			hours < 24 -> "${hours}시간 전"
			days < 30 -> "${days}일 전"
			months < 12 -> "${months}달 전"
			else -> "${months / 12}년 전"
		}
	}

	private fun formatDateOnly(timestamp: Long): String {
		val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
		return sdf.format(Date(timestamp))
	}

	private fun formatFullDateTime(timestamp: Long): String {
		val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)
		return sdf.format(Date(timestamp))
	}
}