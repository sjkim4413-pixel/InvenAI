package com.example.myapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide

class PhotoConfirmation : AppCompatActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_photo_confirmation)

		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/r18axnwt_expires_30_days.png").into(findViewById(R.id.rgsw0un4ycns))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/m99cfgdk_expires_30_days.png").into(findViewById(R.id.r3zlzs93haff))

		// 상단 뒤로가기
		findViewById<View>(R.id.rgsw0un4ycns)?.setOnClickListener { finish() }

		// [적재 완료 제출] 버튼
		val btnSubmit: View? = findViewById(R.id.r9q20dzoqfq6)
		btnSubmit?.setOnClickListener {
			Toast.makeText(this, "✅ 적재 완료 전송 및 로컬 DB 상태 업데이트 완료!", Toast.LENGTH_SHORT).show()

			val intent = Intent(this, Inbound::class.java)
			intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
			startActivity(intent)
			finish()
		}

		// [재촬영] 버튼
		val btnRetake: View? = findViewById(R.id.r6r7tkf5i87c)
		btnRetake?.setOnClickListener {
			Toast.makeText(this, "사진 재촬영을 진행합니다.", Toast.LENGTH_SHORT).show()
		}
	}
}