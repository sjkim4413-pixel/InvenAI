package com.example.myapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration

class LoaderHomeDashboard : AppCompatActivity() {

	private var inventoryListener: ListenerRegistration? = null
	private var auditListener: ListenerRegistration? = null
	private var legacyAnomalies = emptyList<InventoryItem>()
	private var auditAlerts = emptyList<AuditMisplacementAlert>()
	private var alertProblem: String? = null
	private val notificationPermission = registerForActivityResult(
		androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
	) {
		(application as? InvenAIApplication)?.notificationPermissionChanged()
		updateNotificationStatus(F06PushRegistration.status.value.orEmpty())
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_loader_home)
		if (InventoryManager.role(this) != "LOADER") {
			openWarehouseHome(this)
			return
		}

		val sharedPref = getSharedPreferences("app_user_session", MODE_PRIVATE)
		F06NotificationHelper.ensureChannel(this)
		findViewById<View>(R.id.btnLoaderNotifications).setOnClickListener {
			if (android.os.Build.VERSION.SDK_INT >= 33 && !F06NotificationHelper.notificationsEnabled(this) &&
				androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(this, android.Manifest.permission.POST_NOTIFICATIONS)) {
				notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
			} else {
				F06PushRegistration.sync(this)
				startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
					.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
			}
		}
		F06PushRegistration.status.observe(this) { updateNotificationStatus(it) }
		if (android.os.Build.VERSION.SDK_INT >= 33 &&
			androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
			android.content.pm.PackageManager.PERMISSION_GRANTED && !sharedPref.getBoolean("NOTIFICATION_PERMISSION_ASKED", false)) {
			sharedPref.edit().putBoolean("NOTIFICATION_PERMISSION_ASKED", true).apply()
			notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
		}
		val companyCode = InventoryManager.activeCompanyCode(this).orEmpty()
		val userId = sharedPref.getString("USER_ID", "").orEmpty()

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
			F06PushRegistration.unregister(this)
			FirebaseAuth.getInstance().signOut()
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
		if (!isFinishing) {
			setupAnomalyBanner()
			updateNotificationStatus(F06PushRegistration.status.value.orEmpty())
		}
	}

	private fun updateNotificationStatus(connection: String) {
		findViewById<TextView>(R.id.tvLoaderNotificationStatus).text = when {
			!F06NotificationHelper.notificationsEnabled(this) -> "기기 알림이 꺼져 있습니다. 알림을 허용해 주세요."
			!F06NotificationHelper.headsUpEnabled(this) -> "소리·상단 팝업 알림 설정을 확인해 주세요."
			else -> connection
		}
	}

	override fun onPause() {
		super.onPause()
		inventoryListener?.remove()
		auditListener?.remove()
		inventoryListener = null
		auditListener = null
	}

	private fun setupAnomalyBanner() {
        inventoryListener?.remove()
        auditListener?.remove()
        inventoryListener = InventoryManager.listenToRealtimeInventory(this, onError = {
            alertProblem = it; renderAlertBanner()
        }) { items ->
            legacyAnomalies = items.filter { it.status == "ANOMALY" }.sortedByDescending { it.updatedAtTimestamp }
            renderAlertBanner()
        }
        auditListener = AuditAlertManager.listen(this, onError = {
            alertProblem = it; renderAlertBanner()
        }) {
            auditAlerts = it; alertProblem = null; renderAlertBanner()
        }
    }

    private fun renderAlertBanner() {
        val card = findViewById<View>(R.id.cardActiveAlert)
        val title = findViewById<TextView>(R.id.tvAlertTitle)
        val content = findViewById<TextView>(R.id.tvAlertContent)
        val open = auditAlerts.filter { it.status == "OPEN" }
        val legacy = legacyAnomalies.filter { item -> auditAlerts.none { it.inventoryId == item.documentId } }
        val count = open.size + legacy.size
        val waiting = auditAlerts.count { it.status == "REVIEW_REQUESTED" }
        when {
            count > 0 -> {
                card.background = WarehouseAppearance.shape(this, 0xFFFEF2F2.toInt(), border = 0xFFFECACA.toInt())
                title.text = "오적재 조치 요청 ${count}건"
                title.setTextColor(0xFFB91C1C.toInt())
                content.setTextColor(0xFF7F1D1D.toInt())
                content.text = open.firstOrNull()?.let {
                    "${if (it.isTest) "[테스트 실사] " else "[AI 실사] "}${it.warehouse} · ${it.location}\n기준 ${it.expectedItemName} / 사진 ${it.observedItemName}\n터치하여 조치하기"
                } ?: legacy.firstOrNull()?.let { "${it.itemName} · ${it.location}\n터치하여 조치하기" }
            }
            waiting > 0 -> {
                card.background = WarehouseAppearance.shape(this, 0xFFFFFBEB.toInt(), border = 0xFFFDE68A.toInt())
                title.text = "검수자 재확인 대기 ${waiting}건"
                title.setTextColor(0xFFB45309.toInt())
                content.setTextColor(WarehouseAppearance.MUTED)
                content.text = "조치 결과를 회신했습니다. 검수자의 확인을 기다리고 있습니다."
            }
            else -> {
                card.background = WarehouseAppearance.shape(this, WarehouseAppearance.TEAL_LIGHT, border = 0xFF99F6E4.toInt())
                title.text = if (alertProblem != null) "알림 조회 확인 필요" else "처리할 오적재 요청 없음"
                title.setTextColor(WarehouseAppearance.TEAL)
                content.setTextColor(WarehouseAppearance.MUTED)
                content.text = alertProblem ?: "현재 접수된 오적재 조치 요청이 없습니다."
            }
        }
        card.setOnClickListener { startActivity(Intent(this, AnomalyInventory::class.java)) }
    }
}
