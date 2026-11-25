package com.example.myapp

import android.app.Activity
import android.app.Dialog
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.android.material.button.MaterialButton

object SignUpDialog {

    fun show(activity: Activity, onSignUpSuccess: (String, String) -> Unit) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view: View = LayoutInflater.from(activity).inflate(R.layout.dialog_signup, null)
        dialog.setContentView(view)
        dialog.setCancelable(true)

        val etCompany = view.findViewById<EditText>(R.id.etSignupCompany)
        val etId = view.findViewById<EditText>(R.id.etSignupId)
        val etPw = view.findViewById<EditText>(R.id.etSignupPw)
        val etPwConfirm = view.findViewById<EditText>(R.id.etSignupPwConfirm)
        var role = "LOADER"
        val roleButton = view.findViewById<MaterialButton>(R.id.btnSignupRole)
        WarehouseMenus.decorateSelector(roleButton)
        roleButton.setOnClickListener {
            WarehouseMenus.show(activity, "요청 역할", SignupApproval.roleNames, SignupApproval.roles.indexOf(role)) {
                role = SignupApproval.roles[it]
                roleButton.text = SignupApproval.roleName(role)
            }
        }

        val btnCancel = view.findViewById<Button>(R.id.btnCancelSignup)
        val btnSubmit = view.findViewById<Button>(R.id.btnSubmitSignup)

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSubmit.setOnClickListener {
            val company = etCompany.text.toString().trim().uppercase()
            val id = etId.text.toString().trim()
            val pw = etPw.text.toString().trim()
            val pwConfirm = etPwConfirm.text.toString().trim()

            if (!SignupApproval.validCompany(company)) {
                Toast.makeText(activity, "유효한 회사 코드를 입력하세요. 영문 대문자·숫자·하이픈·밑줄을 사용할 수 있습니다.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (id.length !in 1..128) {
                Toast.makeText(activity, "아이디(사번)를 입력하세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (pw.length < 6) {
                Toast.makeText(activity, "비밀번호는 6자리 이상이어야 합니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (pw != pwConfirm) {
                Toast.makeText(activity, "비밀번호가 일치하지 않습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Firebase Auth용 이메일 변환
            val email = if (id.contains("@")) id else "$id@warehouse.com"
            if (email.equals("admin@warehouse.com", ignoreCase = true)) {
                Toast.makeText(activity, "관리자 전용 아이디입니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnSubmit.isEnabled = false
            btnCancel.isEnabled = false
            dialog.setCancelable(false)

            // 계정 생성 후 관리자의 소속 및 역할 등록을 기다립니다.
            val auth = FirebaseAuth.getInstance()

            auth.createUserWithEmailAndPassword(email, pw)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val uid = task.result.user?.uid
                        SignupApproval.submit(company, role, id).addOnCompleteListener { saved ->
                            if (auth.currentUser?.uid == uid) auth.signOut()
                            Toast.makeText(activity, if (saved.isSuccessful)
                                "가입 요청을 보냈습니다. 관리자 승인 후 이용할 수 있습니다."
                                else "계정은 생성됐지만 승인 요청을 저장하지 못했습니다. 로그인 후 다시 요청하세요.", Toast.LENGTH_LONG).show()
                            dialog.dismiss()
                            onSignUpSuccess(company, id)
                        }
                    } else {
                        btnSubmit.isEnabled = true
                        btnCancel.isEnabled = true
                        dialog.setCancelable(true)
                        val errorMsg = task.exception?.localizedMessage ?: "회원가입 오류"
                        Log.e("SignUpDialog", "Firebase Auth 실패: $errorMsg")
                        Toast.makeText(activity, "회원가입 실패: $errorMsg", Toast.LENGTH_SHORT).show()
                    }
                }
        }

        dialog.show()
        dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.92).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
}
