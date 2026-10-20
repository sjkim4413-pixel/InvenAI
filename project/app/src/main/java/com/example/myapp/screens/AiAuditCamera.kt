package com.example.myapp

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import java.io.ByteArrayOutputStream

class AiAuditCamera : AppCompatActivity() {

	// 안드로이드 디바이스 카메라 Intent 런처
	private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
		if (bitmap != null) {
			val intent = Intent(this, AiAuditResults::class.java)
			val stream = ByteArrayOutputStream()
			bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
			val byteArray = stream.toByteArray()
			intent.putExtra("CAPTURED_PHOTO", byteArray)
			startActivity(intent)
			finish()
		} else {
			Toast.makeText(this, "사진 촬영이 취소되었습니다. (샘플 결과로 연동)", Toast.LENGTH_SHORT).show()
			startActivity(Intent(this, AiAuditResults::class.java))
			finish()
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_ai_audit_camera)

		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/9e3p4of1_expires_30_days.png").into(findViewById(R.id.ritt5ng5o198))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/gbcu6atm_expires_30_days.png").into(findViewById(R.id.r15dwcp3t57c))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/4lciicxp_expires_30_days.png").into(findViewById(R.id.re9v2i5j5okr))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/69d7qhmf_expires_30_days.png").into(findViewById(R.id.rs7jq8zl0qw))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/t0mm0pnj_expires_30_days.png").into(findViewById(R.id.rxsy19v2hpcm))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/911go06g_expires_30_days.png").into(findViewById(R.id.r8mh6pjleq5s))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/9x7sgbjh_expires_30_days.png").into(findViewById(R.id.r9f1976gvrjv))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/3phvlt17_expires_30_days.png").into(findViewById(R.id.rtk0p0bn1qq))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/cp73up0q_expires_30_days.png").into(findViewById(R.id.ryfcmlkyd0x))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/asrws35g_expires_30_days.png").into(findViewById(R.id.r16363f6acfj))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/h3ngit5y_expires_30_days.png").into(findViewById(R.id.r2jog2g9gibp))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/pajsw497_expires_30_days.png").into(findViewById(R.id.r9ig1u678w9v))

		// 1. 카메라 셔터 버튼 클릭 ➔ 안드로이드 실제 카메라 연동 실행
		val btnShutter: View? = findViewById(R.id.r2u5n27nrh39)
		btnShutter?.setOnClickListener {
			try {
				takePictureLauncher.launch(null)
			} catch (e: Exception) {
				Toast.makeText(this, "카메라를 호출합니다.", Toast.LENGTH_SHORT).show()
				startActivity(Intent(this, AiAuditResults::class.java))
				finish()
			}
		}

		// 2. 상단 닫기/뒤로가기
		findViewById<View>(R.id.ritt5ng5o198)?.setOnClickListener { finish() }
	}
}