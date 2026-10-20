package com.example.myapp
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import com.bumptech.glide.Glide

class HomeDashboard : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_home_dashboard)
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
		// 1. 홈 버튼
		findViewById<View>(R.id.rhm1gom7f5ja)?.setOnClickListener {
			// 현재 메인 홈 화면
		}
		// 2. 재고 버튼
		findViewById<View>(R.id.rs08jcmj2xlm)?.setOnClickListener {
			startActivity(Intent(this, InventoryList::class.java))
		}
		// 3. 스캔 버튼 (중앙 플로팅 버튼 및 텍스트)
		val openScanner = View.OnClickListener {
			startActivity(Intent(this, BarcodeScanner::class.java))
		}
		findViewById<View>(R.id.rz27s8jtbjwo)?.setOnClickListener(openScanner)
		findViewById<View>(R.id.rdkzl3gxfayl)?.setOnClickListener(openScanner)

		// 4. 작업 버튼
		findViewById<View>(R.id.rajx13mscno4)?.setOnClickListener {
			startActivity(Intent(this, WorkHistory::class.java))
		}
		// 5. 더보기 버튼 (드롭바/바텀시트 메뉴)
		findViewById<View>(R.id.r46r7crd2blu)?.setOnClickListener {
			MenuHelper.showMoreMenu(this)
		}
	}
}