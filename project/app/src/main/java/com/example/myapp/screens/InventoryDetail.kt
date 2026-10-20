package com.example.myapp
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import com.bumptech.glide.Glide
class InventoryDetail : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_inventory_detail)
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/203wyjsf_expires_30_days.png").into(findViewById(R.id.ruk2l6hsjuh))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/6dzl2y2j_expires_30_days.png").into(findViewById(R.id.rlb7iitjpcch))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/bb3mu2es_expires_30_days.png").into(findViewById(R.id.r1vk1k6pain8j))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/6em8cmgw_expires_30_days.png").into(findViewById(R.id.rrbtmji7ayxp))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/ktgsdqrp_expires_30_days.png").into(findViewById(R.id.rf958hd6doy))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/wdnvw2ut_expires_30_days.png").into(findViewById(R.id.r74nucvcdsxa))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/23nnyjuu_expires_30_days.png").into(findViewById(R.id.rdnv3fthexra))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/mqzyo825_expires_30_days.png").into(findViewById(R.id.rel7zzvm5tt9))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/q9x50ewe_expires_30_days.png").into(findViewById(R.id.rfr3dgqydvgt))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/2w56ktx0_expires_30_days.png").into(findViewById(R.id.rjou5xvfm1e))
		val button1: View = findViewById(R.id.rl1mtyqbucc)
		button1.setOnClickListener {
			println("Pressed")
		}
		val button2: View = findViewById(R.id.r2ocdj4k8cly)
		button2.setOnClickListener {
			println("Pressed")
		}
		val button3: View = findViewById(R.id.r4l9zg4vojz2)
		button3.setOnClickListener {
			println("Pressed")
		}
	}
}