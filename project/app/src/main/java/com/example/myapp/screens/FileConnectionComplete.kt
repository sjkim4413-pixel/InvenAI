package com.example.myapp
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import com.bumptech.glide.Glide
class FileConnectionComplete : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_file_connection_complete)
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/jp800dv3_expires_30_days.png").into(findViewById(R.id.rqsd4ahhmpo))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/7mdndo44_expires_30_days.png").into(findViewById(R.id.r0kr0c1ic21pk))
		val button1: View = findViewById(R.id.rpwbp7dk3ie9)
		button1.setOnClickListener {
			println("Pressed")
		}
	}
}