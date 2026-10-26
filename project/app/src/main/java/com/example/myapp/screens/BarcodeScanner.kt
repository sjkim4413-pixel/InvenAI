package com.example.myapp
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import android.widget.*
import android.graphics.Paint
import com.bumptech.glide.Glide
class BarcodeScanner : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_barcode_scanner)
		findViewById<View>(R.id.r2dw5neub3vq)?.visibility = View.GONE
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/4g5fvela_expires_30_days.png").into(findViewById(R.id.rvhf4gydald))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/xspowcky_expires_30_days.png").into(findViewById(R.id.r59zx6vv10kf))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/p6wb0j9e_expires_30_days.png").into(findViewById(R.id.r59l3mzf1vkl))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/265tju5w_expires_30_days.png").into(findViewById(R.id.r0276uwguq9bt))
		// 상단 뒤로가기 버튼
		findViewById<View>(R.id.rvhf4gydald)?.setOnClickListener { finish() }

		val button1: View = findViewById(R.id.r34ewyb3xwz4)
		button1.setOnClickListener {
			println("Pressed")
		}
		val button2: View = findViewById(R.id.ratq18nw1ps)
		button2.setOnClickListener {
			println("Pressed")
		}
		val button3: View = findViewById(R.id.r1llh6arg7iy)
		button3.setOnClickListener {
			println("Pressed")
		}
		val button4: View = findViewById(R.id.rxq1d7hgy5c)
		button4.setOnClickListener {
			println("Pressed")
		}
		findViewById<TextView>(R.id.robivtxvzpo).paintFlags = Paint.UNDERLINE_TEXT_FLAG
	}
}