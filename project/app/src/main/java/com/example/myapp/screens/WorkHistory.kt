package com.example.myapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import com.bumptech.glide.Glide

class WorkHistory : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_work_history)
		findViewById<View>(R.id.rr145tdsfh3)?.visibility = View.GONE
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/t77czne2_expires_30_days.png").into(findViewById(R.id.r3vtuuy6ybip))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/irtilrwz_expires_30_days.png").into(findViewById(R.id.rx9hjfq8hsit))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/7u67eh1p_expires_30_days.png").into(findViewById(R.id.rb1cli47q0dv))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/t485xuic_expires_30_days.png").into(findViewById(R.id.r6q9ucayn5ik))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/y4lu7uls_expires_30_days.png").into(findViewById(R.id.r8bjmhpf948))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/pvlcfmgt_expires_30_days.png").into(findViewById(R.id.rdi9k35mib1e))
		// 상단 뒤로가기 버튼
		findViewById<View>(R.id.r3vtuuy6ybip)?.setOnClickListener { finish() }

		// 하단 네비게이션 바
		// 1. 홈 버튼
		findViewById<View>(R.id.rx9hjfq8hsit)?.setOnClickListener {
			val intent = Intent(this, HomeDashboard::class.java)
			intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
			startActivity(intent)
			finish()
		}
		// 2. 재고 버튼
		findViewById<View>(R.id.rt6eixtr5xfq)?.setOnClickListener {
			startActivity(Intent(this, InventoryList::class.java))
		}
		// 3. 스캔 버튼
		val openScanner = View.OnClickListener {
			startActivity(Intent(this, BarcodeScanner::class.java))
		}
		findViewById<View>(R.id.rdi9k35mib1e)?.setOnClickListener(openScanner)
		findViewById<View>(R.id.rvd9yojqzrd)?.setOnClickListener(openScanner)

		// 4. 더보기 버튼 (드롭바/바텀시트 메뉴)
		findViewById<View>(R.id.ri1h4l2mq8le)?.setOnClickListener {
			MenuHelper.showMoreMenu(this)
		}

		val button1: View = findViewById(R.id.rl8l7xwqr73h)
		button1.setOnClickListener {
			println("Pressed")
		}
		val button2: View = findViewById(R.id.rp0292p45z8)
		button2.setOnClickListener {
			println("Pressed")
		}
		val button3: View = findViewById(R.id.rslr8fzl8d2)
		button3.setOnClickListener {
			println("Pressed")
		}
		val button4: View = findViewById(R.id.rqjrg8o1p23)
		button4.setOnClickListener {
			println("Pressed")
		}
		val button5: View = findViewById(R.id.r05vk5nfpo0uf)
		button5.setOnClickListener {
			println("Pressed")
		}
		val button6: View = findViewById(R.id.ru5zhhlqzlk8)
		button6.setOnClickListener {
			println("Pressed")
		}
	}
}