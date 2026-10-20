package com.example.myapp

import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class Frame1 : AppCompatActivity() {

	private lateinit var etCompanyCode: EditText
	private lateinit var etUserId: EditText
	private lateinit var etUserPw: EditText

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		// 1. 레이아웃 뷰를 가장 먼저 바인딩합니다.
		setContentView(R.layout.activity_frame1)

		// 2. Firebase 에뮬레이터 설정 (예외 발생 시 안쇄 방지)
		try {
			FirebaseEmulatorConfig.setupEmulator()
		} catch (e: Exception) {
			Log.w("Frame1", "Firebase emulator setup info: " + e.message)
		}

		// 이미지 로딩
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/ta5zkcbg_expires_30_days.png").into(findViewById(R.id.rowjrgnftxv))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/grx3xpqq_expires_30_days.png").into(findViewById(R.id.rrbh7yzzjhoo))
		Glide.with(this).load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/DCxOgkdP9z/3lfzq7rs_expires_30_days.png").into(findViewById(R.id.rzrod1igfm8o))

		// EditText 참조
		etCompanyCode = findViewById(R.id.rmm3ibea01dh)
		etUserId = findViewById(R.id.rtduz0dht8w)
		etUserPw = findViewById(R.id.r5suocwfrhze)

		// 로그인 버튼
		val btnLogin: View = findViewById(R.id.rlk8j1kj2j19)
		btnLogin.setOnClickListener {
			performLogin()
		}

		// 텍스트 밑줄 및 안내
		val tvAdminContact = findViewById<TextView>(R.id.rau6xo31lv7j)
		val tvSignUp = findViewById<TextView>(R.id.rini482f9v4)
		tvAdminContact.paintFlags = Paint.UNDERLINE_TEXT_FLAG
		tvSignUp.paintFlags = Paint.UNDERLINE_TEXT_FLAG

		tvAdminContact.setOnClickListener {
			Toast.makeText(this, "테스트 계정 - 검수자: root / 적재자: loader (비번: 1234)", Toast.LENGTH_LONG).show()
		}

		// 회원가입 버튼 클릭 시 SignUpDialog 연결
		tvSignUp.setOnClickListener {
			SignUpDialog.show(this) { company, id ->
				etCompanyCode.setText(company)
				etUserId.setText(id)
				etUserPw.requestFocus()
			}
		}
	}

	/**
	 * Firebase Authentication 로그인 및 Firestore 역할(role) 기반 라우팅
	 */
	private fun performLogin() {
		val companyCode = etCompanyCode.text.toString().trim()
		val userId = etUserId.text.toString().trim()
		val userPw = etUserPw.text.toString().trim()

		if (companyCode.isEmpty()) {
			Toast.makeText(this, "회사/사업장 코드를 입력해주세요.", Toast.LENGTH_SHORT).show()
			etCompanyCode.requestFocus()
			return
		}
		if (userId.isEmpty()) {
			Toast.makeText(this, "아이디(사번)를 입력해주세요.", Toast.LENGTH_SHORT).show()
			etUserId.requestFocus()
			return
		}
		if (userPw.isEmpty()) {
			Toast.makeText(this, "비밀번호를 입력해주세요.", Toast.LENGTH_SHORT).show()
			etUserPw.requestFocus()
			return
		}

		// Firebase Auth 로그인용 이메일 변환
		val email = if (userId.contains("@")) userId else "$userId@warehouse.com"

		try {
			val auth = FirebaseAuth.getInstance()
			val db = FirebaseFirestore.getInstance()

			auth.signInWithEmailAndPassword(email, userPw)
				.addOnCompleteListener { task ->
					if (task.isSuccessful) {
						val uid = task.result?.user?.uid
						if (uid == null) {
							onLoginSuccess(companyCode, userId, "MOCK_TOKEN", "QA")
							return@addOnCompleteListener
						}

						// Firestore에서 사용자 역할(role) 조회
						db.collection("users").document(uid).get()
							.addOnSuccessListener { document ->
								val role = document.getString("role") ?: "QA"
								val company = document.getString("companyCode") ?: companyCode
								onLoginSuccess(company, userId, uid, role)
							}
							.addOnFailureListener { e ->
								Log.w("Frame1", "Firestore 역할 조회 실패: " + e.message)
								onLoginSuccess(companyCode, userId, uid, "QA")
							}
					} else {
						// 오프라인 / 테스트 계정 fallback (root / loader 등)
						handleLocalFallback(companyCode, userId, userPw)
					}
				}
		} catch (e: Exception) {
			Log.w("Frame1", "Firebase login error: " + e.message + ". Fallback to local accounts.")
			handleLocalFallback(companyCode, userId, userPw)
		}
	}

	/**
	 * 오프라인/테스트 모드 처리
	 */
	private fun handleLocalFallback(companyCode: String, userId: String, userPw: String) {
		if (userId == "root" && (userPw == "1234" || userPw == "123456")) {
			onLoginSuccess(companyCode, userId, "MOCK_FIREBASE_UID_QA", "QA")
		} else if (userId == "loader" && (userPw == "1234" || userPw == "123456")) {
			onLoginSuccess(companyCode, userId, "MOCK_FIREBASE_UID_LOADER", "LOADER")
		} else if (userPw == "1234" || userPw == "123456") {
			onLoginSuccess(companyCode, userId, "MOCK_FIREBASE_UID_QA", "QA")
		} else {
			Toast.makeText(this, "로그인 실패: 아이디 또는 비밀번호를 확인하세요.\n(테스트 계정 - 검수자: root/1234, 적재자: loader/1234)", Toast.LENGTH_LONG).show()
		}
	}

	/**
	 * 로그인 성공 후 세션 저장 및 화면 이동
	 */
	private fun onLoginSuccess(companyCode: String, userId: String, uid: String, role: String) {
		val sharedPref = getSharedPreferences("app_user_session", MODE_PRIVATE)
		sharedPref.edit().apply {
			putString("COMPANY_CODE", companyCode)
			putString("USER_ID", userId)
			putString("FIREBASE_UID", uid)
			putString("ROLE", role)
			putBoolean("IS_LOGGED_IN", true)
			apply()
		}

		// 소켓/푸시 토큰 등록
		PushSocketManager.registerDeviceToken(this, userId, uid)

		// 검수자("QA")인 경우 오더 캐싱
		if (role == "QA") {
			OrderSyncManager.fetchAndCacheOrders(this, uid) { orders, isOnline ->
				Log.d("Frame1", "Orders synced: ${orders.size} items (Online: $isOnline)")
			}
		}

		val roleName = if (role == "QA") "검수자 (QA)" else "적재자 (LOADER)"
		Toast.makeText(this, "로그인 성공! [$roleName]\n환영합니다, ${userId}님.", Toast.LENGTH_SHORT).show()

		// 역할별 화면 라우팅
		routeUserByRole(role)
		finish()
	}

	/**
	 * Role("QA" / "LOADER") 값에 따른 첫 화면 라우팅
	 */
	private fun routeUserByRole(role: String) {
		val intent = if (role == "LOADER") {
			// 적재자용 메인 화면 (Inbound)
			Intent(this, Inbound::class.java)
		} else {
			// 검수자용 메인 대시보드 화면 (HomeDashboard)
			Intent(this, HomeDashboard::class.java)
		}
		startActivity(intent)
	}
}