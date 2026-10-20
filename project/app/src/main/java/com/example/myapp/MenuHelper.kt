package com.example.myapp

import android.app.Activity
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth

object MenuHelper {
	fun showMoreMenu(activity: Activity) {
		val bottomSheetDialog = BottomSheetDialog(activity)
		val view: View = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_more_menu, null)

		// 1. 프로필 / 계정 정보
		view.findViewById<View>(R.id.item_profile)?.setOnClickListener {
			bottomSheetDialog.dismiss()
			Toast.makeText(activity, "김현수 (제1 물류센터 / 현장 관리자)", Toast.LENGTH_SHORT).show()
		}

		// 2. ERP/WMS 시스템 연동
		view.findViewById<View>(R.id.item_erp)?.setOnClickListener {
			bottomSheetDialog.dismiss()
			activity.startActivity(Intent(activity, ErpWmsConnection::class.java))
		}

		// 3. 시스템 설정
		view.findViewById<View>(R.id.item_settings)?.setOnClickListener {
			bottomSheetDialog.dismiss()
			Toast.makeText(activity, "시스템 설정 메뉴 준비 중입니다.", Toast.LENGTH_SHORT).show()
		}

		// 4. 공지사항 및 고객지원
		view.findViewById<View>(R.id.item_notice)?.setOnClickListener {
			bottomSheetDialog.dismiss()
			Toast.makeText(activity, "최신 공지사항: v2.0 버그 수정 완료", Toast.LENGTH_SHORT).show()
		}

		// 5. 로그아웃
		view.findViewById<View>(R.id.item_logout)?.setOnClickListener {
			bottomSheetDialog.dismiss()
			try {
				FirebaseAuth.getInstance().signOut()
			} catch (e: Exception) {
				// Ignore
			}
			val sharedPref = activity.getSharedPreferences("app_user_session", Activity.MODE_PRIVATE)
			sharedPref.edit().clear().apply()
			Toast.makeText(activity, "로그아웃 되었습니다.", Toast.LENGTH_SHORT).show()

			val intent = Intent(activity, Frame1::class.java)
			intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
			activity.startActivity(intent)
			activity.finish()
		}

		bottomSheetDialog.setContentView(view)
		bottomSheetDialog.show()
	}
}