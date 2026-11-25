package com.example.myapp

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.ListenerRegistration

class AnomalyInventory : AppCompatActivity() {

	private var inventoryListener: ListenerRegistration? = null
	private var auditListener: ListenerRegistration? = null
	private var auditAlerts = emptyList<AuditMisplacementAlert>()
	private var auditProblem: String? = null
	private var userRole: String = ""
	private var activeItems = emptyList<InventoryItem>()
	private var filter: String? = null
	private var warehouse: String? = null
	private var statusMessage: String? = "이상 재고를 불러오는 중입니다."

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_anomaly_inventory)

		// 세션에서 사용자 권한(QA / LOADER) 가져오기
		userRole = InventoryManager.role(this).orEmpty()
		if (userRole != "QA" && userRole != "LOADER") {
			finish()
			return
		}

		findViewById<View>(R.id.rvr5zkk72xae)?.visibility = View.GONE

		// 상단 뒤로가기 버튼
		findViewById<View>(R.id.r4dlso48svyt)?.setOnClickListener { finish() }

		findViewById<LinearLayout>(R.id.warehouseBottomNavigation).addView(warehouseNavigation(this))

		filter = savedInstanceState?.getString("anomalyFilter")
		warehouse = savedInstanceState?.getString("warehouse") ?: intent.getStringExtra("WAREHOUSE_CODE")
		for ((id, state) in listOf(R.id.rgaffdocilc to null, R.id.rytuded4x8wi to "ANOMALY", R.id.ri2fvq4nz5o to "PENDING_QA")) {
			findViewById<View>(id).setOnClickListener { filter = state; renderCurrentAnomalies() }
		}
		findViewById<View>(R.id.rdhlujjv2zdp).setOnClickListener {
			startActivity(Intent(this, WorkHistory::class.java).putExtra("ANOMALIES", true).putExtra("RESOLVED", true)
				.putExtra("WAREHOUSE_CODE", warehouse))
		}
		renderCurrentAnomalies()
	}

	override fun onStart() {
		super.onStart()
		if (isFinishing) return
		auditListener = AuditAlertManager.listen(this, onError = {
			auditProblem = it
			renderCurrentAnomalies()
		}) {
			auditAlerts = it
			auditProblem = null
			renderCurrentAnomalies()
		}
		inventoryListener = InventoryManager.listenToRealtimeInventory(this, onError = {
			statusMessage = it
			renderCurrentAnomalies()
		}) { items ->
			activeItems = items.filter { it.needsReview }.sortedByDescending { it.updatedAtTimestamp }
			statusMessage = null
			renderCurrentAnomalies()
		}
	}

	override fun onSaveInstanceState(outState: Bundle) {
		outState.putString("anomalyFilter", filter)
		outState.putString("warehouse", warehouse)
		super.onSaveInstanceState(outState)
	}

	private fun renderCurrentAnomalies() {
		for ((id, state, textId) in listOf(
			Triple(R.id.rgaffdocilc, null, R.id.rm6h0exv82s),
			Triple(R.id.rytuded4x8wi, "ANOMALY", R.id.rqs6bj17x36p),
			Triple(R.id.ri2fvq4nz5o, "PENDING_QA", R.id.raasbhdamjlq)
		)) {
			val selected = filter == state
			findViewById<View>(id).setBackgroundResource(
				if (selected) R.drawable.cr100b0d9488 else R.drawable.se2e8f0sw1cr100bf8fafc
			)
			findViewById<TextView>(textId).setTextColor(if (selected) Color.WHITE else Color.rgb(51, 65, 85))
		}
		renderAnomalyList(activeItems.filter { (filter == null || it.status == filter) &&
			(warehouse == null || it.warehouse == warehouse) })
	}

	/**
	 * 이상 재고 카드 뷰 동적 렌더링
	 */
	private fun renderAnomalyList(items: List<InventoryItem>) {
		val container = findViewById<LinearLayout>(R.id.rj0lzllza8c) ?: return
		container.removeAllViews() // 기존 하드코딩 카드 삭제
		warehouse?.let { selected -> container.addView(TextView(this).apply {
			text = "창고 · $selected"
			setTextColor(WarehouseAppearance.MUTED)
			setPadding(0, 0, 0, dpToPx(12))
		}) }
		val findings = auditAlerts.filter {
			(warehouse == null || it.warehouse == warehouse) &&
				(filter == null || (filter == "ANOMALY" && it.status == "OPEN") ||
					(filter == "PENDING_QA" && it.status == "REVIEW_REQUESTED"))
		}
		for (alert in findings) container.addView(AuditAlertViews.card(this, alert))
		if (auditProblem != null) container.addView(TextView(this).apply {
			text = auditProblem
			setTextColor(WarehouseAppearance.MUTED)
		})

		if (statusMessage != null || (items.isEmpty() && findings.isEmpty())) {
			val emptyView = TextView(this).apply {
				text = statusMessage ?: "현재 조건에 해당하는 이상 재고가 없습니다."
				setTextColor(WarehouseAppearance.MUTED)
				textSize = 15f
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
			background = WarehouseAppearance.shape(this@AnomalyInventory, Color.WHITE, border = WarehouseAppearance.BORDER)
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
			text = if (isPendingQA) "승인 대기" else "오적재 감지"
			setTextColor(if (isPendingQA) Color.parseColor("#D97706") else Color.parseColor("#EF4444"))
			textSize = 11f
			typeface = Typeface.DEFAULT_BOLD
			setPadding(dpToPx(8), dpToPx(5), dpToPx(8), dpToPx(5))
			background = ContextCompat.getDrawable(
				this@AnomalyInventory,
				if (isPendingQA) R.drawable.cr6bfffbeb else R.drawable.sef4444sw1cr4bfef2f2
			)
		}

		val tvLoc = TextView(this).apply {
			text = "현재 · " + item.location
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
			setPadding(0, dpToPx(6), 0, 0)
			setTextColor(WarehouseAppearance.MUTED)
			textSize = 12f
		}

		bodyLayout.addView(tvTitle)
		bodyLayout.addView(tvInfo)

		// 적재자가 수량/위치를 수정 요청했을 경우 변경 요청 수치 표출
		if (isPendingQA) {
			val reqLoc = if (item.pendingLocation.isNotEmpty()) item.pendingLocation else item.location
			val reqQty = if (item.pendingLocation.isNotBlank()) item.pendingQuantity else item.quantity

			val tvRequestText = TextView(this).apply {
				text = "변경 요청 · $reqLoc / ${reqQty}개"
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
				val btnApprove = MaterialButton(this).apply {
					text = "검수 승인"
					setTextColor(Color.WHITE)
					textSize = 12f
					typeface = Typeface.DEFAULT_BOLD
					backgroundTintList = android.content.res.ColorStateList.valueOf(WarehouseAppearance.TEAL)
					layoutParams = LinearLayout.LayoutParams(0, dpToPx(40), 1f).apply {
						setMargins(0, 0, dpToPx(6), 0)
					}
					setOnClickListener {
						AnomalyManager.finishReview(
							context = this@AnomalyInventory,
							item = item,
							approved = true,
							onSuccess = {
								Toast.makeText(this@AnomalyInventory, "검수 승인이 완료되어 요청 수량과 위치가 반영되었습니다.", Toast.LENGTH_LONG).show()
							},
							onFailure = ::showWriteError
						)
					}
				}

				val btnReject = MaterialButton(this).apply {
					text = "재조치 요청"
					setTextColor(Color.parseColor("#475569"))
					textSize = 12f
					typeface = Typeface.DEFAULT_BOLD
					backgroundTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
					strokeWidth = dpToPx(1)
					strokeColor = android.content.res.ColorStateList.valueOf(WarehouseAppearance.BORDER)
					layoutParams = LinearLayout.LayoutParams(0, dpToPx(40), 1f)
					setOnClickListener {
						AnomalyManager.finishReview(
							context = this@AnomalyInventory,
							item = item,
							approved = false,
							onSuccess = {
								Toast.makeText(this@AnomalyInventory, "반려 처리되었습니다. 적재자에게 재조치가 요청됩니다.", Toast.LENGTH_SHORT).show()
							},
							onFailure = ::showWriteError
						)
					}
				}

				buttonBar.addView(btnApprove)
				buttonBar.addView(btnReject)
			} else {
				// 아직 적재자가 조치하지 않은 오적재 항목
				val btnNotify = MaterialButton(this).apply {
					text = "적재자에게 조치 알림 재발송"
					setTextColor(Color.WHITE)
					textSize = 12f
					typeface = Typeface.DEFAULT_BOLD
					backgroundTintList = android.content.res.ColorStateList.valueOf(WarehouseAppearance.TEAL)
					layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(40))
					setOnClickListener {
						AnomalyManager.sendMisplacementAlert(
							context = this@AnomalyInventory,
							item = item,
							targetLocation = item.targetLocation
						)
					}
				}
				buttonBar.addView(btnNotify)
			}
		} else {
			// === [적재자 LOADER 모드 버튼] ===
			if (isPendingQA) {
				val tvWaiting = TextView(this).apply {
					text = "검수자의 승인을 기다리는 중입니다."
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
				val btnRequestComplete = MaterialButton(this).apply {
					text = "재적재 승인 요청"
					setTextColor(Color.parseColor("#475569"))
					textSize = 12f
					typeface = Typeface.DEFAULT_BOLD
					backgroundTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
					strokeWidth = dpToPx(1)
					strokeColor = android.content.res.ColorStateList.valueOf(WarehouseAppearance.BORDER)
					layoutParams = LinearLayout.LayoutParams(0, dpToPx(40), 1f).apply {
						setMargins(0, 0, dpToPx(6), 0)
					}
					setOnClickListener {
						InventoryManager.requestReview(
							context = this@AnomalyInventory,
							item = item,
							pendingLocation = item.location,
							pendingQuantity = item.quantity,
							onSuccess = {
								Toast.makeText(this@AnomalyInventory, "✅ 올바른 재적재 완료! 검수자(QA)에게 승인 요청을 전송했습니다.", Toast.LENGTH_LONG).show()
							},
							onFailure = ::showWriteError
						)
					}
				}

				val btnEdit = MaterialButton(this).apply {
					text = "수량 / 위치 수정"
					setTextColor(Color.WHITE)
					textSize = 12f
					typeface = Typeface.DEFAULT_BOLD
					backgroundTintList = android.content.res.ColorStateList.valueOf(WarehouseAppearance.TEAL)
					layoutParams = LinearLayout.LayoutParams(0, dpToPx(40), 1f)
					setOnClickListener {
						EditInventoryDialog.show(this@AnomalyInventory, item)
					}
				}

				buttonBar.addView(btnRequestComplete)
				buttonBar.addView(btnEdit)
			}
		}

		for (index in 0 until buttonBar.childCount) {
			(buttonBar.getChildAt(index) as? MaterialButton)?.apply {
				cornerRadius = dpToPx(14)
				insetTop = 0
				insetBottom = 0
				elevation = 0f
				stateListAnimator = null
				isAllCaps = false
				minHeight = 0
				setPadding(dpToPx(12), 0, dpToPx(12), 0)
				layoutParams.height = dpToPx(48)
			}
		}
		card.addView(headerLayout)
		card.addView(bodyLayout)
		card.addView(buttonBar)

		return card
	}

	private fun showWriteError(message: String) {
		Toast.makeText(this, "저장 실패: $message", Toast.LENGTH_LONG).show()
	}

	private fun dpToPx(dp: Int): Int {
		return (dp * resources.displayMetrics.density).toInt()
	}

	override fun onStop() {
		inventoryListener?.remove()
		inventoryListener = null
		auditListener?.remove()
		auditListener = null
		super.onStop()
	}
}
