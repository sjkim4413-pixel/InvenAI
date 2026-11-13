package com.example.myapp

import com.google.firebase.auth.FirebaseAuth

object F04AuthTokenProvider {

    fun getToken(
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val user = FirebaseAuth.getInstance().currentUser

        if (user == null) {
            onError("로그인이 필요합니다.")
            return
        }

        user.getIdToken(false)
            .addOnSuccessListener { result ->
                val token = result.token

                if (token.isNullOrBlank()) {
                    onError("Firebase 인증 토큰을 가져오지 못했습니다.")
                } else {
                    onSuccess(token)
                }
            }
            .addOnFailureListener {
                onError("Firebase 인증 토큰 발급에 실패했습니다.")
            }
    }
}
