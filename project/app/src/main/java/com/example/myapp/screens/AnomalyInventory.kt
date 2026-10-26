package com.example.myapp

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class AnomalyInventory : AppCompatActivity() {

	private var inventoryListener: ListenerRegistration? = null
	private var userRole: String = "QA"

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_anomaly_inventory)

		// 세션에서 사용자 권한(QA / LOADER) 가져오기
		val sharedPref = getSharedPreferences("app_user_session", MODE_PRIVATE)
		userRole = sharedPref.getString("ROLE", "QA") ?: "QA"

		findViewById<View>(R.id.rvr5zkk72xae)?.visibility = View.GONE
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/jn2255fo_expires_30_days.png").into(findViewById(R.id.r4dlso48svyt))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/tz57phta_expires_30_days.png").into(findViewById(R.id.rp42d4osxz4h))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/piax8y50_expires_30_days.png").into(findViewById(R.id.reyi6z9lj9u))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/4fjizdgl_expires_30_days.png").into(findViewById(R.id.rmau6uv0jsms))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/vy5hudv0_expires_30_days.png").into(findViewById(R.id.rpl9g04uz6f))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/78vttzi5_expires_30_days.png").into(findViewById(R.id.rrb0wh93puaf))

		// 상단 뒤로가기 버튼
		findViewById<View>(R.id.r4dlso48svyt)?.setOnClickListener { finish() }

		// 하단 네비게이션 바
		findViewById<View>(R.id.rp42d4osxz4h)?.setOnClickListener {
			val intent = Intent(this, HomeDashboard::class.java)
			intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
			startActivity(intent)
			finish()
		}

		findViewById<View>(R.id.rzxbmf3ceeq)?.setOnClickListener {
			startActivity(Intent(this, InventoryList::class.java))
		}

		val openScanner = View.OnClickListener {
			startActivity(Intent(this, BarcodeScanner::class.java))
		}
		findViewById<View>(R.id.rrb0wh93puaf)?.setOnClickListener(openScanner)
		findViewById<View>(R.id.rpi1ocgd4f2)?.setOnClickListener(openScanner)

		findViewById<View>(R.id.rjmjgrnlxxw)?.setOnClickListener {
			startActivity(Intent(this, WorkHistory::class.java))
		}

		findViewById<View>(R.id.ro4g6n5thjl)?.setOnClickListener {
			MenuHelper.showMoreMenu(this)
		}

		// 실시간 Firestore 이상 재고 수신
		setupRealtimeAnomalies()
	}

	/**
	 * Firebase Cloud Firestore 실시간 이상 재고 수신
	 */
	private fun setupRealtimeAnomalies() {
		inventoryListener = InventoryManager.listenToRealtimeInventory { items ->
			// 정상 처리되지 않은 항목들만 필터링 (ANOMALY 또는 PENDING_QA 상태)
			val anomalyItems = items.filter {
				!it.status.equals("NORMAL", ignoreCase = true) && it.status != "정상"
			}
			renderAnomalyList(anomalyItems)
		}
	}

	/**
	 * 이상 재고 카드 뷰 동적 렌더링
	 */
	private fun renderAnomalyList(items: List<InventoryItem>) {
		val container = findViewById<LinearLayout>(R.id.rj0lzllza8c) ?: return
		container.removeAllViews() // 기존 하드코딩 카드 삭제

		if (items.isEmpty()) {
			val emptyView = TextView(this).apply {
				text = "✅ 현재 처리할 이상 재고 및 오적재 내역이 없습니다."
				setTextColor(Color.parseColor("#10B981"))
				textSize = 15f
				typeface = Typeface.DEFAULT_BOLD
				gravity = Gravity.CENTER
				setPadding(0, dpToPx(40), 0, dpToPx(40))
			}
			container.addView(emptyView)
			return
		}

		for (item in items) {
			val card = createRoleBasedAnomalyCard(item)
			container.addView(card)
		}
	}

	/**
	 * 권한(QA / LOADER) 및 상태(ANOMALY / PENDING_QA)에 따른 동적 이상재고 카드 생성
	 */
	private fun createRoleBasedAnomalyCard(item: InventoryItem): View {
		val card = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
			background = ContextCompat.getDrawable(this@AnomalyInventory, R.drawable.s0d9488sw1cr16bffffff)
			val params = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT
			)
			params.setMargins(0, 0, 0, dpToPx(12))
			layoutParams = params
		}

		val isQA = userRole == "QA"
		val isPendingQA = item.status.equals("PENDING_QA", ignoreCase = true)

		// 1. 헤더 영역 (상태 배지 & 위치 정보)
		val headerLayout = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = android.view.Gravity.CENTER_VERTICAL
		}

		val badge = TextView(this).apply {
			text = if (isPendingQA) "⏳ 승인 대기 중" else "🚨 오적재 감지"
			setTextColor(if (isPendingQA) Color.parseColor("#D97706") else Color.parseColor("#EF4444"))
			textSize = 11f
			typeface = Typeface.DEFAULT_BOLD
			setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2))
			background = ContextCompat.getDrawable(
				this@AnomalyInventory,
				if (isPendingQA) R.drawable.cr6bfffbeb else R.drawable.sef4444sw1cr4bfef2f2
			)
		}

		val tvLoc = TextView(this).apply {
			text = "📍 현재: " + item.location
			setTextColor(Color.parseColor("#0F172A"))
			textSize = 13f
			typeface = Typeface.DEFAULT_BOLD
			setPadding(dpToPx(8), 0, 0, 0)
			layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
		}

		val tvRoleTag = TextView(this).apply {
			text = if (isQA) "검수자 모드" else "적재자 모드"
			setTextColor(Color.parseColor("#0D9488"))
			textSize = 11f
			typeface = Typeface.DEFAULT_BOLD
		}

		headerLayout.addView(badge)
		headerLayout.addView(tvLoc)
		headerLayout.addView(tvRoleTag)

		// 2. 바디 영역 (상품 정보 & 요청 수치 변경 내역)
		val bodyLayout = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(0, dpToPx(10), 0, dpToPx(10))
		}

		val tvTitle = TextView(this).apply {
			text = item.itemName
			setTextColor(Color.parseColor("#0F172A"))
			textSize = 15f
			typeface = Typeface.DEFAULT_BOLD
		}

		val tvInfo = TextView(this).apply {
			text = "품목 코드: ${item.itemCode}  ·  확정 수량: ${item.quantity}개"
			setTextColor(Color.parseColor("#94A3B8"))
			textSize = 12f
		}

		bodyLayout.addView(tvTitle)
		bodyLayout.addView(tvInfo)

		// 적재자가 수량/위치를 수정 요청했을 경우 변경 요청 수치 표출
		if (isPendingQA) {
			val reqLoc = if (item.pendingLocation.isNotEmpty()) item.pendingLocation else item.location
			val reqQty = if (item.pendingQuantity > 0) item.pendingQuantity else item.quantity

			val tvRequestText = TextView(this).apply {
				text = "🔄 적재자 요청 변경 ➔ [위치: $reqLoc / 수량: ${reqQty}개]"
				setTextColor(Color.parseColor("#D97706"))
				textSize = 13f
				typeface = Typeface.DEFAULT_BOLD
				setPadding(0, dpToPx(4), 0, 0)
			}
			bodyLayout.addView(tvRequestText)
		}

		// 3. 권한별 버튼 영역
		val buttonBar = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = android.view.Gravity.CENTER_VERTICAL
			setPadding(0, dpToPx(8), 0, 0)
		}

		if (isQA) {
			// === [검수자 QA 모드 버튼] ===
			if (isPendingQA) {
				// 적재자가 요청한 수량/위치를 검수자가 최종 확인하고 DB에 반영
				val btnApprove = Button(this).apply {
					text = "✅ 최종 승인 및 DB 반영"
					setTextColor(Color.WHITE)
					textSize = 12f
					typeface = Typeface.DEFAULT_BOLD
					background = ContextCompat.getDrawable(this@AnomalyInventory, R.drawable.cr8b0d9488)
					layoutParams = LinearLayout.LayoutParams(0, dpToPx(40), 1f).apply {
						setMargins(0, 0, dpToPx(6), 0)
					}
					setOnClickListener {
						// 최종 승인 시 적재자가 요청한 수량과 위치를 실제 DB 수량/위치로 확정하고 정상(NORMAL) 상태로 저장
						val finalLoc = if (item.pendingLocation.isNotEmpty()) item.pendingLocation else item.location
						val finalQty = if (item.pendingQuantity > 0) item.pendingQuantity else item.quantity

						val approvedItem = item.copy(
							quantity = finalQty,
							location = finalLoc,
							status = "NORMAL",
							pendingQuantity = 0,
							pendingLocation = ""
						)

						InventoryManager.addOrUpdateInventoryItem(
							item = approvedItem,
							onSuccess = {
								// 승인 완료 시 anomalies 컬렉션에서도 해당 항목 삭제하여 DB 정정/정리
								val db = FirebaseFirestore.getInstance()
								db.collection("anomalies")
									.whereEqualTo("itemCode", item.itemCode)
									.get()
									.addOnSuccessListener { querySnapshot ->
										for (doc in querySnapshot.documents) {
											db.collection("anomalies").document(doc.id).delete()
										}
									}

								Toast.makeText(this@AnomalyInventory, "✅ 검수 승인 완료! 실제 수량($finalQty 개) 및 위치($finalLoc)가 DB에 최종 반영되었습니다.", Toast.LENGTH_LONG).show()
							}
						)
					}
				}

				val btnReject = Button(this).apply {
					text = "❌ 반려 (재조치)"
					setTextColor(Color.parseColor("#475569"))
					textSize = 12f
					typeface = Typeface.DEFAULT_BOLD
					background = ContextCompat.getDrawable(this@AnomalyInventory, R.drawable.se2e8f0sw1cr8)
					layoutParams = LinearLayout.LayoutParams(0, dpToPx(40), 1f)
					setOnClickListener {
						val rejectedItem = item.copy(status = "ANOMALY")
						InventoryManager.addOrUpdateInventoryItem(
							item = rejectedItem,
							onSuccess = {
								Toast.makeText(this@AnomalyInventory, "반려 처리되었습니다. 적재자에게 재조치가 요청됩니다.", Toast.LENGTH_SHORT).show()
							}
						)
					}
				}

				buttonBar.addView(btnApprove)
				buttonBar.addView(btnReject)
			} else {
				// 아직 적재자가 조치하지 않은 오적재 항목
				val btnNotify = Button(this).apply {
					text = "🚨 적재자에게 조치 알림 재발송"
					setTextColor(Color.WHITE)
					textSize = 12f
					typeface = Typeface.DEFAULT_BOLD
					background = ContextCompat.getDrawable(this@AnomalyInventory, R.drawable.cr8b0d9488)
					layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(40))
					setOnClickListener {
						AnomalyManager.sendMisplacementAlert(
							context = this@AnomalyInventory,
							itemCode = item.itemCode,
							itemName = item.itemName,
							currentLocation = item.location,
							targetLocation = "B구역 R05-3층",
							quantity = item.quantity,
							loaderUserId = "loader"
						)
					}
				}
				buttonBar.addView(btnNotify)
			}
		} else {
			// === [적재자 LOADER 모드 버튼] ===
			if (isPendingQA) {
				val tvWaiting = TextView(this).apply {
					text = "⏳ 검수자(QA)의 승인을 기다리는 중입니다."
					setTextColor(Color.parseColor("#D97706"))
					textSize = 13f
					typeface = Typeface.DEFAULT_BOLD
					gravity = Gravity.CENTER
					setPadding(0, dpToPx(8), 0, dpToPx(8))
					layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
				}
				buttonBar.addView(tvWaiting)
			} else {
				// 적재자가 올바르게 재적재하거나 수량/위치를 수정하고 검수자에게 승인을 요청함
				val btnRequestComplete = Button(this).apply {
					text = "재적재 완료 (검수자 승인 요청)"
					setTextColor(Color.parseColor("#475569"))
					textSize = 12f
					typeface = Typeface.DEFAULT_BOLD
					background = ContextCompat.getDrawable(this@AnomalyInventory, R.drawable.se2e8f0sw1cr8)
					layoutParams = LinearLayout.LayoutParams(0, dpToPx(40), 1f).apply {
						setMargins(0, 0, dpToPx(6), 0)
					}
					setOnClickListener {
						val pendingItem = item.copy(
							status = "PENDING_QA",
							pendingLocation = item.location,
							pendingQuantity = item.quantity
						)
						InventoryManager.addOrUpdateInventoryItem(
							item = pendingItem,
							onSuccess = {
								Toast.makeText(this@AnomalyInventory, "✅ 올바른 재적재 완료! 검수자(QA)에게 승인 요청을 전송했습니다.", Toast.LENGTH_LONG).show()
							}
						)
					}
				}

				val btnEdit = Button(this).apply {
					text = "수량/위치 수정 및 승인 요청"
					setTextColor(Color.WHITE)
					textSize = 12f
					typeface = Typeface.DEFAULT_BOLD
					background = ContextCompat.getDrawable(this@AnomalyInventory, R.drawable.cr8b0d9488)
					layoutParams = LinearLayout.LayoutParams(0, dpToPx(40), 1f)
					setOnClickListener {
						EditInventoryDialog.show(this@AnomalyInventory, item)
					}
				}

				buttonBar.addView(btnRequestComplete)
				buttonBar.addView(btnEdit)
			}
		}

		card.addView(headerLayout)
		card.addView(bodyLayout)
		card.addView(buttonBar)

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
