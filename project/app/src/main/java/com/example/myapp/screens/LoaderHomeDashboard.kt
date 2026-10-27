package com.example.myapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.ListenerRegistration

class LoaderHomeDashboard : AppCompatActivity() {

	private var inventoryListener: ListenerRegistration? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_loader_home)

		val sharedPref = getSharedPreferences("app_user_session", MODE_PRIVATE)
		val companyCode = sharedPref.getString("COMPANY_CODE", "ABC-001") ?: "ABC-001"
		val userId = sharedPref.getString("USER_ID", "loader") ?: "loader"

		findViewById<TextView>(R.id.tvLoaderUserSession)?.text = "사업장: $companyCode | 사번: $userId"

		findViewById<View>(R.id.cardInbound)?.setOnClickListener {
			startActivity(Intent(this, Inbound::class.java))
		}

		findViewById<View>(R.id.cardTransfer)?.setOnClickListener {
			startActivity(Intent(this, Transfer::class.java))
		}

		findViewById<View>(R.id.cardAnomaly)?.setOnClickListener {
			startActivity(Intent(this, AnomalyInventory::class.java))
		}

		findViewById<View>(R.id.cardWorkHistory)?.setOnClickListener {
			startActivity(Intent(this, WorkHistory::class.java))
		}

		findViewById<View>(R.id.btnScanBarcode)?.setOnClickListener {
			startActivity(Intent(this, BarcodeScanner::class.java))
		}

		findViewById<View>(R.id.btnLogoutLoader)?.setOnClickListener {
			sharedPref.edit().clear().apply()
			val intent = Intent(this, Frame1::class.java).apply {
				flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
			}
			startActivity(intent)
			finish()
		}
	}

	override fun onResume() {
		super.onResume()
		setupAnomalyBanner()
	}

	override fun onPause() {
		super.onPause()
		inventoryListener?.remove()
	}

	/**
	 * 실시간 재고(inventory) 상태를 모니터링하여 ANOMALY 상태인 품목이 있을 때만 긴급 알람 배너 표출 (자체 정화 메커니즘)
	 */
	private fun setupAnomalyBanner() {
		inventoryListener = InventoryManager.listenToRealtimeInventory(this) { items ->
			val cardAlert = findViewById<View>(R.id.cardActiveAlert)
			val tvAlertTitle = findViewById<TextView>(R.id.tvAlertTitle)
			val tvAlertContent = findViewById<TextView>(R.id.tvAlertContent)

			// ANOMALY 상태이면서 수량이 0 초과인 품목 필터링
			val anomalyItems = items.filter {
				it.status.equals("ANOMALY", ignoreCase = true) && it.quantity > 0
			}

			if (anomalyItems.isNotEmpty()) {
				val doc = anomalyItems[0]
				val itemName = doc.itemName.ifEmpty { "품목" }
				val currLoc = doc.location
				val targetLoc = doc.targetLocation.ifEmpty { "목표 랙 위치" }

				cardAlert?.setBackgroundColor(android.graphics.Color.parseColor("#FEF2F2"))
				tvAlertTitle?.text = "🚨 [긴급] 오적재 조치 요청 발생!"
				tvAlertTitle?.setTextColor(android.graphics.Color.parseColor("#B91C1C"))
				tvAlertContent?.text = "품목: $itemName\n현재위치: $currLoc ➔ 목표위치: $targetLoc\n(터치하여 조치하기)"
				tvAlertContent?.setTextColor(android.graphics.Color.parseColor("#7F1D1D"))
				cardAlert?.setOnClickListener {
					startActivity(Intent(this, AnomalyInventory::class.java))
				}
			} else {
				cardAlert?.setBackgroundColor(android.graphics.Color.parseColor("#F0FDFA"))
				tvAlertTitle?.text = "✅ 오적재 조치 현황: 정상"
				tvAlertTitle?.setTextColor(android.graphics.Color.parseColor("#0D9488"))
				tvAlertContent?.text = "현재 접수된 오적재 조치 요청이 없습니다. (터치 시 오적재 관리 이동)"
				tvAlertContent?.setTextColor(android.graphics.Color.parseColor("#475569"))
				cardAlert?.setOnClickListener {
					startActivity(Intent(this, AnomalyInventory::class.java))
				}
			}
		}
	}
}
