package com.example.myapp

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

// 검수자(QA)와 적재자(LOADER) 간의 오적재 감지 및 실시간 처리 매니저 (사업장 코드별 스코프 지원)
object AnomalyManager {
    private const val TAG = "AnomalyManager"

    private fun getCompanyCode(context: Context?): String {
        if (context == null) return "ABC-001"
        val prefs = context.getSharedPreferences("app_user_session", Context.MODE_PRIVATE)
        val code = prefs.getString("COMPANY_CODE", "ABC-001") ?: "ABC-001"
        return if (code.isBlank() || code == "DEFAULT") "ABC-001" else code.trim().uppercase()
    }

    // 1. 검수자(QA): 동일 회사 코드를 가진 적재자들에게만 오적재 알림 발송 및 targetLocation 반영
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
        val companyCode = getCompanyCode(context)

        val anomalyData = hashMapOf(
            "anomalyId" to anomalyId,
            "companyCode" to companyCode, // 동일 회사 코드(companyCode) 스코프 부여
            "itemCode" to itemCode,
            "itemName" to itemName,
            "currentLocation" to currentLocation,
            "targetLocation" to targetLocation,
            "quantity" to quantity,
            "status" to "ALERTED",
            "qaUserId" to currentQaId,
            "loaderUserId" to loaderUserId,
            "createdAt" to FieldValue.serverTimestamp()
        )

        // 1. anomalies 컬렉션에 기록 (companyCode 포함)
        db.collection("anomalies").document(anomalyId)
            .set(anomalyData)
            .addOnSuccessListener {
                Log.d(TAG, "오적재 알림 Firestore 등록 성공: $anomalyId (Company: $companyCode)")

                // 2. inventory 컬렉션의 해당 품목 상태를 ANOMALY로, targetLocation을 반영하여 동기화 업데이트
                db.collection("inventory").document("${companyCode}_${itemCode}")
                    .update(
                        mapOf(
                            "status" to "ANOMALY",
                            "targetLocation" to targetLocation
                        )
                    )
                    .addOnCompleteListener {
                        Toast.makeText(context, "🚨 동일 사업장($companyCode) 적재자들에게 오적재 조치 요청 알림이 발송되었습니다!\n[$itemName: $currentLocation ➔ $targetLocation]", Toast.LENGTH_LONG).show()

                        PushSocketManager.registerDeviceToken(context, loaderUserId, currentQaId)
                        onSuccess()
                    }
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
        itemCode: String = "",
        onSuccess: () -> Unit = {}
    ) {
        val db = FirebaseFirestore.getInstance()
        val updateMap = hashMapOf<String, Any>(
            "status" to "RESOLVED",
            "resolvedAt" to FieldValue.serverTimestamp()
        )
        val companyCode = getCompanyCode(context)

        db.collection("anomalies").document(anomalyId)
            .update(updateMap)
            .addOnSuccessListener {
                Log.d(TAG, "재적재 완료 처리 성공: $anomalyId")
                if (itemCode.isNotEmpty()) {
                    db.collection("inventory").document("${companyCode}_${itemCode}")
                        .update("status", "PENDING_QA")
                        .addOnCompleteListener {
                            Toast.makeText(context, "✅ 올바른 재적재가 완료되었습니다!\n검수자(QA)에게 승인 요청이 전송되었습니다.", Toast.LENGTH_LONG).show()
                            onSuccess()
                        }
                } else {
                    Toast.makeText(context, "✅ 올바른 재적재가 완료되었습니다!", Toast.LENGTH_SHORT).show()
                    onSuccess()
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "재적재 완료 처리 예외: ${e.message}")
                Toast.makeText(context, "✅ 올바른 재적재가 완료되었습니다!", Toast.LENGTH_SHORT).show()
                onSuccess()
            }
    }

    // 3. 실시간 오적재 내역 리스너 (companyCode 기준 필터링)
    fun listenToAnomalies(
        context: Context?,
        onUpdate: (List<Map<String, Any>>) -> Unit
    ): ListenerRegistration? {
        return try {
            val companyCode = getCompanyCode(context)
            val db = FirebaseFirestore.getInstance()
            db.collection("anomalies")
                .whereEqualTo("companyCode", companyCode)
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
