
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
import com.google.firebase.firestore.Source

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
		etCompanyCode.hint = "회사 코드 (관리자는 생략)"
		etCompanyCode.setText(intent.getStringExtra("COMPANY_CODE").orEmpty())
		etUserId.setText(intent.getStringExtra("USER_ID").orEmpty())

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
		val company = etCompanyCode.text.toString().trim().uppercase()
		val employee = etUserId.text.toString().trim()
		val request = "회사/사업장: $company\n아이디: $employee\n소속 및 QA/LOADER 권한 등록을 확인해 주세요."
		AlertDialog.Builder(this)
			.setTitle("관리자에게 계정 승인 요청")
			.setMessage("소속 회사 관리자에게 아래 정보를 전달해 주세요.\n\n$request")
			.setPositiveButton("요청 내용 복사") { _, _ ->
				val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
				clipboard.setPrimaryClip(android.content.ClipData.newPlainText("계정 승인 요청", request))
			}
			.setNegativeButton("닫기", null)
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

		val auth = FirebaseAuth.getInstance()
		val db = FirebaseFirestore.getInstance()
		val loginButton = findViewById<View>(R.id.rlk8j1kj2j19)
		val signUpLink = findViewById<View>(R.id.rini482f9v4)
		loginButton.isEnabled = false
		signUpLink.isEnabled = false
		auth.signOut()
		getSharedPreferences("app_user_session", MODE_PRIVATE).edit()
			.remove("COMPANY_CODE").remove("USER_ID").remove("FIREBASE_UID").remove("ROLE")
			.remove("IS_LOGGED_IN").apply()

		fun reject(message: String) {
			auth.signOut()
			loginButton.isEnabled = true
			signUpLink.isEnabled = true
			Toast.makeText(this, message, Toast.LENGTH_LONG).show()
		}

		auth.signInWithEmailAndPassword(email, userPw)
			.addOnSuccessListener { result ->
				val user = result.user
				if (user == null) {
					reject("로그인 사용자 정보를 확인할 수 없습니다.")
					return@addOnSuccessListener
				}
				SignupApproval.checkAdmin().addOnSuccessListener adminResult@{ admin ->
					if (isFinishing || isDestroyed || auth.currentUser?.uid != user.uid) return@adminResult
					if (admin.getBoolean("enabled") == true) {
						onLoginSuccess("", userId, user.uid, "ADMIN")
						return@adminResult
					}
				db.collection(WarehouseMembership.COLLECTION).document(user.uid)
					.get(Source.SERVER)
					.addOnSuccessListener membershipResult@{ document ->
						val membership = WarehouseMembership.from(document)
						if (isFinishing || isDestroyed || auth.currentUser?.uid != user.uid) {
							return@membershipResult
						}
						if (membership == null) {
							startActivity(Intent(this, PendingApproval::class.java)
								.putExtra("COMPANY_CODE", companyCode).putExtra("USER_ID", userId))
							finish()
						} else if (membership.companyCode != companyCode) {
							reject("관리자가 지정한 회사/사업장 코드로 로그인하세요.")
						} else {
							loginButton.isEnabled = true
							onLoginSuccess(membership.companyCode, userId, user.uid, membership.role)
						}
					}
					.addOnFailureListener { error ->
						Log.w("Frame1", "Firestore membership lookup failed", error)
						reject("팀원 권한을 확인하지 못했습니다. 연결 상태를 확인하고 다시 로그인하세요.")
					}
				}.addOnFailureListener {
					reject("계정 권한을 확인하지 못했습니다. 연결 상태를 확인하고 다시 로그인하세요.")
				}
			}
			.addOnFailureListener { error ->
				Log.w("Frame1", "Firebase sign-in failed", error)
				reject("로그인 실패: 아이디 또는 비밀번호를 확인하세요.")
			}
	}

	// 로그인 성공 후 세션 저장
	private fun onLoginSuccess(
		companyCode: String,
		userId: String,
		uid: String,
		role: String
	) {

		val sharedPref =
			getSharedPreferences(
				"app_user_session",
				MODE_PRIVATE
			)

		sharedPref.edit().apply {
			putString("COMPANY_CODE", companyCode)
			putString("USER_ID", userId)
			putString("FIREBASE_UID", uid)
			putString("ROLE", role)
			putBoolean("IS_LOGGED_IN", true)
			apply()
		}

		val roleName =
			if (role == "ADMIN") {
				"관리자"
			} else if (role == "QA") {
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
			if (role == "ADMIN") {
				Intent(this, AdminDashboard::class.java)
			} else if (role == "LOADER") {
				Intent(this, LoaderHomeDashboard::class.java)
			} else {
				Intent(this, HomeDashboard::class.java)
			}

		startActivity(intent)
	}
}
