package com.example.myapp

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.google.firebase.auth.FirebaseAuth
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.concurrent.Executors

sealed interface AuditResultState {
    data object Loading : AuditResultState
    data class Failure(val message: String, val canRetry: Boolean = true) : AuditResultState
    data class WarehouseRequired(val warehouses: List<String>) : AuditResultState
    data class Ready(val result: F03AnalyzeAndCompareResponse) : AuditResultState
}

internal data class AuditAlertDeliveryUi(val message: String, val canRetry: Boolean = false)

class AuditResultViewModel(application: Application) : AndroidViewModel(application) {
    private val api by lazy { F03RetrofitClient.retrofit.create(F03ApiService::class.java) }
    private val mutableState = MutableLiveData<AuditResultState>()
    val state: LiveData<AuditResultState> = mutableState
    var photoBytes: ByteArray? = null
        private set
    var uploadedFilename: String? = null
        private set
    var warehouseCode: String? = null
        private set
    internal var photoDigest: String? = null
        private set
    internal var photoOwnerUid: String? = null
        private set
    var reviewOnly = false
    private var testRackLabels = false
    private var analysisMode = "live"
    private var expectedTestWarehouse: String? = null
    private var activeCall: Call<*>? = null
    private var locationCall: Call<F03PositionContext>? = null
    private val reviewDb = ManualAuditReviewDb(application)
    private val reviewExecutor = Executors.newSingleThreadExecutor()
    private val mainThread = Handler(Looper.getMainLooper())
    private val mutableManualReviews = MutableLiveData<ManualAuditState?>()
    internal val manualReviews: LiveData<ManualAuditState?> = mutableManualReviews
    private var manualPositions = emptyMap<String, ManualAuditPosition>()
    private var cleared = false
    private val mutableAlertDelivery = MutableLiveData<AuditAlertDeliveryUi?>()
    internal val alertDelivery: LiveData<AuditAlertDeliveryUi?> = mutableAlertDelivery
    private var alertIds = emptySet<String>()
    private val alertObserver = Observer<AuditAlertQueueState> { queue ->
        val company = InventoryManager.activeCompanyCode(application)
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (alertIds.isNotEmpty() && queue.owner == "$company:$uid") {
            val pending = queue.pending.intersect(alertIds).size
            val sending = queue.sending.intersect(alertIds).isNotEmpty()
            mutableAlertDelivery.value = when {
                pending == 0 -> AuditAlertDeliveryUi("오적재 알림 ${alertIds.size}건을 등록했습니다. 접속 중인 적재자 화면에 표시됩니다.")
                sending -> AuditAlertDeliveryUi("적재자에게 오적재 알림을 전송하고 있습니다.")
                else -> AuditAlertDeliveryUi("오적재 알림 ${pending}건 전송 대기 중입니다. 연결 상태를 확인해 주세요.", true)
            }
        }
    }
    init { AuditAlertManager.queueState.observeForever(alertObserver) }

    internal fun retryAlertDelivery() { AuditAlertManager.flush(getApplication<Application>()) }

    private fun sendAuditAlerts(result: F03AnalyzeAndCompareResponse) {
        val uid = photoOwnerUid ?: return
        val company = result.companyCode ?: return
        if (!hasQaSession(uid, company)) return
        val planned = planAuditMisplacementAlerts(result, uid)
        alertIds = planned.map { it.id }.toSet()
        mutableAlertDelivery.value = if (planned.isEmpty()) null else AuditAlertDeliveryUi("적재자에게 오적재 알림을 전송하고 있습니다.")
        if (planned.isNotEmpty()) AuditAlertManager.send(getApplication<Application>(), planned)
    }

    fun open(
        photo: ByteArray?,
        filename: String?,
        warehouse: String? = null,
        testRackLabels: Boolean = false,
        restoredDigest: String? = null,
        restoredOwner: String? = null,
        testMode: String? = null,
        testWarehouse: String? = null
    ) {
        if (mutableState.value != null) return
        photoBytes = photo
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        val sameOwner = currentUid != null && currentUid == restoredOwner
        photoOwnerUid = currentUid
        uploadedFilename = filename.takeIf { sameOwner }
        warehouseCode = warehouse.takeIf { sameOwner || photo != null }
        photoDigest = photo?.let(::auditPhotoDigest) ?: restoredDigest.takeIf { sameOwner }
        analysisMode = if (testRackLabels) "test_rack_labels"
            else testMode?.takeIf { it in setOf("test_product_regions", "test_reference_pairs") } ?: "live"
        this.testRackLabels = analysisMode != "live"
        expectedTestWarehouse = testWarehouse
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
        val problem = auditPhotoWarehouseProblem(warehouseCode, analysisMode, expectedTestWarehouse)
        if (problem != null) {
            mutableState.value = AuditResultState.Failure(problem, canRetry = false)
            return
        }
        locationCall?.cancel()
        locationCall = null
        manualPositions = emptyMap()
        mutableManualReviews.value = null
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
        val call = api.analyzeAndCompare(filename, warehouseCode = warehouseCode, mode = analysisMode)
        activeCall = call
        call.enqueue(object : Callback<F03AnalyzeAndCompareResponse> {
            override fun onResponse(call: Call<F03AnalyzeAndCompareResponse>, response: Response<F03AnalyzeAndCompareResponse>) {
                if (call.isCanceled) return
                activeCall = null
                val result = response.body()
                if (response.isSuccessful && result != null) {
                    val resultWarehouse = result.warehouseCode ?: result.erpWarehouse
                    if (resultWarehouse != warehouseCode || (result.erpWarehouse != null && result.erpWarehouse != warehouseCode)) {
                        mutableState.value = AuditResultState.Failure("선택한 창고와 분석 결과의 창고가 다릅니다. 창고와 사진을 다시 선택해 주세요.", canRetry = false)
                        return
                    }
                    mutableState.value = AuditResultState.Ready(result)
                    sendAuditAlerts(result)
                    loadManualReviews(result)
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

    private fun hasQaSession(uid: String, company: String): Boolean {
        val app = getApplication<Application>()
        val preferences = app.getSharedPreferences("app_user_session", Context.MODE_PRIVATE)
        return FirebaseAuth.getInstance().currentUser?.uid == uid &&
            InventoryManager.activeCompanyCode(app) == company &&
            preferences.getString("ROLE", null) == "QA"
    }

    private fun currentManualScope(): ManualAuditScope? =
        mutableManualReviews.value?.scope?.takeIf { hasQaSession(it.reviewerUid, it.companyCode) }

    private fun loadManualReviews(result: F03AnalyzeAndCompareResponse) {
        val uid = photoOwnerUid ?: return
        val company = result.companyCode ?: return
        val warehouse = result.warehouseCode ?: result.erpWarehouse ?: return
        val digest = photoDigest ?: return
        if (warehouse.isBlank() || !hasQaSession(uid, company)) return

        val context = getApplication<Application>()
        val scope = ManualAuditScope(company, uid, warehouse, digest, testRackLabels)
        AuditSessionManager.getVerifiedSession(context, 1) { session ->
            if (cleared || (mutableState.value as? AuditResultState.Ready)?.result !== result ||
                session?.role != "QA" || session.userId != uid || session.companyCode != company ||
                !hasQaSession(uid, company)
            ) return@getVerifiedSession

            reviewExecutor.execute {
                val saved = runCatching { reviewDb.read(scope) }
                mainThread.post {
                    if (cleared || (mutableState.value as? AuditResultState.Ready)?.result !== result ||
                        !hasQaSession(uid, company)
                    ) return@post
                    saved.onSuccess { records ->
                        manualPositions = result.results.orEmpty().mapNotNull { it.manualAuditPosition() }
                            .associateBy { it.id }
                        mutableManualReviews.value = ManualAuditState(scope, records)
                    }.onFailure { error ->
                        Log.w("AuditResultViewModel", "Could not load manual audit reviews", error)
                    }
                }
            }
        }
    }

    internal fun canSaveManualReview(): Boolean = currentManualScope() != null

    internal fun getManualPositions(onResult: (List<ManualAuditPosition>) -> Unit) {
        val scope = currentManualScope() ?: return onResult(emptyList())
        locationCall?.cancel()
        val call = api.getWarehousePositions(scope.warehouseCode)
        locationCall = call
        fun finish(positions: List<ManualAuditPosition>) {
            if (locationCall !== call || cleared) return
            locationCall = null
            if (currentManualScope() != scope) {
                onResult(emptyList())
                return
            }
            manualPositions = manualPositions + positions.associateBy { it.id }
            onResult(manualPositions.values.sortedBy { it.label })
        }
        call.enqueue(object : Callback<F03PositionContext> {
            override fun onResponse(call: Call<F03PositionContext>, response: Response<F03PositionContext>) {
                val body = response.body()
                val matchesScope = response.isSuccessful && body?.companyCode == scope.companyCode &&
                    body.warehouseCode == scope.warehouseCode
                val positions = if (matchesScope) body?.positions.orEmpty().mapNotNull { position ->
                    val id = position.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    ManualAuditPosition(id, position.label?.takeIf { it.isNotBlank() } ?: id)
                } else emptyList()
                finish(positions)
            }

            override fun onFailure(call: Call<F03PositionContext>, error: Throwable) {
                if (!call.isCanceled) finish(emptyList())
            }
        })
    }

    internal fun saveManualReview(
        position: ManualAuditPosition,
        barcode: String,
        quantity: String,
        onComplete: (Boolean) -> Unit
    ) {
        val scope = currentManualScope() ?: return onComplete(false)
        val allowedPosition = manualPositions[position.id]
            ?: mutableManualReviews.value?.records?.get(position.id)?.position
            ?: return onComplete(false)
        val values = manualAuditValues(barcode, quantity) ?: return onComplete(false)
        val review = ManualAuditReview(allowedPosition, values)
        reviewExecutor.execute {
            val saved = runCatching { reviewDb.save(scope, review) }
            mainThread.post {
                if (cleared) return@post
                val current = mutableManualReviews.value
                if (current?.scope != scope || currentManualScope() != scope) return@post
                if (saved.isSuccess) {
                    mutableManualReviews.value = current.copy(
                        records = current.records + (allowedPosition.id to review)
                    )
                } else {
                    Log.w("AuditResultViewModel", "Could not save manual audit review", saved.exceptionOrNull())
                }
                onComplete(saved.isSuccess)
            }
        }
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
        AuditAlertManager.queueState.removeObserver(alertObserver)
        cleared = true
        activeCall?.cancel()
        locationCall?.cancel()
        reviewExecutor.execute(reviewDb::close)
        reviewExecutor.shutdown()
    }
}
