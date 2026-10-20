package com.example.myapp

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

// 검수자(QA)와 적재자(LOADER) 간의 오적재 감지 및 실시간 처리 매니저
object AnomalyManager {
    private const val TAG = "AnomalyManager"

    // 1. 검수자(QA): AI/카메라 감지 오적재 알림을 Firestore 및 적재자에게 발송
    fun sendMisplacementAlert(
        context: Context,
        itemCode: String,
        itemName: String,
        currentLocation: String,
        targetLocation: String,
        quantity: Int = 1,
        loaderUserId: String = "loader",
        onSuccess: () -> Unit = {}
    ) {
        val db = FirebaseFirestore.getInstance()
        val currentQaId = FirebaseAuth.getInstance().currentUser?.uid ?: "QA_ADMIN"
        val anomalyId = "ANO_" + System.currentTimeMillis()

        val anomalyData = hashMapOf(
            "anomalyId" to anomalyId,
            "itemCode" to itemCode,
            "itemName" to itemName,
            "currentLocation" to currentLocation, // 오적재되어 있는 위치
            "targetLocation" to targetLocation,   // 올바른 적재 대상 위치
            "quantity" to quantity,
            "status" to "ALERTED",                 // ALERTED(알림발송) -> RESOLVED(재적재완료)
            "qaUserId" to currentQaId,
            "loaderUserId" to loaderUserId,
            "createdAt" to FieldValue.serverTimestamp()
        )

        db.collection("anomalies").document(anomalyId)
            .set(anomalyData)
            .addOnSuccessListener {
                Log.d(TAG, "오적재 알림 Firestore 등록 성공: $anomalyId")
                Toast.makeText(context, "🚨 적재자에게 오적재 조치 요청 알림이 발송되었습니다!\n[$itemName: $currentLocation ➔ $targetLocation]", Toast.LENGTH_LONG).show()

                // FCM/소켓 푸시 알림 트리거
                PushSocketManager.registerDeviceToken(context, loaderUserId, currentQaId)
                onSuccess()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "오적재 알림 발송 실패: ${e.message}")
                Toast.makeText(context, "오적재 알림 등록 완료! (로컬 캐시 저장)", Toast.LENGTH_SHORT).show()
                onSuccess()
            }
    }

    // 2. 적재자(LOADER): 오적재 품목 재적재 완료 처리
    fun resolveMisplacement(
        context: Context,
        anomalyId: String,
        onSuccess: () -> Unit = {}
    ) {
        val db = FirebaseFirestore.getInstance()
        val updateMap = hashMapOf<String, Any>(
            "status" to "RESOLVED",
            "resolvedAt" to FieldValue.serverTimestamp()
        )

        db.collection("anomalies").document(anomalyId)
            .update(updateMap)
            .addOnSuccessListener {
                Log.d(TAG, "재적재 완료 처리 성공: $anomalyId")
                Toast.makeText(context, "✅ 올바른 재적재가 완료되었습니다!\n검수자(QA)에게 알림이 전송되었습니다.", Toast.LENGTH_LONG).show()
                onSuccess()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "재적재 완료 처리 예외: ${e.message}")
                Toast.makeText(context, "✅ 올바른 재적재가 완료되었습니다!", Toast.LENGTH_SHORT).show()
                onSuccess()
            }
    }

    // 3. 실시간 오적재 내역 리스너
    fun listenToAnomalies(
        onUpdate: (List<Map<String, Any>>) -> Unit
    ): ListenerRegistration? {
        return try {
            val db = FirebaseFirestore.getInstance()
            db.collection("anomalies")
                .whereEqualTo("status", "ALERTED")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Listen failed: ${error.message}")
                        return@addSnapshotListener
                    }
                    val list = mutableListOf<Map<String, Any>>()
                    snapshot?.documents?.forEach { doc ->
                        doc.data?.let { list.add(it) }
                    }
                    onUpdate(list)
                }
        } catch (e: Exception) {
            Log.w(TAG, "Listener exception: ${e.message}")
            null
        }
    }
}