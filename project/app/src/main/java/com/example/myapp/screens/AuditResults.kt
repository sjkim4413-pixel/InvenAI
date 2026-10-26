package com.example.myapp
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import com.bumptech.glide.Glide
class AuditResults : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_audit_results)
		findViewById<View>(R.id.ryp73yew161)?.visibility = View.GONE
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/tl76mav5_expires_30_days.png").into(findViewById(R.id.rybu9wsnunwh))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/jilgd5g3_expires_30_days.png").into(findViewById(R.id.rg161sziuouk))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/wk4ctkmq_expires_30_days.png").into(findViewById(R.id.rzf7ualwd41o))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/yy4pb8hp_expires_30_days.png").into(findViewById(R.id.r0y1kp13k5c0p))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/d1sh6on0_expires_30_days.png").into(findViewById(R.id.rckkdgtr23qk))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/aoej4vkb_expires_30_days.png").into(findViewById(R.id.r2dhwjvzwpj))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/ie5y63fh_expires_30_days.png").into(findViewById(R.id.rp00bozyv6sq))
		val button1: View = findViewById(R.id.rlpcxivvxlkf)
		button1.setOnClickListener {
			println("Pressed")
		}
		val button2: View = findViewById(R.id.r048oo1t9va7k)
		button2.setOnClickListener {
			println("Pressed")
		}
		val button3: View = findViewById(R.id.r28g570vah4c)
		button3.setOnClickListener {
			println("Pressed")
		}
	}
}