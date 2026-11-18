package com.remotepad.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.remotepad.app.MainActivity
import com.remotepad.app.R
import com.remotepad.app.network.ConnectionManager
import com.remotepad.app.network.ConnectionState
import com.remotepad.app.network.TransportMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Persistent Foreground Service that keeps RemotePad's PC connection (Local Wi-Fi,
 * Direct P2P UDP, or Internet VPS Relay) AND optional Phone Microphone stream alive
 * even when the user leaves the app, locks the screen, or swipes the app away from Recents.
 *
 * Uses `connectedDevice` foregroundServiceType at all times, and dynamically upgrades to
 * `connectedDevice | microphone` ONLY while the user has manually enabled Phone Mic
 * (so Android 11–16 does not silence AudioRecord 2–3 seconds after leaving the app).
 */
class RemoteConnectionService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var connectionManager: ConnectionManager
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        connectionManager = ConnectionManager.getInstance(applicationContext)
        notificationManager = getSystemService(NotificationManager::class.java)
        ensureNotificationChannel()
        promoteToForeground(
            state = connectionManager.connectionState.value,
            micEnabled = connectionManager.isMicEnabled.value,
            micStreaming = connectionManager.isMicStreaming.value,
            includeMicrophone = connectionManager.shouldHoldMicrophoneForeground(),
        )

        serviceScope.launch {
            combine(
                connectionManager.connectionState,
                connectionManager.isMicEnabled,
                connectionManager.isMicStreaming,
            ) { state, micEnabled, micStreaming ->
                Triple(state, micEnabled, micStreaming)
            }.collect { (state, micEnabled, micStreaming) ->
                if (isRunning) {
                    runCatching {
                        notificationManager.notify(
                            NOTIFICATION_ID,
                            buildNotification(state, micEnabled, micStreaming),
                        )
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MIC_START -> {
                val promoted = promoteToForeground(
                    state = connectionManager.connectionState.value,
                    micEnabled = connectionManager.isMicEnabled.value,
                    micStreaming = connectionManager.isMicStreaming.value,
                    includeMicrophone = true,
                )
                if (promoted) {
                    connectionManager.onMicForegroundServiceReady()
                } else {
                    connectionManager.onMicForegroundServiceFailed(
                        "Could not start background microphone service"
                    )
                }
                return START_STICKY
            }
            ACTION_MIC_STOP -> {
                connectionManager.stopMicFromService()
                if (connectionManager.shouldKeepServiceAlive()) {
                    promoteToForeground(
                        state = connectionManager.connectionState.value,
                        micEnabled = false,
                        micStreaming = false,
                        includeMicrophone = false,
                    )
                    return START_STICKY
                } else {
                    stopSelfAndClearForeground()
                    return START_NOT_STICKY
                }
            }
            ACTION_DISCONNECT -> {
                connectionManager.disconnectByUser()
                stopSelfAndClearForeground()
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> {
                promoteToForeground(
                    state = connectionManager.connectionState.value,
                    micEnabled = connectionManager.isMicEnabled.value,
                    micStreaming = connectionManager.isMicStreaming.value,
                    includeMicrophone = connectionManager.shouldHoldMicrophoneForeground(),
                )
                connectionManager.onServiceStarted(forceReconnect = true)
                return START_STICKY
            }
            ACTION_STOP -> {
                connectionManager.onForegroundServiceDestroyed()
                stopSelfAndClearForeground()
                return START_NOT_STICKY
            }
            else -> {
                promoteToForeground(
                    state = connectionManager.connectionState.value,
                    micEnabled = connectionManager.isMicEnabled.value,
                    micStreaming = connectionManager.isMicStreaming.value,
                    includeMicrophone = connectionManager.shouldHoldMicrophoneForeground(),
                )
                connectionManager.onServiceStarted(forceReconnect = false)
                return START_STICKY
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Intentionally do NOT disconnect, stop the microphone, or stopSelf() when the user
        // closes/swipes the app away from Recents — the foreground service keeps both the
        // PC connection and any manually enabled Phone Mic stream alive.
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        isHoldingMicrophoneForeground = false
        serviceScope.cancel()
        connectionManager.onForegroundServiceDestroyed()
        super.onDestroy()
    }

    private fun stopSelfAndClearForeground() {
        isRunning = false
        isHoldingMicrophoneForeground = false
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Promotes (or updates) this Foreground Service. Returns true if the microphone
     * foreground service capability is actively granted, false otherwise.
     */
    private fun promoteToForeground(
        state: ConnectionState,
        micEnabled: Boolean,
        micStreaming: Boolean,
        includeMicrophone: Boolean,
    ): Boolean {
        val wantsMicType = includeMicrophone && hasRecordAudioPermission()
        val notification = buildNotification(state, micEnabled, micStreaming)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (wantsMicType) {
                val combinedType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                val micPromoted = runCatching {
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, combinedType)
                }.isSuccess
                if (micPromoted) {
                    isHoldingMicrophoneForeground = true
                    return true
                }
            }
            runCatching {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            }
            isHoldingMicrophoneForeground = false
            return false
        }

        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        val started = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
        }.isSuccess
        isHoldingMicrophoneForeground = started && wantsMicType
        return isHoldingMicrophoneForeground
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PC Connection & Mic",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps RemotePad connected to your PC and streams Phone Mic in the background"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        state: ConnectionState,
        micEnabled: Boolean,
        micStreaming: Boolean,
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            REQ_OPEN,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopMicIntent = Intent(this, RemoteConnectionService::class.java).apply {
            action = ACTION_MIC_STOP
        }
        val stopMicPending = PendingIntent.getService(
            this,
            REQ_STOP_MIC,
            stopMicIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val reconnectIntent = Intent(this, RemoteConnectionService::class.java).apply {
            action = ACTION_RECONNECT
        }
        val reconnectPending = PendingIntent.getService(
            this,
            REQ_RECONNECT,
            reconnectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val disconnectIntent = Intent(this, RemoteConnectionService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPending = PendingIntent.getService(
            this,
            REQ_DISCONNECT,
            disconnectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val baseStatusText = when (state) {
            is ConnectionState.Connected -> when (state.transportMode) {
                TransportMode.LAN -> "Connected to ${state.pcName} • Local Wi-Fi"
                TransportMode.P2P_DIRECT -> {
                    val rtt = state.p2pRttMs?.let { " (${it}ms)" }.orEmpty()
                    "Connected to ${state.pcName} • Direct P2P$rtt"
                }
                TransportMode.P2P_CONNECTING -> "Connected to ${state.pcName} • Internet (P2P...)"
                TransportMode.VPS_RELAY -> "Connected to ${state.pcName} • Internet (VPS)"
            }
            is ConnectionState.Connecting -> "Connecting • ${state.target}"
            is ConnectionState.Error -> if (connectionManager.isReconnectExhausted) {
                state.message
            } else {
                "${state.message} • Reconnecting..."
            }
            ConnectionState.Disconnected -> if (connectionManager.isReconnectExhausted) {
                "Disconnected"
            } else {
                "Auto-connect active (Wi-Fi → VPS)"
            }
        }

        val title = when {
            micEnabled && micStreaming -> "RemotePad • Mic Live"
            micEnabled -> "RemotePad • Mic Paused"
            else -> "RemotePad"
        }

        val statusText = when {
            micEnabled && micStreaming && state is ConnectionState.Connected ->
                "Phone Mic active → ${state.pcName} • Local Wi-Fi"
            micEnabled ->
                "Phone Mic waiting for Wi-Fi • $baseStatusText"
            else -> baseStatusText
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_remotepad)
            .setContentTitle(title)
            .setContentText(statusText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusText))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPending)

        if (micEnabled) {
            builder.addAction(0, "Stop Mic", stopMicPending)
        } else {
            builder.addAction(0, "Reconnect", reconnectPending)
        }
        builder.addAction(0, "Disconnect", disconnectPending)

        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "remotepad_connection"
        private const val NOTIFICATION_ID = 1001
        private const val REQ_OPEN = 101
        private const val REQ_RECONNECT = 102
        private const val REQ_DISCONNECT = 103
        private const val REQ_STOP_MIC = 104

        const val ACTION_START = "com.remotepad.app.action.START"
        const val ACTION_RECONNECT = "com.remotepad.app.action.RECONNECT"
        const val ACTION_DISCONNECT = "com.remotepad.app.action.DISCONNECT"
        const val ACTION_STOP = "com.remotepad.app.action.STOP"
        const val ACTION_MIC_START = "com.remotepad.app.action.MIC_START"
        const val ACTION_MIC_STOP = "com.remotepad.app.action.MIC_STOP"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isHoldingMicrophoneForeground: Boolean = false
            private set

        fun start(context: Context) {
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, RemoteConnectionService::class.java).apply {
                action = ACTION_START
            }
            runCatching {
                ContextCompat.startForegroundService(appCtx, intent)
            }
        }

        fun startMic(context: Context) {
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, RemoteConnectionService::class.java).apply {
                action = ACTION_MIC_START
            }
            val started = runCatching {
                ContextCompat.startForegroundService(appCtx, intent)
            }.isSuccess
            if (!started) {
                ConnectionManager.getInstance(appCtx)
                    .onMicForegroundServiceFailed("Could not start background microphone service")
            }
        }

        fun stopMic(context: Context) {
            if (!isRunning && !isHoldingMicrophoneForeground) return
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, RemoteConnectionService::class.java).apply {
                action = ACTION_MIC_STOP
            }
            runCatching {
                appCtx.startService(intent)
            }
        }

        fun stop(context: Context) {
            if (!isRunning) return
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, RemoteConnectionService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching {
                appCtx.stopService(intent)
            }
        }
    }
}
