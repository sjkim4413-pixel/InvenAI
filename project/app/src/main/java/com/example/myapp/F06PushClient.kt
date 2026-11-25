package com.example.myapp

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.android.gms.tasks.Tasks
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.UUID
import java.util.concurrent.TimeUnit

data class F06DeviceRegistration(@SerializedName("device_id") val deviceId: String, val token: String)
data class F06DeviceRemoval(@SerializedName("device_id") val deviceId: String)
data class F06DeviceRegistrationResult(val registered: Boolean, @SerializedName("sender_ready") val senderReady: Boolean)

internal object F06PushRegistration {
    @Volatile private var syncedOwner: String? = null
    @Volatile private var syncedAt = 0L
    private val mutableStatus = MutableLiveData("기기 알림 연결을 확인하고 있습니다.")
    val status: LiveData<String> = mutableStatus
    private val api get() = F03RetrofitClient.retrofit.create(F03ApiService::class.java)
    private fun device(context: Context): String {
        val prefs = context.getSharedPreferences("f06_push_device", Context.MODE_PRIVATE)
        return prefs.getString("device", null) ?: UUID.randomUUID().toString().replace("-", "").also {
            prefs.edit().putString("device", it).apply()
        }
    }
    private fun owner(context: Context): String? {
        if (InventoryManager.role(context) != "LOADER") return null
        val company = InventoryManager.activeCompanyCode(context) ?: return null
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return null
        return "$company:$uid"
    }

    fun sync(context: Context, freshToken: String? = null) {
        val next = owner(context) ?: return
        // Instrumentation using local Firebase must never register a device with the production API.
        if (FirebaseFirestore.getInstance().firestoreSettings.host != "firestore.googleapis.com") return
        if (!F06NotificationHelper.notificationsEnabled(context)) return
        if (freshToken == null && syncedOwner == next && System.currentTimeMillis()-syncedAt < 60_000) return
        mutableStatus.value = "기기 알림 연결을 확인하고 있습니다."
        FirebaseMessaging.getInstance().isAutoInitEnabled = true
        val work = OneTimeWorkRequestBuilder<F06DeviceRegistrationWorker>()
            .setInputData(workDataOf("owner" to next, "force" to (freshToken != null)))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        // Serialize account changes and token rotations for this installation, including after process death.
        WorkManager.getInstance(context).enqueueUniqueWork("f06-register-device", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }

    internal fun register(context: Context, key: String, force: Boolean): Boolean {
        if (owner(context) != key || !F06NotificationHelper.notificationsEnabled(context) ||
            FirebaseFirestore.getInstance().firestoreSettings.host != "firestore.googleapis.com") return true
        if (!force && syncedOwner == key && System.currentTimeMillis()-syncedAt < 60_000) return true
        return try {
            val token = Tasks.await(FirebaseMessaging.getInstance().token, 30, TimeUnit.SECONDS)
            if (owner(context) != key) return true
            val response = api.registerPushDevice(F06DeviceRegistration(device(context), token)).execute()
            if (owner(context) != key) return true
            if (response.isSuccessful && response.body()?.registered == true && response.body()?.senderReady == true) {
                syncedOwner = key;syncedAt = System.currentTimeMillis()
                mutableStatus.postValue("기기 알림 연결됨 · 다른 앱 사용 중에도 수신")
                true
            } else {
                mutableStatus.postValue("기기 알림 연결 확인 필요 · 터치하여 다시 확인")
                response.code() in setOf(403, 422)
            }
        } catch (error: Exception) {
            if (owner(context) == key) mutableStatus.postValue("기기 알림 연결 확인 필요 · 터치하여 다시 확인")
            false
        }
    }

    fun signedOut(context: Context) {
        syncedOwner = null;syncedAt = 0
        context.getSystemService(android.app.NotificationManager::class.java)?.cancelAll()
        mutableStatus.postValue("적재자 로그인 후 알림을 받을 수 있습니다.")
    }

    fun unregister(context: Context) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val id = device(context)
        user.getIdToken(false).addOnSuccessListener { token ->
            token.token?.let { bearer -> api.removePushDevice(F06DeviceRemoval(id), "Bearer $bearer")
                .enqueue(object : Callback<Map<String, Any>> {
                    override fun onResponse(call: Call<Map<String, Any>>, response: Response<Map<String, Any>>) = Unit
                    override fun onFailure(call: Call<Map<String, Any>>, error: Throwable) = Unit
                }) }
        }
        signedOut(context)
    }
}

class F06DeviceRegistrationWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val owner = inputData.getString("owner") ?: return Result.failure()
        return if (F06PushRegistration.register(applicationContext, owner, inputData.getBoolean("force", false)))
            Result.success() else Result.retry()
    }
}

private data class F06QueuedPush(val id: String, val kind: String)

internal object F06PushOutbox {
    private val gson = Gson()
    private fun owner(context: Context): String? {
        if (InventoryManager.role(context) != "QA") return null
        val company = InventoryManager.activeCompanyCode(context) ?: return null
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return null
        return "$company:$uid"
    }
    private fun prefs(context: Context) = context.getSharedPreferences("f06_push_outbox", Context.MODE_PRIVATE)
    private fun read(context: Context, owner: String): List<F06QueuedPush> = runCatching {
        gson.fromJson<List<F06QueuedPush>>(prefs(context).getString(owner, "[]"), object : TypeToken<List<F06QueuedPush>>() {}.type).orEmpty()
    }.getOrDefault(emptyList())

    @Synchronized fun enqueue(context: Context, id: String, kind: String = "audit") {
        val key = owner(context) ?: return
        val items = (read(context, key) + F06QueuedPush(id, kind)).distinct()
        prefs(context).edit().putString(key, gson.toJson(items)).apply()
        schedule(context)
    }

    fun schedule(context: Context) {
        val key = owner(context) ?: return
        if (read(context, key).isEmpty()) return
        if (FirebaseFirestore.getInstance().firestoreSettings.host != "firestore.googleapis.com") return
        val work = OneTimeWorkRequestBuilder<F06PushOutboxWorker>()
            .setInputData(workDataOf("owner" to key))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("f06-outbox:$key", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }

    @Synchronized private fun remove(context: Context, key: String, item: F06QueuedPush) {
        prefs(context).edit().putString(key, gson.toJson(read(context, key).filterNot { it == item })).apply()
    }

    fun deliver(context: Context, key: String): Boolean {
        val api = F03RetrofitClient.retrofit.create(F03ApiService::class.java)
        if (owner(context) != key) return true
        for (item in read(context, key).take(5)) {
            if (owner(context) != key) return true
            val result = runCatching { api.dispatchPushAlert(item.id, item.kind).execute() }.getOrNull() ?: return false
            if (owner(context) != key) return true
            if (result.isSuccessful || result.code() in setOf(403, 404, 422)) remove(context, key, item) else return false
        }
        return read(context, key).isEmpty()
    }
}

class F06PushOutboxWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val owner = inputData.getString("owner") ?: return Result.failure()
        return if (F06PushOutbox.deliver(applicationContext, owner)) Result.success() else Result.retry()
    }
}
