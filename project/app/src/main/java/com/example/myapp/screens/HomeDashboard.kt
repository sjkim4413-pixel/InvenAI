package com.example.myapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.firebase.firestore.ListenerRegistration
import java.text.DecimalFormat

class HomeDashboard : AppCompatActivity() {

	private var inventoryListener: ListenerRegistration? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_home_dashboard)

		// 이미지 로딩
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/ry5hvfbw_expires_30_days.png").into(findViewById(R.id.ry7p7pqvxka))
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
			}
		}
	}
}