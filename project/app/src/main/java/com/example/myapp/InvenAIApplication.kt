package com.example.myapp

import android.app.Activity
import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import java.lang.ref.WeakReference

class InvenAIApplication : Application(), Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private var current = WeakReference<Activity>(null)
    private var registration: ListenerRegistration? = null
    private var inventoryRegistration: ListenerRegistration? = null
    private var inventoryAnomalies = emptyList<InventoryItem>()
    private var owner: String? = null
    private var alerts = emptyList<AuditMisplacementAlert>()
    private var dialog: androidx.appcompat.app.AlertDialog? = null
    private val popupSeen = mutableSetOf<String>()
    private val incomingIds = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
        var authUid = FirebaseAuth.getInstance().currentUser?.uid
        FirebaseAuth.getInstance().addAuthStateListener { auth ->
            if (auth.currentUser?.uid != authUid) {
                authUid = auth.currentUser?.uid
                F06PushRegistration.signedOut(this)
            }
        }
        val network = getSystemService(ConnectivityManager::class.java)
        network?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { main.post {
                AuditAlertManager.flush(this@InvenAIApplication)
                F06PushOutbox.schedule(this@InvenAIApplication)
                F06PushRegistration.sync(this@InvenAIApplication)
            } }
        })
    }

    override fun onActivityResumed(activity: Activity) {
        current = WeakReference(activity)
        val company = InventoryManager.activeCompanyCode(this)
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val next = if (InventoryManager.role(this) == "LOADER" && company != null && uid != null) "$company:$uid" else null
        if (next != owner) {
            val notifications = getSystemService(android.app.NotificationManager::class.java)
            alerts.forEach { notifications?.cancel(it.id.hashCode()) }
            inventoryAnomalies.forEach { notifications?.cancel(it.documentId.hashCode()) }
            registration?.remove();registration = null;alerts = emptyList();popupSeen.clear()
            inventoryRegistration?.remove();inventoryRegistration = null;inventoryAnomalies = emptyList()
            incomingIds.clear()
            dialog?.dismiss();dialog = null;owner = next
        }
        if (next != null && registration == null) {
            registration = AuditAlertManager.listen(this) { received ->
                if (owner != next || InventoryManager.role(this) != "LOADER") return@listen
                val openIds = received.filter { it.status == "OPEN" }.map { it.id }.toSet()
                alerts.filter { it.id !in openIds }.forEach {
                    getSystemService(android.app.NotificationManager::class.java)?.cancel(it.id.hashCode())
                }
                alerts = received
                notifyOpenAlerts()
                present()
            }
        }
        if (next != null && inventoryRegistration == null) {
            inventoryRegistration = InventoryManager.listenToRealtimeInventory(this) { items ->
                if (owner != next || InventoryManager.role(this) != "LOADER") return@listenToRealtimeInventory
                val active = items.filter { it.status == "ANOMALY" }
                inventoryAnomalies.filter { old -> active.none { it.documentId == old.documentId } }.forEach {
                    getSystemService(android.app.NotificationManager::class.java)?.cancel(it.documentId.hashCode())
                }
                inventoryAnomalies = active
                active.forEach { F06NotificationHelper.notifyInventoryAlert(this, it) }
            }
        }
        if (next != null) F06PushRegistration.sync(this)
        if (InventoryManager.role(this) == "QA") {
            AuditAlertManager.flush(this)
            F06PushOutbox.schedule(this)
        }
        main.postDelayed({ present() }, 350)
    }

    internal fun notificationPermissionChanged() {
        notifyOpenAlerts()
        inventoryAnomalies.forEach { F06NotificationHelper.notifyInventoryAlert(this, it) }
        F06PushRegistration.sync(this)
        main.postDelayed({ present() }, 350)
    }

    private fun notifyOpenAlerts() {
        if (owner == null) return
        if (InventoryManager.role(this) != "LOADER") return
        for (alert in alerts.filter { it.status == "OPEN" }) {
            F06NotificationHelper.notifyAuditAlert(this, alert)
        }
    }

    private fun present(attempt: Int = 0) {
        val activity = current.get() ?: return
        if (activity.isFinishing || activity.isDestroyed || InventoryManager.role(this) != "LOADER") return
        val fresh = alerts.filter { it.status == "OPEN" && "${it.id}:${it.updatedAt}" !in popupSeen }
        if (dialog?.isShowing == true) {
            if (incomingIds.isNotEmpty()) {
                incomingIds.addAll(fresh.map { it.id })
                popupSeen.addAll(fresh.map { "${it.id}:${it.updatedAt}" })
                val combined = alerts.filter { it.status == "OPEN" && it.id in incomingIds }
                if (combined.isEmpty()) dialog?.dismiss() else AuditAlertViews.updateIncoming(dialog!!, combined)
            }
            return
        }
        if (!activity.hasWindowFocus()) {
            if (attempt < 20) main.postDelayed({ present(attempt + 1) }, 250)
            return
        }
        val deepLink = activity.intent.getStringExtra("OPEN_F06_ALERT_ID")
        val selected = alerts.find { it.id == deepLink }
        if (selected != null) {
            activity.intent.removeExtra("OPEN_F06_ALERT_ID")
            dialog = AuditAlertViews.details(activity, selected)
            return
        }
        if (fresh.isNotEmpty()) {
            popupSeen.addAll(fresh.map { "${it.id}:${it.updatedAt}" })
            incomingIds.clear();incomingIds.addAll(fresh.map { it.id })
            dialog = AuditAlertViews.incoming(activity, fresh)
            dialog?.setOnDismissListener { incomingIds.clear(); dialog = null; main.post { present() } }
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (current.get() === activity) { current.clear();dialog?.dismiss();dialog = null;incomingIds.clear() }
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
