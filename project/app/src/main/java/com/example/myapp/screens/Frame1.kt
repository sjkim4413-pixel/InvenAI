
package com.example.myapp

import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.net.HttpURLConnection
import java.net.URL

class Frame1 : AppCompatActivity() {

	private lateinit var etCompanyCode: EditText
	private lateinit var etUserId: EditText
	private lateinit var etUserPw: EditText

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		setContentView(R.layout.activity_frame1)

		// Firebase 에뮬레이터 설정
		try {
			FirebaseEmulatorConfig.setupEmulator()
		} catch (e: Exception) {
			Log.w("Frame1", "Firebase emulator setup info: ${e.message}")
		}

		// 이미지 로딩
		findViewById<View>(R.id.rowjrgnftxv)?.visibility = View.GONE



		// 입력창 연결
		etCompanyCode = findViewById(R.id.rmm3ibea01dh)
		etUserId = findViewById(R.id.rtduz0dht8w)
		etUserPw = findViewById(R.id.r5suocwfrhze)

		// 비밀번호 표시 / 숨기기
		val ivPasswordToggle =
			findViewById<View>(R.id.rzrod1igfm8o)

		var isPasswordVisible = false

		ivPasswordToggle?.setOnClickListener {
			isPasswordVisible = !isPasswordVisible

			val selection = etUserPw.selectionEnd

			if (isPasswordVisible) {
				etUserPw.transformationMethod =
					android.text.method.HideReturnsTransformationMethod.getInstance()
			} else {
				etUserPw.transformationMethod =
					android.text.method.PasswordTransformationMethod.getInstance()
			}

			etUserPw.setSelection(selection)
		}

		// 로그인 버튼
		val btnLogin: View =
			findViewById(R.id.rlk8j1kj2j19)

		btnLogin.setOnClickListener {
			performLogin()
		}

		// 관리자 문의 및 회원가입
		val tvAdminContact =
			findViewById<TextView>(R.id.rau6xo31lv7j)

		val tvSignUp =
			findViewById<TextView>(R.id.rini482f9v4)

		tvAdminContact.paintFlags = Paint.UNDERLINE_TEXT_FLAG
		tvSignUp.paintFlags = Paint.UNDERLINE_TEXT_FLAG

		tvAdminContact.setOnClickListener {
			showAdminContactDialog()
		}

		tvSignUp.setOnClickListener {
			SignUpDialog.show(this) { company, id ->
				etCompanyCode.setText(company)
				etUserId.setText(id)
				etUserPw.requestFocus()
			}
		}
	}

	private fun showAdminContactDialog() {

		val layout = android.widget.LinearLayout(this).apply {
			orientation = android.widget.LinearLayout.VERTICAL
			setPadding(50, 40, 50, 10)
		}

		val etSubject = EditText(this).apply {
			hint = "문의 제목 (예: 로그인 계정 잠김 / 권한 오류)"
			inputType = android.text.InputType.TYPE_CLASS_TEXT
			privateImeOptions = "defaultInputmode=korean"
		}

		val etBody = EditText(this).apply {
			hint = "문의 내용을 상세히 적어주세요."
			inputType =
				android.text.InputType.TYPE_CLASS_TEXT or
						android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
			minLines = 4
			gravity =
				android.view.Gravity.TOP or
						android.view.Gravity.START
			privateImeOptions = "defaultInputmode=korean"
		}

		layout.addView(etSubject)
		layout.addView(etBody)

		AlertDialog.Builder(this)
			.setTitle("📧 관리자에게 문의 메일 보내기")
			.setMessage(
				"시스템 관리자(admin@warehouse.com)에게 문의 사항을 전달합니다."
			)
			.setView(layout)
			.setPositiveButton("메일 발송") { _, _ ->

				val subject = etSubject.text.toString().trim()
				val body = etBody.text.toString().trim()

				if (subject.isEmpty()) {
					Toast.makeText(
						this,
						"문의 제목을 입력해주세요.",
						Toast.LENGTH_SHORT
					).show()
					return@setPositiveButton
				}

				try {
					val emailIntent =
						Intent(Intent.ACTION_SENDTO).apply {
							data = android.net.Uri.parse(
								"mailto:admin@warehouse.com"
							)
							putExtra(
								Intent.EXTRA_SUBJECT,
								"[WMS 물류 문의] $subject"
							)
							putExtra(
								Intent.EXTRA_TEXT,
								body
							)
						}

					if (emailIntent.resolveActivity(packageManager) != null) {
						startActivity(emailIntent)
					} else {
						Toast.makeText(
							this,
							"메일 앱을 찾을 수 없습니다.",
							Toast.LENGTH_LONG
						).show()
					}
				} catch (e: Exception) {
					Log.e("Frame1", "Mail app error", e)
					Toast.makeText(
						this,
						"메일 앱을 실행하지 못했습니다.",
						Toast.LENGTH_LONG
					).show()
				}
			}
			.setNegativeButton("취소", null)
			.show()
	}

	// Firebase Authentication 로그인
	private fun performLogin() {

		val companyCode =
			etCompanyCode.text.toString().trim().uppercase()

		val userId =
			etUserId.text.toString().trim()

		val userPw =
			etUserPw.text.toString().trim()

		if (companyCode.isEmpty()) {
			Toast.makeText(
				this,
				"회사/사업장 코드를 입력해주세요.",
				Toast.LENGTH_SHORT
			).show()
			etCompanyCode.requestFocus()
			return
		}

		if (userId.isEmpty()) {
			Toast.makeText(
				this,
				"아이디(사번)를 입력해주세요.",
				Toast.LENGTH_SHORT
			).show()
			etUserId.requestFocus()
			return
		}

		if (userPw.isEmpty()) {
			Toast.makeText(
				this,
				"비밀번호를 입력해주세요.",
				Toast.LENGTH_SHORT
			).show()
			etUserPw.requestFocus()
			return
		}

		val email =
			if (userId.contains("@")) {
				userId
			} else {
				"$userId@warehouse.com"
			}

		try {
			val auth = FirebaseAuth.getInstance()
			val db = FirebaseFirestore.getInstance()

			auth.signInWithEmailAndPassword(email, userPw)
				.addOnCompleteListener { task ->

					if (task.isSuccessful) {

						Log.d(
							"IVENAI_AUTH_TEST",
							"FIREBASE_LOGIN_SUCCESS"
						)

						val uid = task.result?.user?.uid

						// Firebase ID 토큰 발급 및 서버 인증 테스트
						auth.currentUser?.getIdToken(false)
							?.addOnSuccessListener { result ->

								val token = result.token
								val hasToken = !token.isNullOrBlank()

								Log.d(
									"IVENAI_AUTH_TEST",
									"FIREBASE_ID_TOKEN_READY=$hasToken"
								)

								if (hasToken && token != null) {
									verifyFastApiAuth(token)
								}
							}
							?.addOnFailureListener { error ->
								Log.e(
									"IVENAI_AUTH_TEST",
									"FIREBASE_ID_TOKEN_FAILED",
									error
								)
							}

						if (uid == null) {
							Log.e(
								"IVENAI_AUTH_TEST",
								"FIREBASE_UID_MISSING"
							)
							Toast.makeText(
								this,
								"로그인 사용자 정보를 확인할 수 없습니다.",
								Toast.LENGTH_LONG
							).show()
							return@addOnCompleteListener
						}

						// Firestore에서 사용자 역할 조회
						db.collection("users")
							.document(uid)
							.get()
							.addOnSuccessListener { document ->

								val role =
									document.getString("role") ?: "QA"

								val company =
									document.getString("companyCode")
										?: companyCode

								onLoginSuccess(
									company.uppercase(),
									userId,
									uid,
									role
								)
							}
							.addOnFailureListener { error ->

								Log.w(
									"Frame1",
									"Firestore 역할 조회 실패: ${error.message}"
								)

								onLoginSuccess(
									companyCode,
									userId,
									uid,
									"QA"
								)
							}

					} else {

						Log.e(
							"IVENAI_AUTH_TEST",
							"FIREBASE_LOGIN_FAILED",
							task.exception
						)

						// 기존 테스트 계정 로그인 유지
						handleLocalFallback(
							companyCode,
							userId,
							userPw
						)
					}
				}

		} catch (e: Exception) {

			Log.e(
				"IVENAI_AUTH_TEST",
				"FIREBASE_LOGIN_EXCEPTION",
				e
			)

			handleLocalFallback(
				companyCode,
				userId,
				userPw
			)
		}
	}

	// 실제 Firebase 토큰으로 FastAPI 서버 인증 테스트
	private fun verifyFastApiAuth(token: String) {

		Thread {
			var connection: HttpURLConnection? = null

			try {
				val url = URL(
					"http://10.0.2.2:8083/f04/auth/verify"
				)

				connection =
					url.openConnection() as HttpURLConnection

				connection.requestMethod = "GET"

				connection.setRequestProperty(
					"Authorization",
					"Bearer $token"
				)

				connection.connectTimeout = 5000
				connection.readTimeout = 5000

				val status = connection.responseCode

				Log.d(
					"IVENAI_AUTH_TEST",
					"FASTAPI_AUTH_HTTP_STATUS=$status"
				)

			} catch (e: Exception) {

				Log.e(
					"IVENAI_AUTH_TEST",
					"FASTAPI_AUTH_CONNECTION_FAILED",
					e
				)

			} finally {
				connection?.disconnect()
			}
		}.start()
	}

	// 기존 로컬 테스트 로그인
	// 개발용이며 실제 서비스 출시 전 제거해야 함
	private fun handleLocalFallback(
		companyCode: String,
		userId: String,
		userPw: String
	) {

		val upperCompany = companyCode.uppercase()

		if (
			userId == "root" &&
			(userPw == "1234" || userPw == "123456")
		) {
			onLoginSuccess(
				upperCompany,
				userId,
				"MOCK_FIREBASE_UID_QA",
				"QA"
			)

		} else if (
			userId == "loader" &&
			(userPw == "1234" || userPw == "123456")
		) {
			onLoginSuccess(
				upperCompany,
				userId,
				"MOCK_FIREBASE_UID_LOADER",
				"LOADER"
			)

		} else if (
			userPw == "1234" || userPw == "123456"
		) {
			onLoginSuccess(
				upperCompany,
				userId,
				"MOCK_FIREBASE_UID_QA",
				"QA"
			)

		} else {
			Toast.makeText(
				this,
				"로그인 실패: 아이디 또는 비밀번호를 확인하세요.\n(테스트 계정 - 검수자: root/1234, 적재자: loader/1234)",
				Toast.LENGTH_LONG
			).show()
		}
	}

	// 로그인 성공 후 세션 저장
	private fun onLoginSuccess(
		companyCode: String,
		userId: String,
		uid: String,
		role: String
	) {

		val finalCompanyCode =
			if (companyCode.isBlank()) {
				"ABC-001"
			} else {
				companyCode.uppercase()
			}

		val sharedPref =
			getSharedPreferences(
				"app_user_session",
				MODE_PRIVATE
			)

		sharedPref.edit().apply {
			putString("COMPANY_CODE", finalCompanyCode)
			putString("USER_ID", userId)
			putString("FIREBASE_UID", uid)
			putString("ROLE", role)
			putBoolean("IS_LOGGED_IN", true)
			apply()
		}

		// 소켓 / 푸시 토큰 등록
		PushSocketManager.registerDeviceToken(
			this,
			userId,
			uid
		)

		// 검수자 오더 캐싱
		if (role == "QA") {
			OrderSyncManager.fetchAndCacheOrders(
				this,
				uid
			) { orders, isOnline ->

				Log.d(
					"Frame1",
					"Orders synced: ${orders.size} items (Online: $isOnline)"
				)
			}
		}

		val roleName =
			if (role == "QA") {
				"검수자 (QA)"
			} else {
				"적재자 (LOADER)"
			}

		Toast.makeText(
			this,
			"로그인 성공! [$roleName]\n환영합니다, ${userId}님.",
			Toast.LENGTH_SHORT
		).show()

		routeUserByRole(role)
		finish()
	}

	// 역할별 첫 화면 이동
	private fun routeUserByRole(role: String) {

		val intent =
			if (role == "LOADER") {
				Intent(this, LoaderHomeDashboard::class.java)
			} else {
				Intent(this, HomeDashboard::class.java)
			}

		startActivity(intent)
	}
}
