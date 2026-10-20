package com.example.myapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import com.bumptech.glide.Glide

class InventoryList : AppCompatActivity() {
	private var editTextValue1: String = ""
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_inventory_list)
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/4jibd1jn_expires_30_days.png").into(findViewById(R.id.rmkfwrpy51p))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/ke7kqco6_expires_30_days.png").into(findViewById(R.id.rp3vtwhd9m2q))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/e9oja0k5_expires_30_days.png").into(findViewById(R.id.rlyrs66q8nar))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/xzowdvcj_expires_30_days.png").into(findViewById(R.id.rngizvr5lan))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/rihbigz2_expires_30_days.png").into(findViewById(R.id.rwz8a4fzzaxp))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/npvgd8oj_expires_30_days.png").into(findViewById(R.id.r288wmetab16))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/8j9xu5hh_expires_30_days.png").into(findViewById(R.id.rnlw8su6veg))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/5aod9zot_expires_30_days.png").into(findViewById(R.id.rjqoym9k7e6c))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/w0fng0rw_expires_30_days.png").into(findViewById(R.id.rk3okepp5ox))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/ct5z84en_expires_30_days.png").into(findViewById(R.id.rxq6bb0gaxh))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/bnrv32vk_expires_30_days.png").into(findViewById(R.id.rhk837un2nsl))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/mchitgku_expires_30_days.png").into(findViewById(R.id.rt87e4hkgdz))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/hzpl9gub_expires_30_days.png").into(findViewById(R.id.rbuk0ip58a9q))
		val editText1: EditText = findViewById(R.id.rzgh3zmtlhx)
		editText1.addTextChangedListener(object : TextWatcher {
			override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
				// before Text Changed
			}
			override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
				editTextValue1 = s.toString()  // on Text Changed
			}
			override fun afterTextChanged(s: Editable?) {
				// after Text Changed
			}
		})
		// 상단 뒤로가기 버튼
		findViewById<View>(R.id.btn_inventory_list_back)?.setOnClickListener { finish() }
		findViewById<View>(R.id.rp3vtwhd9m2q)?.setOnClickListener { finish() }

		// 하단 네비게이션 바 버튼 연결
		// 1. 홈 버튼
		findViewById<View>(R.id.rk3okepp5ox)?.setOnClickListener {
			val intent = Intent(this, HomeDashboard::class.java)
			intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
			startActivity(intent)
			finish()
		}
		// 2. 스캔 버튼
		val openScanner = View.OnClickListener {
			startActivity(Intent(this, BarcodeScanner::class.java))
		}
		findViewById<View>(R.id.rbuk0ip58a9q)?.setOnClickListener(openScanner)
		findViewById<View>(R.id.rklc2x3n6g)?.setOnClickListener(openScanner)

		// 3. 작업 버튼
		findViewById<View>(R.id.rjqojvr7z0xr)?.setOnClickListener {
			startActivity(Intent(this, WorkHistory::class.java))
		}
		// 4. 더보기 버튼 (드롭바/바텀시트 메뉴)
		findViewById<View>(R.id.rnw6p4q6bcle)?.setOnClickListener {
			MenuHelper.showMoreMenu(this)
		}

		val button1: View? = findViewById(R.id.rcsp34d582jh)
		button1?.setOnClickListener {
			startActivity(Intent(this, InventoryDetail::class.java))
		}
		val button2: View? = findViewById(R.id.ra0ithaorus4)
		button2?.setOnClickListener {
			startActivity(Intent(this, InventoryDetail::class.java))
		}
		val button3: View? = findViewById(R.id.revxqonyr1am)
		button3?.setOnClickListener {
			startActivity(Intent(this, InventoryDetail::class.java))
		}
		val button4: View? = findViewById(R.id.r7osm9qpd5he)
		button4?.setOnClickListener {
			startActivity(Intent(this, InventoryDetail::class.java))
		}
	}
}