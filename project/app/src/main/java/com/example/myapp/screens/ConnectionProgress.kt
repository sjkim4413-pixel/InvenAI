package com.example.myapp
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import com.bumptech.glide.Glide
class ConnectionProgress : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_connection_progress)
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/mm6029m8_expires_30_days.png").into(findViewById(R.id.rpie2z6g8rx))
		val button1: View = findViewById(R.id.rjkat8j124h)
		button1.setOnClickListener {
			println("Pressed")
		}
	}
}