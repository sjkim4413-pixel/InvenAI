package com.example.myapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import android.widget.*
import android.graphics.Paint
import com.bumptech.glide.Glide

class ErpWmsConnection : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_erp_wms_connection)
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/5npv6fq1_expires_30_days.png").into(findViewById(R.id.r19yzeoke5nu))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/iyolqgmj_expires_30_days.png").into(findViewById(R.id.r2lp9y0mkyrk))

		// 상단 뒤로가기 버튼
		findViewById<View>(R.id.r2lp9y0mkyrk)?.setOnClickListener { finish() }

		val button1: View? = findViewById(R.id.r0gl8xlzc7ajj)
		button1?.setOnClickListener {
			startActivity(Intent(this, DataConnectionSelect::class.java))
		}
		val button2: View? = findViewById(R.id.rd0uqcl7pnu9)
		button2?.setOnClickListener {
			startActivity(Intent(this, DataConnectionSelect::class.java))
		}
		val button3: View? = findViewById(R.id.rekrkz67ppw)
		button3?.setOnClickListener {
			startActivity(Intent(this, DataConnectionSelect::class.java))
		}
		val button4: View? = findViewById(R.id.rhxgafg3j5yk)
		button4?.setOnClickListener {
			startActivity(Intent(this, ConnectionProgress::class.java))
		}
		findViewById<TextView>(R.id.rpgdybouru5g).paintFlags = Paint.UNDERLINE_TEXT_FLAG
	}
}