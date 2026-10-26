package com.example.myapp

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.google.firebase.firestore.ListenerRegistration

class AiAuditResults : AppCompatActivity() {

	private var inventoryListener: ListenerRegistration? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_ai_audit_results)

		// 상단 및 하단 배경 Glide 로드
		findViewById<View>(R.id.r92jaxcy3c4)?.visibility = View.GONE
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/lxazxxpk_expires_30_days.png").into(findViewById(R.id.r3qa1ncjec6t))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/87expjfj_expires_30_days.png").into(findViewById(R.id.rs6psw8xg03l))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/qmh90k2b_expires_30_days.png").into(findViewById(R.id.rjt4y2gmsgjf))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/e9gzahm3_expires_30_days.png").into(findViewById(R.id.rl54k66oqeuj))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/obbizl46_expires_30_days.png").into(findViewById(R.id.rktb3cut3f88))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/dqo0yxnb_expires_30_days.png").into(findViewById(R.id.rte7pemp4q98))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/fvw57hul_expires_30_days.png").into(findViewById(R.id.r9hjk1laagf4))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/4k7aazjx_expires_30_days.png").into(findViewById(R.id.re4xglprgpaq))
			Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/x5hjnz9j_expires_30_days.png").into(findViewById(R.id.rh7vg1bgh24r))

		// 카메라로 촬영하고 C++ Edge 보정을 마친 실사 사진 수신 및 뷰어 바인딩 (가로/세로 오리엔테이션 대응)
		val photoBytes = CapturedPhotoHolder.getAndClear() ?: intent.getByteArrayExtra("CAPTURED_PHOTO")
		val ivPhoto = findViewById<ImageView>(R.id.ivAiCapturedPhoto)
		if (photoBytes != null && ivPhoto != null) {
			val bitmap = BitmapFactory.decodeByteArray(photoBytes, 0, photoBytes.size)
			if (bitmap != null) {
				if (bitmap.width > bitmap.height) {
					ivPhoto.scaleType = ImageView.ScaleType.FIT_CENTER
					ivPhoto.adjustViewBounds = true
				} else {
					ivPhoto.scaleType = ImageView.ScaleType.CENTER_CROP
				}
				ivPhoto.setImageBitmap(bitmap)
			}
		} else if (ivPhoto != null) {
			Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/uzofup9p_expires_30_days.png").into(ivPhoto)
		}

		// 상단 뒤로가기
		findViewById<View>(R.id.r3qa1ncjec6t)?.setOnClickListener { finish() }

		// 1. [재촬영] 버튼
		val btnRetake: View? = findViewById(R.id.rn409sprmgg)
		btnRetake?.setOnClickListener {
			startActivity(Intent(this, AiAuditCamera::class.java))
			finish()
		}

		// 2. F-06: [🚨 오적재 알림 발송] 버튼 클릭 ➔ 위치, 정상 ID, 오적재 ID, 사진 패키징 후 FCM/푸시 전송
		val btnSendAlert: View? = findViewById(R.id.r0e5j960n87jp)
		btnSendAlert?.setOnClickListener {
			val payload = MisplacementAlertPayload(
				alertId = "F06_" + System.currentTimeMillis(),
				location = "B구역 R05-3층",
				expectedItemCode = "M-100",
				currentItemCode = "BT-200",
				photoUrl = "https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/uzofup9p_expires_30_days.png"
			)

			// F-06 파이프라인: 온라인 시 3초 이내 헤즈업 푸시 발송, 오프라인 단절 시 로컬 큐 캐싱 & 스낵바
			F06NotificationHelper.dispatchAlert(this, payload) {
				AnomalyManager.sendMisplacementAlert(
					context = this,
					itemCode = payload.expectedItemCode,
					itemName = "무선 마우스 M-100",
					currentLocation = "A구역 R01-1층 (오적재)",
					targetLocation = payload.location,
					quantity = 50,
					loaderUserId = "loader"
				) {
					finish()
				}
			}
		}

		// 하단 네비게이션 바 연결
		// 1. 홈
		findViewById<View>(R.id.rte7pemp4q98)?.setOnClickListener {
			val intent = Intent(this, HomeDashboard::class.java)
			intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
			startActivity(intent)
			finish()
		}

		// 2. 재고
		val openInventory = View.OnClickListener {
			startActivity(Intent(this, InventoryList::class.java))
			finish()
		}
		findViewById<View>(R.id.rklq2ru6dpxi)?.setOnClickListener(openInventory)
		findViewById<View>(R.id.r9hjk1laagf4)?.setOnClickListener(openInventory)

		// 3. 스캔
		val openScanner = View.OnClickListener {
			startActivity(Intent(this, BarcodeScanner::class.java))
		}
		findViewById<View>(R.id.r0ygmbl1hl7wq)?.setOnClickListener(openScanner)
		findViewById<View>(R.id.rttg048oiico)?.setOnClickListener(openScanner)

		// 4. 작업
		val openWork = View.OnClickListener {
			startActivity(Intent(this, WorkHistory::class.java))
		}
		findViewById<View>(R.id.rv9avan314c)?.setOnClickListener(openWork)
		findViewById<View>(R.id.re4xglprgpaq)?.setOnClickListener(openWork)

		// 5. 더보기 (바텀시트 메뉴)
		val openMore = View.OnClickListener {
			MenuHelper.showMoreMenu(this)
		}
		findViewById<View>(R.id.rza5cxscdic)?.setOnClickListener(openMore)
		findViewById<View>(R.id.rh7vg1bgh24r)?.setOnClickListener(openMore)

		// 실시간 Firestore 전산 데이터 바인딩
		setupRealtimeComparison()
	}

	override fun onDestroy() {
		super.onDestroy()
		inventoryListener?.remove()
	}

	/**
	 * Firestore 실시간 'inventory' 전산 데이터 수신 및 AI 실사 대조 결과 바인딩
	 */
	private fun setupRealtimeComparison() {
		inventoryListener = InventoryManager.listenToRealtimeInventory(this) { items ->
			renderSummaryAndDetails(items)
		}
	}

	/**
	 * 전산 데이터 기반 요약 수치 및 세부 대조 결과 카드 동적 바인딩
	 */
	private fun renderSummaryAndDetails(items: List<InventoryItem>) {
		val displayItems = if (items.isNotEmpty()) items else listOf(
			InventoryItem("M-100", "무선 마우스 M-100", 120, "A01-1단", "NORMAL"),
			InventoryItem("BT-200", "블루투스 이어폰 BT-200", 50, "A02-2단 (오적재 감지)", "ANOMALY"),
			InventoryItem("MC-300", "고속 C타입 멀티 충전기", 80, "A03-3단", "PENDING_QA"),
			InventoryItem("KB-400", "기계식 키보드 K-90", 200, "A04-1단", "NORMAL")
		)

		// 1. 요약 수치 바인딩
		val totalCount = displayItems.size
		val normalCount = displayItems.count { it.status.equals("NORMAL", ignoreCase = true) || it.status == "정상" }
		val anomalyCount = totalCount - normalCount

		findViewById<TextView>(R.id.rcmltsyaqay)?.text = "${totalCount}개 단"
		findViewById<TextView>(R.id.r1o3adrmw6zi)?.text = "${normalCount}개 단"
		findViewById<TextView>(R.id.rd1claoa9qi)?.text = "${anomalyCount}개 단"

		// 2. 동적 전산 대조 결과 카드 바인딩
		val container = findViewById<LinearLayout>(R.id.llAuditDetailContainer) ?: return
		container.removeAllViews()

		for (item in displayItems) {
			val card = createComparisonCard(item)
			container.addView(card)
		}
	}

	/**
	 * 단일 전산 대조 결과 카드 동적 생성
	 */
	private fun createComparisonCard(item: InventoryItem): View {
		val isNormal = item.status.equals("NORMAL", ignoreCase = true) || item.status == "정상"
		val isPending = item.status.equals("PENDING_QA", ignoreCase = true) || item.status.equals("WAITING_REVERIFICATION", ignoreCase = true)

		val card = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dpToPx(14), dpToPx(14), dpToPx(14), dpToPx(14))
			background = ContextCompat.getDrawable(
				this@AiAuditResults,
				if (isNormal) R.drawable.se2e8f0sw1cr12bffffff
				else if (isPending) R.drawable.se2e8f0sw1cr10bf8fafc
				else R.drawable.sef4444sw1cr12bfef2f2
			)
			val params = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT
			)
			params.setMargins(0, 0, 0, dpToPx(12))
			layoutParams = params
		}

		// 헤더 (로케이션 랙 위치 + 상태 배지)
		val headerLayout = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			val params = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT
			)
			params.setMargins(0, 0, 0, dpToPx(8))
			layoutParams = params
		}

		val tvLocation = TextView(this).apply {
			text = item.location.ifEmpty { "위치 미지정" }
			setTextColor(Color.parseColor("#0F172A"))
			textSize = 15f
			typeface = Typeface.DEFAULT_BOLD
			layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
		}

		val tvBadge = TextView(this).apply {
			text = if (isNormal) "정상 매칭" else if (isPending) "재검증 대기" else "오적재 의심"
			setTextColor(
				if (isNormal) Color.parseColor("#10B981")
				else if (isPending) Color.parseColor("#0D9488")
				else Color.WHITE
			)
			textSize = 11f
			typeface = Typeface.DEFAULT_BOLD
			setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2))
			background = ContextCompat.getDrawable(
				this@AiAuditResults,
				if (isNormal) R.drawable.s10b981sw1cr4becfdf5
				else if (isPending) R.drawable.cr100bf0fdfa
				else R.drawable.cr4bef4444
			)
		}

		headerLayout.addView(tvLocation)
		headerLayout.addView(tvBadge)

		// 상세 대조 정보 텍스트
		val tvDetail = TextView(this).apply {
			text = if (isNormal) {
				"전산 품목: ${item.itemName} [${item.itemCode}]  ·  수량 일치 (${item.quantity}개)"
			} else if (isPending) {
				"전산 품목: ${item.itemName} [${item.itemCode}]  ·  적재자 시정 완료 (재검증 대기)"
			} else {
				"전산 품목: ${item.itemName} [${item.itemCode}]  ·  위치 불일치 (${item.location})"
			}
			setTextColor(Color.parseColor("#475569"))
			textSize = 12f
		}

		card.addView(headerLayout)
		card.addView(tvDetail)

		return card
	}

	private fun dpToPx(dp: Int): Int {
		return (dp * resources.displayMetrics.density).toInt()
	}
}