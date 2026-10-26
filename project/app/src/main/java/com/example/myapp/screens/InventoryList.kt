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
	private var selectedTagFilter: String = "ALL" // "ALL", "A_ZONE", "B_ZONE", "NORMAL"
	private var currentSearchQuery: String = ""

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_inventory_list)

		// 상단 이미지/아이콘 로딩
		findViewById<View>(R.id.rmkfwrpy51p)?.visibility = View.GONE
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/ke7kqco6_expires_30_days.png").into(findViewById(R.id.rp3vtwhd9m2q))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/w0fng0rw_expires_30_days.png").into(findViewById(R.id.rk3okepp5ox))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/ct5z84en_expires_30_days.png").into(findViewById(R.id.rxq6bb0gaxh))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/bnrv32vk_expires_30_days.png").into(findViewById(R.id.rhk837un2nsl))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/mchitgku_expires_30_days.png").into(findViewById(R.id.rt87e4hkgdz))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/hzpl9gub_expires_30_days.png").into(findViewById(R.id.rbuk0ip58a9q))

		// 검색어 입력 Listener (한글 및 모바일 키보드 호환)
		val etSearch: EditText? = findViewById(R.id.rzgh3zmtlhx)
		etSearch?.addTextChangedListener(object : TextWatcher {
			override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
			override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
				currentSearchQuery = s.toString().trim()
				applyFilterAndSearch()
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

		// 검색창 아래 필터 태그 칩 기능 구현
		setupTagChipListeners()

		// Cloud Firestore 실시간 재고 바인딩
		setupRealtimeInventory()
	}

	/**
	 * 필터 태그 칩 클릭 이벤트 구현 ([전체 창고], [A구역], [B구역], [정상])
	 */
	private fun setupTagChipListeners() {
		val btnTagAll: View? = findViewById(R.id.rcsp34d582jh)
		val btnTagA: View? = findViewById(R.id.ra0ithaorus4)
		val btnTagB: View? = findViewById(R.id.revxqonyr1am)
		val btnTagNormal: View? = findViewById(R.id.r7osm9qpd5he)

		btnTagAll?.setOnClickListener {
			selectedTagFilter = "ALL"
			updateTagChipStyles(R.id.rcsp34d582jh)
			applyFilterAndSearch()
		}

		btnTagA?.setOnClickListener {
			selectedTagFilter = "A_ZONE"
			updateTagChipStyles(R.id.ra0ithaorus4)
			applyFilterAndSearch()
		}

		btnTagB?.setOnClickListener {
			selectedTagFilter = "B_ZONE"
			updateTagChipStyles(R.id.revxqonyr1am)
			applyFilterAndSearch()
		}

		btnTagNormal?.setOnClickListener {
			selectedTagFilter = "NORMAL"
			updateTagChipStyles(R.id.r7osm9qpd5he)
			applyFilterAndSearch()
		}
	}

	/**
	 * 선택된 태그 칩 스타일 하이라이트 변경
	 */
	private fun updateTagChipStyles(selectedId: Int) {
		val btnTagAll: View? = findViewById(R.id.rcsp34d582jh)
		val btnTagA: View? = findViewById(R.id.ra0ithaorus4)
		val btnTagB: View? = findViewById(R.id.revxqonyr1am)
		val btnTagNormal: View? = findViewById(R.id.r7osm9qpd5he)

		val tvAll: TextView? = findViewById(R.id.rmzk6ugrumd)
		val tvA: TextView? = findViewById(R.id.rd3p87bdx95a)
		val tvB: TextView? = findViewById(R.id.r0oro1j125toe)
		val tvNormal: TextView? = findViewById(R.id.rpxlzzrxv1al)

		// 초기화
		btnTagAll?.background = ContextCompat.getDrawable(this, R.drawable.se2e8f0sw1cr100bf8fafc)
		btnTagA?.background = ContextCompat.getDrawable(this, R.drawable.se2e8f0sw1cr100bf8fafc)
		btnTagB?.background = ContextCompat.getDrawable(this, R.drawable.se2e8f0sw1cr100bf8fafc)
		btnTagNormal?.background = ContextCompat.getDrawable(this, R.drawable.se2e8f0sw1cr100bf8fafc)

		tvAll?.setTextColor(Color.parseColor("#475569"))
		tvA?.setTextColor(Color.parseColor("#475569"))
		tvB?.setTextColor(Color.parseColor("#475569"))
		tvNormal?.setTextColor(Color.parseColor("#475569"))

		// 선택된 태그 하이라이트 (Teal background, White text)
		when (selectedId) {
			R.id.rcsp34d582jh -> {
				btnTagAll?.background = ContextCompat.getDrawable(this, R.drawable.cr100b0d9488)
				tvAll?.setTextColor(Color.WHITE)
			}
			R.id.ra0ithaorus4 -> {
				btnTagA?.background = ContextCompat.getDrawable(this, R.drawable.cr100b0d9488)
				tvA?.setTextColor(Color.WHITE)
			}
			R.id.revxqonyr1am -> {
				btnTagB?.background = ContextCompat.getDrawable(this, R.drawable.cr100b0d9488)
				tvB?.setTextColor(Color.WHITE)
			}
			R.id.r7osm9qpd5he -> {
				btnTagNormal?.background = ContextCompat.getDrawable(this, R.drawable.cr100b0d9488)
				tvNormal?.setTextColor(Color.WHITE)
			}
		}
	}

	/**
	 * 태그 필터와 검색어를 조합하여 최종 결과 필터링
	 */
	private fun applyFilterAndSearch() {
		var filtered = currentItems

		// 1. 태그 필터 적용
		filtered = when (selectedTagFilter) {
			"A_ZONE" -> filtered.filter { it.location.contains("A구역", ignoreCase = true) || it.location.contains("A-", ignoreCase = true) || it.location.contains("A", ignoreCase = true) }
			"B_ZONE" -> filtered.filter { it.location.contains("B구역", ignoreCase = true) || it.location.contains("B-", ignoreCase = true) || it.location.contains("B", ignoreCase = true) }
			"NORMAL" -> filtered.filter { it.status.equals("NORMAL", ignoreCase = true) || it.status == "정상" }
			else -> filtered
		}

		// 2. 검색어 필터 적용
		if (currentSearchQuery.isNotEmpty()) {
			filtered = filtered.filter {
				it.itemName.contains(currentSearchQuery, ignoreCase = true) ||
				it.itemCode.contains(currentSearchQuery, ignoreCase = true) ||
				it.location.contains(currentSearchQuery, ignoreCase = true)
			}
		}

		renderInventoryList(filtered)
	}

	/**
	 * Firebase Cloud Firestore 실시간 재고 수신
	 */
	private fun setupRealtimeInventory() {
		inventoryListener = InventoryManager.listenToRealtimeInventory(this) { items ->
			currentItems = items
			applyFilterAndSearch()
		}
	}

	/**
	 * 동적으로 Firestore 재고 카드 목록 렌더링
	 */
	private fun renderInventoryList(items: List<InventoryItem>) {
		val container = findViewById<LinearLayout>(R.id.r0dvle802is6) ?: return
		container.removeAllViews()

		if (items.isEmpty()) {
			val emptyView = TextView(this).apply {
				text = "조건에 해당하는 재고 데이터가 없습니다."
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
				putExtra("UPDATED_AT", item.updatedAtTimestamp)
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