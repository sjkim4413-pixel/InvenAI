package com.example.myapp

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

// 로컬 Firebase 에뮬레이터 테스트 설정 클래스
object FirebaseEmulatorConfig {

    // 로컬 에뮬레이터 Suite 사용 시 true로 변경하시면 10.0.2.2 가상 에뮬레이터 서버로 연결됩니다.
    var USE_EMULATOR = false

    private var isConfigured = false

    fun setupEmulator() {
        if (USE_EMULATOR && !isConfigured) {
            try {
                val auth = FirebaseAuth.getInstance()
                auth.useEmulator("10.0.2.2", 9099)

                val db = FirebaseFirestore.getInstance()
                db.useEmulator("10.0.2.2", 8080)

                isConfigured = true
                Log.d("FirebaseEmulator", "Connected to Firebase Local Emulator Suite (10.0.2.2:9099 / 8080)")
            } catch (e: Exception) {
                Log.w("FirebaseEmulator", "Emulator configuration: " + e.message)
            }
        }
    }
}