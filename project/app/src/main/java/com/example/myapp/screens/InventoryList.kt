package com.example.myapp

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.google.firebase.firestore.ListenerRegistration

class InventoryList : AppCompatActivity() {

	private var inventoryListener: ListenerRegistration? = null
	private var currentItems: List<InventoryItem> = emptyList()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_inventory_list)

		// 상단 이미지/아이콘 로딩
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/4jibd1jn_expires_30_days.png").into(findViewById(R.id.rmkfwrpy51p))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/ke7kqco6_expires_30_days.png").into(findViewById(R.id.rp3vtwhd9m2q))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/w0fng0rw_expires_30_days.png").into(findViewById(R.id.rk3okepp5ox))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/ct5z84en_expires_30_days.png").into(findViewById(R.id.rxq6bb0gaxh))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/bnrv32vk_expires_30_days.png").into(findViewById(R.id.rhk837un2nsl))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/mchitgku_expires_30_days.png").into(findViewById(R.id.rt87e4hkgdz))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/hzpl9gub_expires_30_days.png").into(findViewById(R.id.rbuk0ip58a9q))

		// 검색어 입력 Listener
		val etSearch: EditText? = findViewById(R.id.rzgh3zmtlhx)
		etSearch?.addTextChangedListener(object : TextWatcher {
			override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
			override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
				filterInventory(s.toString().trim())
			}
			override fun afterTextChanged(s: Editable?) {}
		})

		// 상단 뒤로가기 버튼
		findViewById<View>(R.id.btn_inventory_list_back)?.setOnClickListener { finish() }
		findViewById<View>(R.id.rp3vtwhd9m2q)?.setOnClickListener { finish() }

		// 하단 네비게이션 바
		findViewById<View>(R.id.rk3okepp5ox)?.setOnClickListener {
			val intent = Intent(this, HomeDashboard::class.java)
			intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
			startActivity(intent)
			finish()
		}

		val openScanner = View.OnClickListener {
			startActivity(Intent(this, BarcodeScanner::class.java))
		}
		findViewById<View>(R.id.rbuk0ip58a9q)?.setOnClickListener(openScanner)
		findViewById<View>(R.id.rklc2x3n6g)?.setOnClickListener(openScanner)

		findViewById<View>(R.id.rjqojvr7z0xr)?.setOnClickListener {
			startActivity(Intent(this, WorkHistory::class.java))
		}

		findViewById<View>(R.id.rnw6p4q6bcle)?.setOnClickListener {
			MenuHelper.showMoreMenu(this)
		}

		// Cloud Firestore 실시간 재고 바인딩
		setupRealtimeInventory()
	}

	/**
	 * Firebase Cloud Firestore 실시간 재고 수신 및 동적 카드 바인딩
	 */
	private fun setupRealtimeInventory() {
		inventoryListener = InventoryManager.listenToRealtimeInventory { items ->
			currentItems = items
			renderInventoryList(items)
		}
	}

	/**
	 * 검색어 필터링
	 */
	private fun filterInventory(query: String) {
		if (query.isEmpty()) {
			renderInventoryList(currentItems)
		} else {
			val filtered = currentItems.filter {
				it.itemName.contains(query, ignoreCase = true) ||
				it.itemCode.contains(query, ignoreCase = true) ||
				it.location.contains(query, ignoreCase = true)
			}
			renderInventoryList(filtered)
		}
	}

	/**
	 * 동적으로 Firestore 재고 카드 목록 렌더링
	 */
	private fun renderInventoryList(items: List<InventoryItem>) {
		val container = findViewById<LinearLayout>(R.id.r0dvle802is6) ?: return
		container.removeAllViews() // 기존 고정 하드코딩 카드 삭제

		if (items.isEmpty()) {
			val emptyView = TextView(this).apply {
				text = "등록된 재고 데이터가 없습니다.\n(Firebase 'inventory' 컬렉션을 확인하세요)"
				setTextColor(Color.parseColor("#94A3B8"))
				textSize = 14f
				gravity = Gravity.CENTER
				setPadding(0, dpToPx(40), 0, dpToPx(40))
			}
			container.addView(emptyView)
			return
		}

		for (item in items) {
			val card = createCardView(item)
			container.addView(card)
		}
	}

	/**
	 * 단일 재고 항목 카드 뷰 동적 생성
	 */
	private fun createCardView(item: InventoryItem): View {
		val card = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
			background = ContextCompat.getDrawable(this@InventoryList, R.drawable.se2e8f0sw1cr16bffffff)
			val params = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT
			)
			params.setMargins(0, 0, 0, dpToPx(10))
			layoutParams = params
		}

		// 1. 헤더 (상품명 + 코드 + 상태)
		val headerLayout = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
		}

		val textLayout = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
		}

		val tvTitle = TextView(this).apply {
			text = item.itemName.ifEmpty { "품목: ${item.itemCode}" }
			setTextColor(Color.parseColor("#0F172A"))
			textSize = 15f
			typeface = Typeface.DEFAULT_BOLD
		}

		val tvCode = TextView(this).apply {
			text = "코드: ${item.itemCode}"
			setTextColor(Color.parseColor("#94A3B8"))
			textSize = 12f
		}

		textLayout.addView(tvTitle)
		textLayout.addView(tvCode)

		val tvStatus = TextView(this).apply {
			val isNormal = item.status.equals("NORMAL", ignoreCase = true) || item.status == "정상"
			text = if (isNormal) "정상" else "이상"
			setTextColor(if (isNormal) Color.parseColor("#10B981") else Color.parseColor("#EF4444"))
			textSize = 11f
			typeface = Typeface.DEFAULT_BOLD
			setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
			background = ContextCompat.getDrawable(
				this@InventoryList,
				if (isNormal) R.drawable.cr6becfdf5 else R.drawable.cr6bfef2f2
			)
		}

		headerLayout.addView(textLayout)
		headerLayout.addView(tvStatus)

		// 구분선
		val divider = View(this).apply {
			val params = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				dpToPx(1)
			)
			params.setMargins(0, dpToPx(12), 0, dpToPx(11))
			layoutParams = params
			setBackgroundColor(Color.parseColor("#E2E8F0"))
		}

		// 2. 바텀 (랙 위치 + 수량)
		val bottomLayout = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
		}

		val tvLocation = TextView(this).apply {
			text = "📍 " + (item.location.ifEmpty { "위치 미지정" })
			setTextColor(Color.parseColor("#475569"))
			textSize = 13f
			typeface = Typeface.DEFAULT_BOLD
			layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
		}

		val tvQty = TextView(this).apply {
			text = "현재고: ${item.quantity}개"
			setTextColor(Color.parseColor("#0F172A"))
			textSize = 15f
			typeface = Typeface.DEFAULT_BOLD
		}

		bottomLayout.addView(tvLocation)
		bottomLayout.addView(tvQty)

		card.addView(headerLayout)
		card.addView(divider)
		card.addView(bottomLayout)

		// 상세 페이지 이동
		card.setOnClickListener {
			val intent = Intent(this, InventoryDetail::class.java).apply {
				putExtra("ITEM_CODE", item.itemCode)
				putExtra("ITEM_NAME", item.itemName)
				putExtra("LOCATION", item.location)
				putExtra("QUANTITY", item.quantity)
				putExtra("STATUS", item.status)
			}
			startActivity(intent)
		}

		return card
	}

	private fun dpToPx(dp: Int): Int {
		return (dp * resources.displayMetrics.density).toInt()
	}

	override fun onDestroy() {
		super.onDestroy()
		inventoryListener?.remove()
	}
}