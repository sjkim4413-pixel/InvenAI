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

class WorkHistory : AppCompatActivity() {

	private var inventoryListener: ListenerRegistration? = null
	private var currentTab: String = "HISTORY" // "HISTORY" or "ALERTS"

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_work_history)
		findViewById<View>(R.id.rr145tdsfh3)?.visibility = View.GONE
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/irtilrwz_expires_30_days.png").into(findViewById(R.id.rx9hjfq8hsit))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/7u67eh1p_expires_30_days.png").into(findViewById(R.id.rb1cli47q0dv))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/t485xuic_expires_30_days.png").into(findViewById(R.id.r6q9ucayn5ik))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/y4lu7uls_expires_30_days.png").into(findViewById(R.id.r8bjmhpf948))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/pvlcfmgt_expires_30_days.png").into(findViewById(R.id.rdi9k35mib1e))

		// 상단 뒤로가기 버튼
		findViewById<View>(R.id.r3vtuuy6ybip)?.setOnClickListener { finish() }

		// 탭 전환 리스너
		val tabHistory = findViewById<View>(R.id.rqbydvd1z5wa)
		val tabAlerts = findViewById<View>(R.id.rkjg6bxc6v8)

		tabHistory?.setOnClickListener {
			currentTab = "HISTORY"
			updateTabStyles()
			setupRealtimeLogs()
		}

		tabAlerts?.setOnClickListener {
			currentTab = "ALERTS"
			updateTabStyles()
			setupRealtimeLogs()
		}

		// 하단 네비게이션 바
		findViewById<View>(R.id.rx9hjfq8hsit)?.setOnClickListener {
			val intent = Intent(this, HomeDashboard::class.java)
			intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
			startActivity(intent)
			finish()
		}
		findViewById<View>(R.id.rt6eixtr5xfq)?.setOnClickListener {
			startActivity(Intent(this, InventoryList::class.java))
		}
		val openScanner = View.OnClickListener {
			startActivity(Intent(this, BarcodeScanner::class.java))
		}
		findViewById<View>(R.id.rdi9k35mib1e)?.setOnClickListener(openScanner)
		findViewById<View>(R.id.rvd9yojqzrd)?.setOnClickListener(openScanner)
		findViewById<View>(R.id.ri1h4l2mq8le)?.setOnClickListener {
			MenuHelper.showMoreMenu(this)
		}

		// 실시간 로그 바인딩
		setupRealtimeLogs()
	}

	private fun updateTabStyles() {
		val tvHistory = findViewById<TextView>(R.id.rcm9935xqg6k)
		val tvAlerts = findViewById<TextView>(R.id.rj2s496dgzep)

		if (currentTab == "HISTORY") {
			tvHistory?.setTextColor(Color.parseColor("#0D9488"))
			tvHistory?.typeface = Typeface.DEFAULT_BOLD
			tvAlerts?.setTextColor(Color.parseColor("#475569"))
			tvAlerts?.typeface = Typeface.DEFAULT
		} else {
			tvAlerts?.setTextColor(Color.parseColor("#0D9488"))
			tvAlerts?.typeface = Typeface.DEFAULT_BOLD
			tvHistory?.setTextColor(Color.parseColor("#475569"))
			tvHistory?.typeface = Typeface.DEFAULT
		}
	}

	private fun setupRealtimeLogs() {
		inventoryListener?.remove()
		inventoryListener = InventoryManager.listenToRealtimeInventory(this) { items ->
			val container = findViewById<LinearLayout>(R.id.rxbx6tajr89a) ?: return@listenToRealtimeInventory
			container.removeAllViews()

			val filteredItems = if (currentTab == "HISTORY") {
				items.sortedByDescending { it.updatedAtTimestamp }
			} else {
				items.filter { !it.status.equals("NORMAL", ignoreCase = true) && it.status != "정상" }
					.sortedByDescending { it.updatedAtTimestamp }
			}

			if (filteredItems.isEmpty()) {
				val emptyView = TextView(this).apply {
					text = if (currentTab == "HISTORY") "📋 기록된 작업 이력이 없습니다." else "✅ 등록된 알림/이상 내역이 없습니다."
					setTextColor(Color.parseColor("#94A3B8"))
					textSize = 14f
					gravity = Gravity.CENTER
					setPadding(0, dpToPx(40), 0, dpToPx(40))
				}
				container.addView(emptyView)
				return@listenToRealtimeInventory
			}

			for (item in filteredItems) {
				val card = createLogCard(item)
				container.addView(card)
			}
		}
	}

	private fun createLogCard(item: InventoryItem): View {
		val isNormal = item.status.equals("NORMAL", ignoreCase = true) || item.status == "정상"

		val card = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			setPadding(dpToPx(14), dpToPx(14), dpToPx(14), dpToPx(14))
			background = ContextCompat.getDrawable(
				this@WorkHistory, R.drawable.se2e8f0sw1cr12bffffff
			)
			val params = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT
			)
			params.setMargins(0, 0, 0, dpToPx(8))
			layoutParams = params
		}

		val statusDot = View(this).apply {
			val params = LinearLayout.LayoutParams(dpToPx(10), dpToPx(10))
			params.setMargins(0, 0, dpToPx(12), 0)
			layoutParams = params
			background = ContextCompat.getDrawable(
				this@WorkHistory,
				if (isNormal) R.drawable.cr5b10b981 else R.drawable.cr5bf59e0b
			)
		}

		val textLayout = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
		}

		val tvTitle = TextView(this).apply {
			text = "${item.itemName} (${if (isNormal) "입고/재고 정상 처리" else "이상/오적재 발생"})"
			setTextColor(Color.parseColor("#0F172A"))
			textSize = 13f
			typeface = Typeface.DEFAULT_BOLD
		}

		val tvSubtitle = TextView(this).apply {
			text = "위치: ${item.location}  ·  수량: ${item.quantity}개  ·  작업자: ${item.userId}"
			setTextColor(Color.parseColor("#475569"))
			textSize = 11f
		}

		textLayout.addView(tvTitle)
		textLayout.addView(tvSubtitle)

		val tvTime = TextView(this).apply {
			text = formatRelativeTime(item.updatedAtTimestamp)
			setTextColor(Color.parseColor("#94A3B8"))
			textSize = 11f
		}

		card.addView(statusDot)
		card.addView(textLayout)
		card.addView(tvTime)

		return card
	}

	private fun dpToPx(dp: Int): Int {
		return (dp * resources.displayMetrics.density).toInt()
	}

	private fun formatRelativeTime(timestamp: Long): String {
		val now = System.currentTimeMillis()
		val diff = now - timestamp

		if (diff <= 0) return "방금 전"

		val seconds = diff / 1000
		val minutes = seconds / 60
		val hours = minutes / 60
		val days = hours / 24

		return when {
			minutes < 1 -> "방금 전"
			minutes < 60 -> "${minutes}분 전"
			hours < 24 -> "${hours}시간 전"
			else -> "${days}일 전"
		}
	}

	override fun onDestroy() {
		super.onDestroy()
		inventoryListener?.remove()
	}
}
