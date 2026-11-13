package com.example.myapp

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

sealed interface AuditResultState {
    data object Loading : AuditResultState
    data class Failure(val message: String, val canRetry: Boolean = true) : AuditResultState
    data class WarehouseRequired(val warehouses: List<String>) : AuditResultState
    data class Ready(val result: F03AnalyzeAndCompareResponse) : AuditResultState
}

class AuditResultViewModel : ViewModel() {
    private val api by lazy { F03RetrofitClient.retrofit.create(F03ApiService::class.java) }
    private val mutableState = MutableLiveData<AuditResultState>()
    val state: LiveData<AuditResultState> = mutableState
    var photoBytes: ByteArray? = null
        private set
    var uploadedFilename: String? = null
        private set
    var warehouseCode: String? = null
        private set
    var reviewOnly = false
    private var testRackLabels = false
    private var activeCall: Call<*>? = null

    fun open(photo: ByteArray?, filename: String?, warehouse: String? = null, testRackLabels: Boolean = false) {
        if (mutableState.value != null) return
        photoBytes = photo
        uploadedFilename = filename
        warehouseCode = warehouse
        this.testRackLabels = testRackLabels
        requestComparison()
    }

    fun selectWarehouse(warehouse: String) {
        val required = mutableState.value as? AuditResultState.WarehouseRequired ?: return
        if (warehouse !in required.warehouses) return
        warehouseCode = warehouse
        requestComparison()
    }

    fun requestComparison() {
        if (activeCall != null) return
        mutableState.value = AuditResultState.Loading
        val filename = uploadedFilename
        if (filename != null) {
            compareUploadedPhoto(filename)
            return
        }
        val bytes = photoBytes
        if (bytes == null || bytes.isEmpty()) {
            mutableState.value = AuditResultState.Failure(
                "사진이 없습니다. 사진을 다시 선택하거나 촬영해주세요.", canRetry = false
            )
            return
        }
        val isPng = bytes.size >= 8 && bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() && bytes[2] == 0x4e.toByte() && bytes[3] == 0x47.toByte()
        val mime = if (isPng) "image/png" else "image/jpeg"
        val extension = if (isPng) "png" else "jpg"
        val body = MultipartBody.Part.createFormData(
            "image", "audit.$extension", RequestBody.create(MediaType.parse(mime), bytes)
        )
        val call = api.uploadImage(body)
        activeCall = call
        call.enqueue(object : Callback<F03ImageUploadResponse> {
            override fun onResponse(call: Call<F03ImageUploadResponse>, response: Response<F03ImageUploadResponse>) {
                if (call.isCanceled) return
                activeCall = null
                val uploaded = response.body()?.filename
                if (response.isSuccessful && !uploaded.isNullOrBlank()) {
                    uploadedFilename = uploaded
                    compareUploadedPhoto(uploaded)
                } else {
                    showHttpError("사진 업로드", response.code())
                }
            }

            override fun onFailure(call: Call<F03ImageUploadResponse>, error: Throwable) {
                if (!call.isCanceled) showConnectionError()
            }
        })
    }

    private fun compareUploadedPhoto(filename: String) {
        val mode = if (testRackLabels) "test_rack_labels" else "live"
        val call = api.analyzeAndCompare(filename, warehouseCode = warehouseCode, mode = mode)
        activeCall = call
        call.enqueue(object : Callback<F03AnalyzeAndCompareResponse> {
            override fun onResponse(call: Call<F03AnalyzeAndCompareResponse>, response: Response<F03AnalyzeAndCompareResponse>) {
                if (call.isCanceled) return
                activeCall = null
                val result = response.body()
                if (response.isSuccessful && result != null) {
                    mutableState.value = AuditResultState.Ready(result)
                } else {
                    if (response.code() == 404) uploadedFilename = null
                    if (response.code() == 409 && showWarehouseChoices(response)) return
                    showHttpError("ERP 대조", response.code())
                }
            }

            override fun onFailure(call: Call<F03AnalyzeAndCompareResponse>, error: Throwable) {
                if (!call.isCanceled) showConnectionError()
            }
        })
    }

    private fun showWarehouseChoices(response: Response<*>): Boolean {
        val warehouses = runCatching {
            val body = response.errorBody()?.string().orEmpty()
            val detail = JSONObject(body).getJSONObject("detail")
            if (detail.getString("code") != "WAREHOUSE_SELECTION_REQUIRED") return false
            val values = detail.getJSONArray("warehouses")
            (0 until values.length()).map { values.getString(it) }
        }.getOrDefault(emptyList())
        if (warehouses.isEmpty()) return false
        mutableState.value = AuditResultState.WarehouseRequired(warehouses)
        return true
    }

    private fun showHttpError(operation: String, code: Int) {
        val message = when (code) {
            400, 415 -> "사진을 읽을 수 없습니다. 정상적인 JPEG 또는 PNG를 선택해주세요."
            401 -> "Firebase 로그인이 필요합니다."
            403 -> "실사 요청 권한을 확인해주세요."
            404 -> "서버에서 사진을 찾을 수 없습니다."
            413 -> "16MB 이하의 사진을 선택해주세요."
            422 -> if (testRackLabels) "내장 테스트 이미지와 ABC-002 테스트 재고를 확인해주세요."
                else "$operation 실패 (HTTP $code)"
            429 -> "서버에서 다른 사진을 분석 중입니다. 잠시 후 다시 시도해주세요."
            503 -> "서버의 인증, 분석 모델 또는 ERP 설정을 확인해주세요."
            else -> "$operation 실패 (HTTP $code)"
        }
        val canRetry = code !in setOf(400, 413, 415) && (photoBytes != null || uploadedFilename != null)
        mutableState.value = AuditResultState.Failure(message, canRetry)
    }

    private fun showConnectionError() {
        activeCall = null
        mutableState.value = AuditResultState.Failure("분석 서버의 응답을 받지 못했습니다.")
    }

    override fun onCleared() {
        activeCall?.cancel()
    }
}
