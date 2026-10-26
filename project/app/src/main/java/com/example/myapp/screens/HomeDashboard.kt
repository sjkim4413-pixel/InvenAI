package com.example.myapp

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.google.firebase.firestore.ListenerRegistration
import java.text.DecimalFormat

class HomeDashboard : AppCompatActivity() {

	private var inventoryListener: ListenerRegistration? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_home_dashboard)

		// 이미지 로딩
		findViewById<View>(R.id.ry7p7pqvxka)?.visibility = View.GONE
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/j2ueo07z_expires_30_days.png").into(findViewById(R.id.rl6h0q4vuf7r))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/ghnv4git_expires_30_days.png").into(findViewById(R.id.rvivb4u3goq))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/8bv0wi1p_expires_30_days.png").into(findViewById(R.id.rgp6rugrpw18))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/qhg90uym_expires_30_days.png").into(findViewById(R.id.rj5jh5v0bjop))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/geoetad2_expires_30_days.png").into(findViewById(R.id.rab39btppni9))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/pkzd3d2y_expires_30_days.png").into(findViewById(R.id.r0s0w7txmc16))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/d6kmecjr_expires_30_days.png").into(findViewById(R.id.rpwoat3ganbb))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/kw244s3t_expires_30_days.png").into(findViewById(R.id.r98ojsxh1t6i))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/79z16sz3_expires_30_days.png").into(findViewById(R.id.re5c8ey5f249))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/11rtvn76_expires_30_days.png").into(findViewById(R.id.r6lzdd9oqzfl))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/oeplcdpb_expires_30_days.png").into(findViewById(R.id.rhm1gom7f5ja))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/awz7tnoe_expires_30_days.png").into(findViewById(R.id.ruqljmdutzc))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/7c6y7et8_expires_30_days.png").into(findViewById(R.id.r8l37me9a9ry))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/rwta62px_expires_30_days.png").into(findViewById(R.id.rmm59y3lfvnq))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/onggqhx0_expires_30_days.png").into(findViewById(R.id.rz27s8jtbjwo))

		// 신속 현장 작업 버튼 연결
		findViewById<View>(R.id.ror0ls8e8rs)?.setOnClickListener {
			startActivity(Intent(this, Inbound::class.java))
		}
		findViewById<View>(R.id.rw3dpgqgpg3r)?.setOnClickListener {
			startActivity(Intent(this, Outbound::class.java))
		}
		findViewById<View>(R.id.r5nn11xsmxdh)?.setOnClickListener {
			startActivity(Intent(this, Transfer::class.java))
		}
		findViewById<View>(R.id.r58m6zkpupi8)?.setOnClickListener {
			startActivity(Intent(this, AuditStart::class.java))
		}
		findViewById<View>(R.id.rcgbksuzw3mr)?.setOnClickListener {
			startActivity(Intent(this, InventoryList::class.java))
		}
		findViewById<View>(R.id.r0da5dco3z0cj)?.setOnClickListener {
			startActivity(Intent(this, AnomalyInventory::class.java))
		}

		// 최근 현장 로그 전체보기
		findViewById<View>(R.id.rabmoab0ddco)?.setOnClickListener {
			startActivity(Intent(this, WorkHistory::class.java))
		}

		// 하단 네비게이션 바
		findViewById<View>(R.id.rhm1gom7f5ja)?.setOnClickListener {
			// 현재 메인 홈 화면
		}
		findViewById<View>(R.id.rs08jcmj2xlm)?.setOnClickListener {
			startActivity(Intent(this, InventoryList::class.java))
		}

		val openScanner = View.OnClickListener {
			startActivity(Intent(this, BarcodeScanner::class.java))
		}
		findViewById<View>(R.id.rz27s8jtbjwo)?.setOnClickListener(openScanner)
		findViewById<View>(R.id.rdkzl3gxfayl)?.setOnClickListener(openScanner)

		findViewById<View>(R.id.rajx13mscno4)?.setOnClickListener {
			startActivity(Intent(this, WorkHistory::class.java))
		}

		findViewById<View>(R.id.r46r7crd2blu)?.setOnClickListener {
			MenuHelper.showMoreMenu(this)
		}
	}

	override fun onResume() {
		super.onResume()
		// Firestore 실시간 재고 요약 집계 연동 시작
		setupRealtimeSummary()
	}

	override fun onPause() {
		super.onPause()
		inventoryListener?.remove()
	}

	/**
	 * Firebase Cloud Firestore 실시간 재고 집계 수신 및 홈 화면 카드 수치 바인딩
	 */
	private fun setupRealtimeSummary() {
		inventoryListener?.remove()

		inventoryListener = InventoryManager.listenToRealtimeInventory { items ->
			var totalQty = 0
			var normalQty = 0
			var anomalyQty = 0

			for (item in items) {
				totalQty += item.quantity
				val isNormal = item.status.equals("NORMAL", ignoreCase = true) || item.status == "정상" || item.status.isEmpty()
				if (isNormal) {
					normalQty += item.quantity
				} else {
					anomalyQty += item.quantity
				}
			}

			val formatter = DecimalFormat("#,###")
			val strTotal = formatter.format(totalQty) + " 개"
			val strNormal = formatter.format(normalQty) + " 개"
			val strAnomaly = formatter.format(anomalyQty) + " 개"

			runOnUiThread {
				// 1. 전체 재고 수량
				findViewById<TextView>(R.id.r8y8i9t5s33h)?.text = strTotal
				// 2. 정상 재고 수량
				findViewById<TextView>(R.id.rhfbnn8zu2se)?.text = strNormal
				// 3. 이상 재고 수량
				findViewById<TextView>(R.id.rs6kkinbc3pk)?.text = strAnomaly

				// 4. 최근 현장 로그 동적 바인딩
				renderRecentLogs(items)
			}
		}
	}

	/**
	 * 최근 현장 로그 카드 동적 바인딩
	 */
	private fun renderRecentLogs(items: List<InventoryItem>) {
		val container = findViewById<LinearLayout>(R.id.llHomeRecentLogsContainer) ?: return
		container.removeAllViews()

		val now = System.currentTimeMillis()

		// 실제 Firestore 재고 항목이 있으면 최신 업데이트 시각(updatedAtTimestamp) 내림차순 정렬
		val sortedItems = if (items.isNotEmpty()) {
			items.sortedByDescending { it.updatedAtTimestamp }
		} else {
			listOf(
				InventoryItem("M-100", "무선 마우스 M-100", 120, "B구역 R05-3층", "NORMAL", updatedAtTimestamp = now - (1000 * 60 * 5)), // 5분 전
				InventoryItem("BT-200", "블루투스 이어폰 BT-200", 50, "A구역 R01-1층 (오적재 감지)", "ANOMALY", updatedAtTimestamp = now - (1000L * 60 * 35)), // 35분 전
				InventoryItem("MC-300", "고속 C타입 멀티 충전기", 80, "A03-3단", "PENDING_QA", updatedAtTimestamp = now - (1000L * 60 * 60 * 3)), // 3시간 전
				InventoryItem("KB-400", "기계식 키보드 K-90", 200, "A04-1단", "NORMAL", updatedAtTimestamp = now - (1000L * 60 * 60 * 24 * 2)) // 2일 전
			)
		}

		for (item in sortedItems.take(5)) {
			val card = createLogCard(item)
			container.addView(card)
		}
	}

	/**
	 * 최근 현장 로그 단일 카드 동적 생성
	 */
	private fun createLogCard(item: InventoryItem): View {
		val isNormal = item.status.equals("NORMAL", ignoreCase = true) || item.status == "정상"

		val card = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
			background = ContextCompat.getDrawable(
				this@HomeDashboard, R.drawable.se2e8f0sw1cr12bffffff
			)
			val params = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT
			)
			params.setMargins(0, 0, 0, dpToPx(8))
			layoutParams = params
		}

		// 상태 표시 점 (Green/Amber)
		val statusDot = View(this).apply {
			val params = LinearLayout.LayoutParams(dpToPx(10), dpToPx(10))
			params.setMargins(0, 0, dpToPx(12), 0)
			layoutParams = params
			background = ContextCompat.getDrawable(
				this@HomeDashboard,
				if (isNormal) R.drawable.cr5b10b981 else R.drawable.cr5bf59e0b
			)
		}

		// 중앙 텍스트 영역 (품목명 + 상태 / 위치 및 수량)
		val textLayout = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
		}

		val tvTitle = TextView(this).apply {
			text = "${item.itemName} (${if (isNormal) "입고/재고 정상" else "이상/오적재 감지"})"
			setTextColor(Color.parseColor("#0F172A"))
			textSize = 13f
			typeface = Typeface.DEFAULT_BOLD
		}

		val tvSubtitle = TextView(this).apply {
			text = "위치: ${item.location}  ·  수량: ${item.quantity}개"
			setTextColor(Color.parseColor("#475569"))
			textSize = 11f
		}

		textLayout.addView(tvTitle)
		textLayout.addView(tvSubtitle)

		// 실시간/경과 상대 시간 표기 (예: 5분 전, 2시간 전, 1달 전)
		val tvTime = TextView(this).apply {
			text = formatRelativeTime(item.updatedAtTimestamp)
			setTextColor(Color.parseColor("#94A3B8"))
			textSize = 11f
		}

		card.addView(statusDot)
		card.addView(textLayout)
		card.addView(tvTime)

		// 카드 클릭 ➔ 재고 상세 페이지 이동
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

	/**
	 * 타임스탬프 기반 상대 경고/이력 시간 계산 (예: 방금 전, 5분 전, 2시간 전, 3일 전, 1달 전)
	 */
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
}