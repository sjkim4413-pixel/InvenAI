package com.example.myapp

import android.app.Activity
import android.app.Dialog
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

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
        val rbQA = view.findViewById<RadioButton>(R.id.rbQA)

        val btnCancel = view.findViewById<Button>(R.id.btnCancelSignup)
        val btnSubmit = view.findViewById<Button>(R.id.btnSubmitSignup)

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSubmit.setOnClickListener {
            val company = etCompany.text.toString().trim()
            val id = etId.text.toString().trim()
            val pw = etPw.text.toString().trim()
            val pwConfirm = etPwConfirm.text.toString().trim()
            val role = if (rbQA.isChecked) "QA" else "LOADER"

            if (company.isEmpty()) {
                Toast.makeText(activity, "회사/사업장 코드를 입력하세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (id.isEmpty()) {
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

            btnSubmit.isEnabled = false

            // Firebase Auth 및 Firestore 연동
            val auth = FirebaseAuth.getInstance()
            val db = FirebaseFirestore.getInstance()

            auth.createUserWithEmailAndPassword(email, pw)
                .addOnCompleteListener { task ->
                    btnSubmit.isEnabled = true
                    if (task.isSuccessful) {
                        val uid = task.result?.user?.uid
                        if (uid == null) {
                            Toast.makeText(activity, "회원가입 성공!", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            onSignUpSuccess(company, id)
                            return@addOnCompleteListener
                        }

                        // Cloud Function(onUserCreated/beforeUserCreated)에서 자동 생성된 users/{uid} 문서에
                        // 사용자가 입력한 companyCode, employeeId, role 정보를 병합(SetOptions.merge)
                        val updateMap = hashMapOf<String, Any>(
                            "companyCode" to company,
                            "employeeId" to id,
                            "role" to role, // "QA" 또는 "LOADER"
                            "updatedAt" to FieldValue.serverTimestamp()
                        )

                        db.collection("users").document(uid)
                            .set(updateMap, SetOptions.merge())
                            .addOnSuccessListener {
                                auth.signOut() // 회원가입 후 자동 세션 유지를 해제하여 로그인 화면에서 명시적 로그인 유도
                                Toast.makeText(activity, "회원가입 완료! ($role 권한)", Toast.LENGTH_SHORT).show()
                                dialog.dismiss()
                                onSignUpSuccess(company, id)
                            }
                            .addOnFailureListener { e ->
                                auth.signOut()
                                Log.w("SignUpDialog", "Firestore merge update info: ${e.message}")
                                Toast.makeText(activity, "회원가입 완료! ($role 권한)", Toast.LENGTH_SHORT).show()
                                dialog.dismiss()
                                onSignUpSuccess(company, id)
                            }
                    } else {
                        val errorMsg = task.exception?.localizedMessage ?: "회원가입 오류"
                        Log.e("SignUpDialog", "Firebase Auth 실패: $errorMsg")
                        Toast.makeText(activity, "회원가입 실패: $errorMsg", Toast.LENGTH_SHORT).show()
                    }
                }
        }

        dialog.show()
    }
}