package tools.superscanner.clipboardhelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tools.superscanner.clipboard.ClipboardBridgeListener
import tools.superscanner.clipboard.ClipboardBridgeServer

/** Runs only after a foreground user action; clipboard access remains in the Activity. */
class ClipboardHelperService : Service() {
    inner class LocalBinder : Binder() { val service: ClipboardHelperService get() = this@ClipboardHelperService }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observer: ((HelperUiState) -> Unit)? = null
    private var bridge: ClipboardBridgeServer? = null
    private var startJob: Job? = null
    private var current = HelperUiState()
    private var generation = 0L

    internal fun setObserver(value: ((HelperUiState) -> Unit)?) {
        observer = value
        value?.invoke(current)
    }

    internal fun state(): HelperUiState = current

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                try {
                    ensureNotificationChannel()
                    val notification = notification()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                    } else startForeground(NOTIFICATION_ID, notification)
                    startSession()
                } catch (_: Exception) {
                    update(current.copy(starting = false, running = false,
                        error = "Could not start the foreground session. Open this helper and try again."))
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
            ACTION_STOP -> stopSession()
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    fun startSession() {
        if (current.running || current.starting) return
        val runGeneration = ++generation
        update(HelperUiState(starting = true))
        startJob = scope.launch {
            var created: ClipboardBridgeServer? = null
            try {
                val instance = ClipboardBridgeServer(applicationContext, object : ClipboardBridgeListener {
                    override fun onPairRequest(clientFingerprint: String, shortCode: String) {
                        val active = created ?: return
                        if (isCurrent(active, runGeneration)) update(current.copy(
                            pendingFingerprint = clientFingerprint,
                            pendingShortCode = shortCode,
                            error = null,
                        ))
                    }

                    override fun onReceivedText() {
                        val active = created ?: return
                        if (isCurrent(active, runGeneration)) update(current.copy(incomingText = active.receivedText))
                    }

                    override fun onSessionChanged() {
                        val active = created ?: return
                        if (isCurrent(active, runGeneration)) update(current.copy(
                            pendingFingerprint = active.pendingFingerprint,
                            pendingShortCode = active.pendingApprovalCode,
                            approvedFingerprint = active.approvedFingerprint,
                            incomingText = active.receivedText,
                        ))
                    }
                })
                created = instance
                bridge = instance
                val pair = withContext(Dispatchers.IO) { instance.start() }
                if (!isCurrent(instance, runGeneration)) {
                    withContext(NonCancellable + Dispatchers.IO) { instance.stop() }
                    return@launch
                }
                update(HelperUiState(running = true, pairInfo = pair))
            } catch (error: CancellationException) {
                created?.let { instance -> withContext(NonCancellable + Dispatchers.IO) { runCatching { instance.stop() } } }
                if (bridge === created) bridge = null
                throw error
            } catch (_: Exception) {
                created?.let { instance -> withContext(NonCancellable + Dispatchers.IO) { runCatching { instance.stop() } } }
                if (bridge === created) bridge = null
                if (generation == runGeneration) {
                    update(HelperUiState(error = "Connect both devices to the same private Wi-Fi, then start a new session."))
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun isCurrent(instance: ClipboardBridgeServer, runGeneration: Long): Boolean =
        generation == runGeneration && bridge === instance

    fun approvePairing(): Boolean {
        val fingerprint = current.pendingFingerprint ?: return false
        val approved = bridge?.approve(fingerprint) == true
        if (approved) update(current.copy(pendingFingerprint = null, pendingShortCode = null,
            approvedFingerprint = fingerprint, error = null))
        return approved
    }

    fun rejectPairing() {
        bridge?.reject()
        update(current.copy(pendingFingerprint = null, pendingShortCode = null))
    }

    fun unpair() {
        bridge?.unpair()
        update(current.copy(approvedFingerprint = null, pendingFingerprint = null,
            pendingShortCode = null, snapshotPublished = false, incomingText = null))
    }

    fun publishSnapshot(text: String) {
        try {
            val session = bridge ?: throw IllegalStateException("Session is stopped")
            check(session.approvedFingerprint != null) { "Pair a device first" }
            session.publishSnapshot(text)
            update(current.copy(snapshotPublished = true, error = null))
        } catch (_: Exception) {
            update(current.copy(error = "Text was not shared. Pair a device and keep the text under 64 KB."))
        }
    }

    fun clearSnapshot() {
        bridge?.clearSnapshot()
        update(current.copy(snapshotPublished = false))
    }

    fun clearIncoming() {
        bridge?.clearReceivedText()
        update(current.copy(incomingText = null))
    }

    fun stopSession() {
        val stopGeneration = ++generation
        startJob?.cancel()
        startJob = null
        val prior = bridge
        bridge = null
        update(HelperUiState())
        stopForeground(STOP_FOREGROUND_REMOVE)
        cleanupScope.launch {
            runCatching { prior?.stop() }
            withContext(Dispatchers.Main.immediate) {
                if (generation == stopGeneration && bridge == null) stopSelf()
            }
        }
    }

    override fun onDestroy() {
        val prior = bridge
        bridge = null
        generation++
        startJob?.cancel()
        scope.cancel()
        if (prior != null) cleanupScope.launch {
            withContext(NonCancellable) { runCatching { prior.stop() } }
        }
        super.onDestroy()
    }

    private fun update(value: HelperUiState) {
        current = value
        observer?.invoke(value)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
                "Clipboard helper session", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(): Notification {
        val stop = Intent(this, ClipboardHelperService::class.java).setAction(ACTION_STOP)
        val pending = PendingIntent.getService(this, 1, stop,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Supernote Clipboard Helper")
            .setContentText("Secure local text session is active")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "Stop session", pending,
            ).build())
            .build()
    }

    companion object {
        const val ACTION_START = "tools.superscanner.clipboardhelper.START"
        const val ACTION_STOP = "tools.superscanner.clipboardhelper.STOP"
        private const val CHANNEL_ID = "clipboard_helper_session"
        private const val NOTIFICATION_ID = 701
    }
}
