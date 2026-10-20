
package com.example.myapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide

class AuditStart : AppCompatActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		setContentView(R.layout.activity_audit_start)

		// 기존 화면 이미지
		val baseUrl =
			"https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/"

		val images = listOf(
			R.id.rov44kvu00s to "duxv9i3h_expires_30_days.png",
			R.id.r96weuqy0tyj to "lyyd6k3q_expires_30_days.png",
			R.id.ry0z6jgfecc9 to "ovrqq02f_expires_30_days.png",
			R.id.ry0qbvc6ckr to "dqyrj138_expires_30_days.png",
			R.id.rlis7tw39f7b to "hsimni44_expires_30_days.png",
			R.id.rbpi51zl9dq to "nvldjhp1_expires_30_days.png",
			R.id.rnzddmx1gmt to "6nbtlxxd_expires_30_days.png",
			R.id.rrvyode0kr7 to "rl1lf336_expires_30_days.png",
			R.id.rbacjdf7m65n to "8pk2yyg6_expires_30_days.png"
		)

		for ((viewId, fileName) in images) {
			val imageView =
				findViewById<android.widget.ImageView>(viewId)

			if (imageView != null) {
				Glide.with(this)
					.load(baseUrl + fileName)
					.into(imageView)
			}
		}

		// 상단 뒤로가기
		findViewById<View>(R.id.r96weuqy0tyj)
			?.setOnClickListener {
				finish()
			}

		// 하단 메뉴 1. 홈
		findViewById<View>(R.id.rlis7tw39f7b)
			?.setOnClickListener {
				val intent =
					Intent(this, HomeDashboard::class.java)

				intent.flags =
					Intent.FLAG_ACTIVITY_CLEAR_TOP or
							Intent.FLAG_ACTIVITY_SINGLE_TOP

				startActivity(intent)
				finish()
			}

		// 하단 메뉴 2. 재고
		findViewById<View>(R.id.rfgttzwpdgsv)
			?.setOnClickListener {
				startActivity(
					Intent(this, InventoryList::class.java)
				)
			}

		// 하단 메뉴 3. 스캔
		val openScanner = View.OnClickListener {
			startActivity(
				Intent(this, BarcodeScanner::class.java)
			)
		}

		findViewById<View>(R.id.rbacjdf7m65n)
			?.setOnClickListener(openScanner)

		findViewById<View>(R.id.rygg1q1smwrl)
			?.setOnClickListener(openScanner)

		// 하단 메뉴 4. 작업
		findViewById<View>(R.id.r9lvydb052fq)
			?.setOnClickListener {
				startActivity(
					Intent(this, WorkHistory::class.java)
				)
			}

		// 하단 메뉴 5. 더보기
		findViewById<View>(R.id.r3sd4rh47xqh)
			?.setOnClickListener {
				MenuHelper.showMoreMenu(this)
			}

		// F-02 카메라로 실사 시작 버튼
		// XML에서 확인한 실제 버튼 ID
		findViewById<View>(R.id.roxu0u1g02kd)
			?.setOnClickListener {
				startActivity(
					Intent(this, AiAuditCamera::class.java)
				)
			}
	}
}
