package com.example.myapp
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import android.widget.*
import android.graphics.Paint
import com.bumptech.glide.Glide
class FileUpload : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_file_upload)
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/ittqmvj2_expires_30_days.png").into(findViewById(R.id.r84rxy9d98ma))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/8akwhoo6_expires_30_days.png").into(findViewById(R.id.r0thmbwfj7ef))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/ip4gf7aw_expires_30_days.png").into(findViewById(R.id.rawwjbromlm5))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/vdz009rr_expires_30_days.png").into(findViewById(R.id.rv4fctmjq6es))
		val button1: View = findViewById(R.id.r4vksl7hg0mr)
		button1.setOnClickListener {
			println("Pressed")
		}
		findViewById<TextView>(R.id.rqm5rjjr74rf).paintFlags = Paint.UNDERLINE_TEXT_FLAG
	}
}