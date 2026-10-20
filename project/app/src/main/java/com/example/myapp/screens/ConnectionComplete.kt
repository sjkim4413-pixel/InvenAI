package com.example.myapp
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import com.bumptech.glide.Glide
class ConnectionComplete : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_connection_complete)
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/4kr6y2o8_expires_30_days.png").into(findViewById(R.id.rmshct9d6oos))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/vkmq281s_expires_30_days.png").into(findViewById(R.id.rojred0csw3i))
		val button1: View = findViewById(R.id.r8j0r1o729at)
		button1.setOnClickListener {
			println("Pressed")
		}
	}
}