package com.example.myapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import com.bumptech.glide.Glide

class Inbound : AppCompatActivity() {
	private var editTextValue1: String = ""
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_inbound)
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/6xpw5puz_expires_30_days.png").into(findViewById(R.id.r2z29mgruyhb))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/8bmodns0_expires_30_days.png").into(findViewById(R.id.rz06v3snjqw))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/f6027umo_expires_30_days.png").into(findViewById(R.id.rz17npri7jm))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/p7etgoqn_expires_30_days.png").into(findViewById(R.id.r9vqbrvdq3ns))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/2g5xjdzd_expires_30_days.png").into(findViewById(R.id.r5fn4vu8h1zv))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/t5a5ea4r_expires_30_days.png").into(findViewById(R.id.r5brvw8zo9fb))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/22vgv3xf_expires_30_days.png").into(findViewById(R.id.r7cgzthr5y5s))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/5guhpod6_expires_30_days.png").into(findViewById(R.id.rk8n6y9k570b))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/d5uup5ix_expires_30_days.png").into(findViewById(R.id.rwd9isb2oxqe))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/d2334nnp_expires_30_days.png").into(findViewById(R.id.r46gl7w0vf2j))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/exmdfbsn_expires_30_days.png").into(findViewById(R.id.rsf672vle6zd))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/nasp7wvc_expires_30_days.png").into(findViewById(R.id.r8gqcrdwn809))

		val editText1: EditText = findViewById(R.id.r9d214a21z9)
		editText1.addTextChangedListener(object : TextWatcher {
			override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
			override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
				editTextValue1 = s.toString()
			}
			override fun afterTextChanged(s: Editable?) {}
		})

		// 상단 뒤로가기 버튼
		findViewById<View>(R.id.btn_inbound_back)?.setOnClickListener { finish() }

		// 하단 네비게이션 바
		// 1. 홈
		findViewById<View>(R.id.rk8n6y9k570b)?.setOnClickListener {
			val intent = Intent(this, HomeDashboard::class.java)
			intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
			startActivity(intent)
			finish()
		}
		// 2. 재고
		findViewById<View>(R.id.r14gqtmenhf1e)?.setOnClickListener {
			startActivity(Intent(this, InventoryList::class.java))
		}
		// 3. 스캔
		val openScanner = View.OnClickListener {
			startActivity(Intent(this, BarcodeScanner::class.java))
		}
		findViewById<View>(R.id.r8gqcrdwn809)?.setOnClickListener(openScanner)
		findViewById<View>(R.id.rgomt01hrsm)?.setOnClickListener(openScanner)

		// 4. 작업
		findViewById<View>(R.id.r0raiyfo8vnl)?.setOnClickListener {
			startActivity(Intent(this, WorkHistory::class.java))
		}
		// 5. 더보기 (드롭바/바텀시트 메뉴)
		findViewById<View>(R.id.rovr3324r3q)?.setOnClickListener {
			MenuHelper.showMoreMenu(this)
		}

		val button1: View? = findViewById(R.id.r6kcaduuock3)
		button1?.setOnClickListener { finish() }
		val button2: View? = findViewById(R.id.r44de9a9hkn)
		button2?.setOnClickListener { finish() }
		val button3: View? = findViewById(R.id.rzf39icr5vo)
		button3?.setOnClickListener { finish() }
	}
}