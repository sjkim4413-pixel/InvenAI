package com.example.myapp
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import com.bumptech.glide.Glide
class DataConnectionSelect : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_data_connection_select)
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/9vc7cuej_expires_30_days.png").into(findViewById(R.id.rysy69cr6zkl))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/fw2w81ix_expires_30_days.png").into(findViewById(R.id.r1yn65w9hyuj))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/fnplkbc5_expires_30_days.png").into(findViewById(R.id.reggl6xgmu86))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/c4y8101a_expires_30_days.png").into(findViewById(R.id.rz2rpz4hrupr))
	}
}