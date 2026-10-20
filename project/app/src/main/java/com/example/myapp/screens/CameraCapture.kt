package com.example.myapp
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import com.bumptech.glide.Glide
class CameraCapture : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_camera_capture)
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/c66errjr_expires_30_days.png").into(findViewById(R.id.rpnrbwdb3cz))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/hkzk6o5l_expires_30_days.png").into(findViewById(R.id.rv7b2iultky))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/g24fycey_expires_30_days.png").into(findViewById(R.id.r9hxihhz6vy9))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/wpe1saux_expires_30_days.png").into(findViewById(R.id.rv1tgwikyid9))
		val button1: View = findViewById(R.id.rmhc5kxfqz9h)
		button1.setOnClickListener {
			println("Pressed")
		}
	}
}