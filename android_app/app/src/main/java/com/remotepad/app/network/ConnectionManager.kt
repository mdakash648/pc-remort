package com.remotepad.app.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.remotepad.app.input.MicSessionAdapter
import com.remotepad.app.input.MicSessionController
import com.remotepad.app.input.MicStreamer
import com.remotepad.app.service.RemoteConnectionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class RelayConfig(
    val url: String,
    val deviceId: String,
    val token: String,
) {
    val isValid: Boolean
        get() = (url.startsWith("ws://") || url.startsWith("wss://")) &&
            deviceId.length >= 3 &&
            token.length >= 8
}

enum class ConnectOutcome {
    CONNECTED_WIFI,
    CONNECTED_VPS,
    NO_NETWORK,
    FAILED,
}

enum class ReconnectCycleResult {
    CONNECTED_WIFI,
    CONNECTED_VPS,
    NO_NETWORK,
    EXHAUSTED,
    STOPPED,
}

/**
 * Tracks consecutive failed reconnect attempts per disconnect episode.
 *
 * - Caps automatic reconnect tries at [maxAttempts] (default: 5).
 * - Resets back to 0 when a connection succeeds ([onConnected]) so a subsequent
 *   disconnect gets a fresh [maxAttempts] tries, or when the user explicitly
 *   triggers a manual connect/retry ([resetForUserAction]).
 */
class ReconnectBudget(
    val maxAttempts: Int = MAX_RECONNECT_ATTEMPTS,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }

    @Volatile
    var failedAttempts: Int = 0
        private set

    val isExhausted: Boolean
        get() = failedAttempts >= maxAttempts

    val remainingAttempts: Int
        get() = (maxAttempts - failedAttempts).coerceAtLeast(0)

    val nextAttemptNumber: Int
        get() = (failedAttempts + 1).coerceAtMost(maxAttempts)

    fun recordFailure(): Boolean {
        if (failedAttempts < maxAttempts) {
            failedAttempts += 1
        }
        return isExhausted
    }

    fun onConnected() {
        failedAttempts = 0
    }

    fun resetForUserAction() {
        failedAttempts = 0
    }

    companion object {
        const val MAX_RECONNECT_ATTEMPTS = 5
    }
}

interface ConnectionTransportAdapter {
    fun currentNetwork(): NetworkSnapshot
    fun savedLanHost(): String
    fun savedLanPort(): Int
    fun relayConfig(): RelayConfig?
    fun onSearchingWifi()
    fun onDiscoveredPcSelected(pc: DiscoveredPc)
    suspend fun discoverLanPc(timeoutMs: Long, freshScan: Boolean): DiscoveredPc?
    suspend fun tryConnectLan(host: String, port: Int, timeoutMs: Long): Boolean
    suspend fun tryConnectRelay(config: RelayConfig, timeoutMs: Long): Boolean
    fun stopDiscovery()
}

/**
 * Pure-JVM Wi-Fi-first -> Internet (VPS) connection orchestrator.
 *
 * Strategy:
 * 1. Always try Local Wi-Fi (LAN) first (active UDP discovery, plus direct saved-IP probe
 *    only when on Wi-Fi/Ethernet and the saved IP belongs to the current local subnet).
 * 2. If Local Wi-Fi finds no PC or the LAN handshake fails, fall back immediately to Internet (VPS)
 *    which establishes WebSocket + upgrades to Direct P2P UDP when NAT allows.
 */
class WifiFirstConnector(
    private val adapter: ConnectionTransportAdapter,
    private val wifiDiscoveryTimeoutMs: Long = DEFAULT_WIFI_DISCOVERY_TIMEOUT_MS,
    private val cellularDiscoveryTimeoutMs: Long = DEFAULT_CELLULAR_DISCOVERY_TIMEOUT_MS,
    private val lanConnectTimeoutMs: Long = DEFAULT_LAN_CONNECT_TIMEOUT_MS,
    private val savedIpConnectTimeoutMs: Long = DEFAULT_SAVED_IP_CONNECT_TIMEOUT_MS,
    private val relayConnectTimeoutMs: Long = DEFAULT_RELAY_CONNECT_TIMEOUT_MS,
) {
    suspend fun attemptOnce(freshNetwork: Boolean): ConnectOutcome {
        val net = adapter.currentNetwork()
        if (!net.hasNetwork) {
            adapter.stopDiscovery()
            return ConnectOutcome.NO_NETWORK
        }

        // Step 1: Always try Local Wi-Fi (LAN) first
        adapter.onSearchingWifi()
        val discoveryWindowMs = if (net.isWifiOrEthernet) {
            wifiDiscoveryTimeoutMs
        } else {
            // Brief probe even on mobile data so PCs connected to the phone's Mobile Hotspot
            // still get ultra-low-latency Local Wi-Fi automatically.
            cellularDiscoveryTimeoutMs
        }

        val discovered = adapter.discoverLanPc(
            timeoutMs = discoveryWindowMs,
            freshScan = freshNetwork,
        )
        if (discovered != null) {
            adapter.onDiscoveredPcSelected(discovered)
            if (adapter.tryConnectLan(discovered.host, discovered.port, lanConnectTimeoutMs)) {
                adapter.stopDiscovery()
                return ConnectOutcome.CONNECTED_WIFI
            }
        } else if (net.isWifiOrEthernet || net.isVpn) {
            // Router or VPN may block UDP broadcast; try saved LAN IP directly
            val savedHost = adapter.savedLanHost().trim()
            val savedPort = adapter.savedLanPort()
            if (savedHost.isNotEmpty() && savedPort in 1..65535 && (net.isVpn || net.matchesLocalSubnet(savedHost))) {
                if (adapter.tryConnectLan(savedHost, savedPort, savedIpConnectTimeoutMs)) {
                    adapter.stopDiscovery()
                    return ConnectOutcome.CONNECTED_WIFI
                }
            }
        }

        // Step 2: Wi-Fi did not connect -> fall back to Internet (VPS)
        adapter.stopDiscovery()
        val relay = adapter.relayConfig()
        if (relay != null && relay.isValid) {
            if (adapter.tryConnectRelay(relay, relayConnectTimeoutMs)) {
                return ConnectOutcome.CONNECTED_VPS
            }
        }

        return ConnectOutcome.FAILED
    }

    /**
     * Runs Wi-Fi-first -> Internet (VPS) reconnect attempts up to the remaining capacity in [budget].
     *
     * - Returns [ReconnectCycleResult.CONNECTED_WIFI] or [ReconnectCycleResult.CONNECTED_VPS]
     *   and resets [budget] immediately on success so the next disconnect gets a full [budget.maxAttempts] tries.
     * - Returns [ReconnectCycleResult.EXHAUSTED] once [budget.maxAttempts] total failures are reached.
     * - Returns [ReconnectCycleResult.NO_NETWORK] without consuming an attempt when offline.
     */
    suspend fun connectWithBudget(
        budget: ReconnectBudget,
        freshNetwork: Boolean,
        isCancelled: () -> Boolean = { false },
        allowRetry: () -> Boolean = { true },
        onAttemptStarting: (attemptNumber: Int, maxAttempts: Int) -> Unit = { _, _ -> },
        onWaitBeforeRetry: suspend (failedAttempts: Int, maxAttempts: Int) -> Unit = { _, _ -> },
    ): ReconnectCycleResult {
        if (budget.isExhausted) return ReconnectCycleResult.EXHAUSTED
        var doFreshScan = freshNetwork
        while (!budget.isExhausted) {
            if (isCancelled()) return ReconnectCycleResult.STOPPED
            val attemptNumber = budget.nextAttemptNumber
            onAttemptStarting(attemptNumber, budget.maxAttempts)
            when (attemptOnce(freshNetwork = doFreshScan)) {
                ConnectOutcome.CONNECTED_WIFI -> {
                    budget.onConnected()
                    return ReconnectCycleResult.CONNECTED_WIFI
                }
                ConnectOutcome.CONNECTED_VPS -> {
                    budget.onConnected()
                    return ReconnectCycleResult.CONNECTED_VPS
                }
                ConnectOutcome.NO_NETWORK -> {
                    return ReconnectCycleResult.NO_NETWORK
                }
                ConnectOutcome.FAILED -> {
                    doFreshScan = false
                    val exhausted = budget.recordFailure()
                    if (isCancelled()) return ReconnectCycleResult.STOPPED
                    if (exhausted) return ReconnectCycleResult.EXHAUSTED
                    if (!allowRetry()) return ReconnectCycleResult.STOPPED
                    onWaitBeforeRetry(budget.failedAttempts, budget.maxAttempts)
                }
            }
        }
        return ReconnectCycleResult.EXHAUSTED
    }

    companion object {
        const val DEFAULT_WIFI_DISCOVERY_TIMEOUT_MS = 2000L
        const val DEFAULT_CELLULAR_DISCOVERY_TIMEOUT_MS = 650L
        const val DEFAULT_LAN_CONNECT_TIMEOUT_MS = 2600L
        const val DEFAULT_SAVED_IP_CONNECT_TIMEOUT_MS = 1800L
        const val DEFAULT_RELAY_CONNECT_TIMEOUT_MS = 7000L
    }
}

/**
 * Process-wide singleton that owns the PC connection lifecycle, LAN discovery, and Android
 * network-change monitoring so the connection survives Activity closure and automatically
 * re-evaluates Wi-Fi first -> Internet (VPS) whenever the network changes.
 */
class ConnectionManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val client = LocalClient(appContext)
    val discovery = LanDiscovery(appContext)

    val connectionState: StateFlow<ConnectionState> = client.state
    val volumeState: StateFlow<RemoteVolumeState> = client.volumeState
    val screenFrames: SharedFlow<ScreenFramePacket> = client.screenFrames
    val p2pClient: P2PUdpClient get() = client.p2pClient
    val discoveredPcs: StateFlow<List<DiscoveredPc>> = discovery.discoveredPcs
    val isScanning: StateFlow<Boolean> = discovery.isScanning

    private val _host = MutableStateFlow(prefs.getString(KEY_HOST, "") ?: "")
    val host: StateFlow<String> = _host.asStateFlow()

    private val _port = MutableStateFlow(prefs.getString(KEY_PORT, DEFAULT_PORT) ?: DEFAULT_PORT)
    val port: StateFlow<String> = _port.asStateFlow()

    private val _relayUrl = MutableStateFlow(prefs.getString(KEY_RELAY_URL, DEFAULT_RELAY_URL) ?: DEFAULT_RELAY_URL)
    val relayUrl: StateFlow<String> = _relayUrl.asStateFlow()

    private val _relayDeviceId = MutableStateFlow(
        prefs.getString(KEY_RELAY_DEVICE_ID, DEFAULT_RELAY_DEVICE_ID)?.let {
            if (it == "akash-pc") "" else it
        } ?: DEFAULT_RELAY_DEVICE_ID
    )
    val relayDeviceId: StateFlow<String> = _relayDeviceId.asStateFlow()

    private val _relayToken = MutableStateFlow(
        prefs.getString(KEY_RELAY_TOKEN, DEFAULT_RELAY_TOKEN)?.let {
            if (it == "rpad-7f4c9a2e8b1d6305") "" else it
        } ?: DEFAULT_RELAY_TOKEN
    )
    val relayToken: StateFlow<String> = _relayToken.asStateFlow()

    private val _autoConnect = MutableStateFlow(prefs.getBoolean(KEY_AUTO_CONNECT, true))
    val autoConnect: StateFlow<Boolean> = _autoConnect.asStateFlow()

    private val reconnectBudget = ReconnectBudget(ReconnectBudget.MAX_RECONNECT_ATTEMPTS)
    val isReconnectExhausted: Boolean
        get() = reconnectBudget.isExhausted

    @Volatile
    private var activeAttemptNumber = 0

    @Volatile
    private var userManuallyDisconnected = false

    @Volatile
    private var isUiAttached = false

    @Volatile
    private var hasEverStartedSession = false

    private val orchestratorLock = Any()
    private var orchestratorJob: Job? = null
    private var networkDebounceJob: Job? = null
    private var manualConnectInProgress = false
    private var lastNetworkEventMs = 0L

    private val micLockGuard = Any()
    private var micWakeLock: PowerManager.WakeLock? = null
    private var micWifiLock: WifiManager.WifiLock? = null

    private lateinit var micSession: MicSessionController

    private val micStreamer = MicStreamer(
        onLevelChanged = { lvl ->
            if (::micSession.isInitialized) {
                micSession.onStreamLevelChanged(lvl)
            }
        },
        onError = { msg ->
            if (::micSession.isInitialized) {
                micSession.onStreamError(msg)
            }
        },
    )

    val isMicEnabled: StateFlow<Boolean>
        get() = micSession.isMicEnabled

    val isMicStreaming: StateFlow<Boolean>
        get() = micSession.isMicStreaming

    val micLevel: StateFlow<Float>
        get() = micSession.micLevel

    val micError: StateFlow<String?>
        get() = micSession.micError

    private val networkMonitor = NetworkMonitor(appContext) { snapshot ->
        handleNetworkChanged(snapshot)
    }

    private val connector = WifiFirstConnector(
        adapter = object : ConnectionTransportAdapter {
            override fun currentNetwork(): NetworkSnapshot = networkMonitor.queryCurrentSnapshot()

            override fun savedLanHost(): String = _host.value.trim()

            override fun savedLanPort(): Int = _port.value.toIntOrNull() ?: 8765

            override fun relayConfig(): RelayConfig? {
                val cfg = RelayConfig(
                    url = _relayUrl.value.trim(),
                    deviceId = _relayDeviceId.value.trim(),
                    token = _relayToken.value.trim(),
                )
                return if (cfg.isValid) cfg else null
            }

            override fun onSearchingWifi() {
                val attempt = activeAttemptNumber
                val label = if (attempt > 0) {
                    "Wi-Fi ($attempt/${reconnectBudget.maxAttempts})..."
                } else {
                    "Wi-Fi (searching...)"
                }
                client.setConnectingStatus(label)
            }

            override fun onDiscoveredPcSelected(pc: DiscoveredPc) {
                _host.value = pc.host
                _port.value = pc.port.toString()
                prefs.edit()
                    .putString(KEY_HOST, pc.host)
                    .putString(KEY_PORT, pc.port.toString())
                    .putString(KEY_LAST_PC_NAME, pc.name)
                    .apply()
            }

            override suspend fun discoverLanPc(timeoutMs: Long, freshScan: Boolean): DiscoveredPc? {
                if (freshScan) {
                    discovery.restart()
                } else {
                    discovery.start()
                    discovery.probeNow()
                }
                val immediate = selectPreferredPc(discovery.discoveredPcs.value)
                if (immediate != null) return immediate

                val foundList = withTimeoutOrNull(timeoutMs) {
                    discovery.discoveredPcs.first { it.isNotEmpty() }
                }
                return foundList?.let { selectPreferredPc(it) }
            }

            override suspend fun tryConnectLan(host: String, port: Int, timeoutMs: Long): Boolean {
                client.connect(host, port)
                val settled = withTimeoutOrNull(timeoutMs) {
                    client.state.first { it !is ConnectionState.Connecting }
                }
                if (settled is ConnectionState.Connected) {
                    return true
                }
                client.disconnectSilently()
                return false
            }

            override suspend fun tryConnectRelay(config: RelayConfig, timeoutMs: Long): Boolean {
                client.connectRelay(config.url, config.deviceId, config.token)
                val settled = withTimeoutOrNull(timeoutMs) {
                    client.state.first { it !is ConnectionState.Connecting }
                }
                if (settled is ConnectionState.Connected) {
                    return true
                }
                client.disconnectSilently()
                return false
            }

            override fun stopDiscovery() {
                discovery.stop()
            }
        }
    )

    init {
        // Clear any old hardcoded default PC ID and Token if present in preferences
        val storedDevId = prefs.getString(KEY_RELAY_DEVICE_ID, null)
        val storedToken = prefs.getString(KEY_RELAY_TOKEN, null)
        if (storedDevId == "akash-pc" || storedToken == "rpad-7f4c9a2e8b1d6305") {
            val editor = prefs.edit()
            if (storedDevId == "akash-pc") editor.putString(KEY_RELAY_DEVICE_ID, "")
            if (storedToken == "rpad-7f4c9a2e8b1d6305") editor.putString(KEY_RELAY_TOKEN, "")
            editor.apply()
        }

        micSession = MicSessionController(
            adapter = object : MicSessionAdapter {
                override fun hasRecordAudioPermission(): Boolean {
                    return ContextCompat.checkSelfPermission(
                        appContext,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                }

                override fun currentConnectionState(): ConnectionState = connectionState.value

                override fun isAutoRecovering(): Boolean {
                    val orchestratorActive = synchronized(orchestratorLock) {
                        orchestratorJob?.isActive == true
                    }
                    return !userManuallyDisconnected &&
                        !reconnectBudget.isExhausted &&
                        (_autoConnect.value || orchestratorActive)
                }

                override fun isStreamingTo(host: String, port: Int): Boolean {
                    return micStreamer.isStreamingTo(host, port)
                }

                override fun startAudioStream(host: String, port: Int): Boolean {
                    return micStreamer.start(host, port)
                }

                override fun stopAudioStream() {
                    micStreamer.stop()
                }

                override fun acquireKeepAliveLocks() {
                    acquireMicKeepAliveLocks()
                }

                override fun releaseKeepAliveLocks() {
                    releaseMicKeepAliveLocks()
                }

                override fun requestMicrophoneForegroundService() {
                    RemoteConnectionService.startMic(appContext)
                }

                override fun releaseMicrophoneForegroundService() {
                    RemoteConnectionService.stopMic(appContext)
                }
            }
        )
        micStreamer.sendWebSocketBinary = { bytes ->
            client.sendBinary(bytes)
        }
        networkMonitor.start()
        observeConnectionLifecycle()
    }

    /**
     * Called when MainActivity opens/resumes. Preserves any active background connection,
     * and if disconnected with Auto-connect enabled, launches the Wi-Fi -> Internet (VPS) cycle
     * plus the Foreground Service so the connection stays alive after closing the app.
     */
    fun onAppOpened(isFreshLaunch: Boolean) {
        isUiAttached = true
        val state = connectionState.value
        if (state is ConnectionState.Connected || state is ConnectionState.Connecting) {
            userManuallyDisconnected = false
            ensureForegroundServiceRunning()
            return
        }

        val orchestratorActive = synchronized(orchestratorLock) { orchestratorJob?.isActive == true }
        if (orchestratorActive) {
            ensureForegroundServiceRunning()
            return
        }

        if (_autoConnect.value && ((!userManuallyDisconnected && !reconnectBudget.isExhausted) || isFreshLaunch)) {
            userManuallyDisconnected = false
            reconnectBudget.resetForUserAction()
            ensureForegroundServiceRunning()
            startWifiFirstConnectCycle(freshNetwork = !hasEverStartedSession, initialDelayMs = 0L)
        } else {
            // User manually disconnected or auto-reconnect exhausted; allow LAN discovery while drawer is visible
            discovery.start()
        }
    }

    fun onUiDetached() {
        isUiAttached = false
        // Do NOT disconnect LocalClient or stop RemoteConnectionService here!
        // Only stop passive LAN discovery if the user manually disconnected or auto-reconnect stopped.
        if (userManuallyDisconnected || reconnectBudget.isExhausted) {
            discovery.stop()
        }
    }

    /**
     * Called by RemoteConnectionService if restarted by Android (START_STICKY) or from
     * the notification "Reconnect" action.
     */
    fun onServiceStarted(forceReconnect: Boolean) {
        if (forceReconnect) {
            userManuallyDisconnected = false
            reconnectBudget.resetForUserAction()
            startWifiFirstConnectCycle(freshNetwork = true, initialDelayMs = 0L)
            return
        }
        if (userManuallyDisconnected || reconnectBudget.isExhausted) return
        val orchestratorActive = synchronized(orchestratorLock) { orchestratorJob?.isActive == true }
        if (orchestratorActive) return
        val state = connectionState.value
        if (state !is ConnectionState.Connected && state !is ConnectionState.Connecting) {
            if (_autoConnect.value) {
                startWifiFirstConnectCycle(freshNetwork = true, initialDelayMs = 0L)
            }
        }
    }

    fun onHostChanged(value: String) {
        _host.value = value
    }

    fun onPortChanged(value: String) {
        if (value.length <= 5 && value.all { it.isDigit() }) {
            _port.value = value
        }
    }

    fun onRelayUrlChanged(value: String) {
        _relayUrl.value = value
    }

    fun onRelayDeviceIdChanged(value: String) {
        _relayDeviceId.value = value
    }

    fun onRelayTokenChanged(value: String) {
        _relayToken.value = value
    }

    fun onAutoConnectChanged(enabled: Boolean) {
        _autoConnect.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_CONNECT, enabled).apply()
        if (enabled) {
            userManuallyDisconnected = false
            reconnectBudget.resetForUserAction()
            ensureForegroundServiceRunning()
            val state = connectionState.value
            if (state !is ConnectionState.Connected && state !is ConnectionState.Connecting) {
                startWifiFirstConnectCycle(freshNetwork = false, initialDelayMs = 0L)
            }
        } else {
            cancelOrchestrator()
            if (connectionState.value is ConnectionState.Connecting) {
                client.disconnect()
            }
            if (connectionState.value !is ConnectionState.Connected) {
                RemoteConnectionService.stop(appContext)
            }
        }
    }

    fun refreshDiscovery() {
        discovery.restart()
    }

    fun connectToDiscovered(pc: DiscoveredPc) {
        _host.value = pc.host
        _port.value = pc.port.toString()
        prefs.edit().putString(KEY_LAST_PC_NAME, pc.name).apply()
        connectManualLan()
    }

    fun connectManualLan() {
        cancelOrchestrator()
        userManuallyDisconnected = false
        reconnectBudget.resetForUserAction()
        hasEverStartedSession = true
        manualConnectInProgress = true
        ensureForegroundServiceRunning()

        val hostVal = _host.value.trim()
        val portVal = _port.value.toIntOrNull() ?: 8765
        prefs.edit()
            .putString(KEY_HOST, hostVal)
            .putString(KEY_PORT, portVal.toString())
            .apply()
        discovery.stop()
        client.connect(hostVal, portVal)
    }

    fun connectManualRelay() {
        cancelOrchestrator()
        userManuallyDisconnected = false
        reconnectBudget.resetForUserAction()
        hasEverStartedSession = true
        manualConnectInProgress = true
        ensureForegroundServiceRunning()

        val urlVal = _relayUrl.value.trim()
        val idVal = _relayDeviceId.value.trim()
        val tokenVal = _relayToken.value.trim()
        prefs.edit()
            .putString(KEY_RELAY_URL, urlVal)
            .putString(KEY_RELAY_DEVICE_ID, idVal)
            .putString(KEY_RELAY_TOKEN, tokenVal)
            .apply()
        discovery.stop()
        client.connectRelay(urlVal, idVal, tokenVal)
    }

    fun reconnectWifiFirst() {
        userManuallyDisconnected = false
        reconnectBudget.resetForUserAction()
        ensureForegroundServiceRunning()
        startWifiFirstConnectCycle(freshNetwork = true, initialDelayMs = 0L)
    }

    fun setMicEnabled(enabled: Boolean): Boolean = micSession.setMicEnabled(enabled)

    fun toggleMic(): Boolean = micSession.toggleMic()

    fun clearMicError() = micSession.clearMicError()

    fun shouldHoldMicrophoneForeground(): Boolean = micSession.shouldHoldMicrophoneForeground

    fun shouldKeepServiceAlive(): Boolean {
        if (userManuallyDisconnected || reconnectBudget.isExhausted) return false
        val orchestratorActive = synchronized(orchestratorLock) { orchestratorJob?.isActive == true }
        val state = connectionState.value
        return state is ConnectionState.Connected ||
            state is ConnectionState.Connecting ||
            orchestratorActive ||
            _autoConnect.value
    }

    fun onMicForegroundServiceReady() {
        scope.launch {
            micSession.onForegroundServiceMicReady()
        }
    }

    fun onMicForegroundServiceFailed(message: String) {
        micSession.onForegroundServiceMicFailed(message)
    }

    fun stopMicFromService() {
        micSession.stopMicFromService()
    }

    fun onForegroundServiceDestroyed() {
        micSession.onForegroundServiceDestroyed()
    }

    /**
     * Explicit user disconnect (from app "Off" button or notification "Disconnect" action).
     * Stops auto-reconnect, stops background microphone, AND stops the Foreground Service cleanly.
     */
    fun disconnectByUser() {
        userManuallyDisconnected = true
        manualConnectInProgress = false
        activeAttemptNumber = 0
        micSession.onUserDisconnect()
        cancelOrchestrator()
        synchronized(orchestratorLock) {
            networkDebounceJob?.cancel()
            networkDebounceJob = null
        }
        client.disconnect()
        if (isUiAttached) {
            discovery.start()
        } else {
            discovery.stop()
        }
        RemoteConnectionService.stop(appContext)
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireMicKeepAliveLocks() {
        synchronized(micLockGuard) {
            runCatching {
                val existingWake = micWakeLock
                if (existingWake == null || !existingWake.isHeld) {
                    val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    if (pm != null) {
                        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemotePad:MicStream").apply {
                            setReferenceCounted(false)
                            acquire()
                        }
                        micWakeLock = wl
                    }
                }
            }
            runCatching {
                val existingWifi = micWifiLock
                if (existingWifi == null || !existingWifi.isHeld) {
                    val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    if (wm != null) {
                        val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        } else {
                            @Suppress("DEPRECATION")
                            WifiManager.WIFI_MODE_FULL_HIGH_PERF
                        }
                        val lock = wm.createWifiLock(lockMode, "RemotePad:MicWifi").apply {
                            setReferenceCounted(false)
                            acquire()
                        }
                        micWifiLock = lock
                    }
                }
            }
        }
    }

    private fun releaseMicKeepAliveLocks() {
        synchronized(micLockGuard) {
            runCatching {
                micWakeLock?.let { wl ->
                    if (wl.isHeld) wl.release()
                }
            }
            micWakeLock = null
            runCatching {
                micWifiLock?.let { lock ->
                    if (lock.isHeld) lock.release()
                }
            }
            micWifiLock = null
        }
    }

    fun send(payload: String): Boolean = client.send(payload)
    fun sendMove(dx: Float, dy: Float): Boolean = client.sendMove(dx, dy)
    fun sendMoveAbs(normX: Float, normY: Float, monitor: Int = 0): Boolean = client.sendMoveAbs(normX, normY, monitor)
    fun sendScroll(dx: Float, dy: Float): Boolean = client.sendScroll(dx, dy)
    fun sendScreenAck(seq: Int, fromP2p: Boolean): Boolean = client.sendScreenAck(seq, fromP2p)
    fun resetScreenReassembler() = client.resetScreenReassembler()
    fun queuedBytes(): Long = client.queuedBytes()

    private fun ensureForegroundServiceRunning() {
        if (!RemoteConnectionService.isRunning) {
            RemoteConnectionService.start(appContext)
        }
    }

    private fun observeConnectionLifecycle() {
        scope.launch {
            var previouslyConnected = false
            client.state.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        previouslyConnected = true
                        manualConnectInProgress = false
                        reconnectBudget.onConnected()
                        ensureForegroundServiceRunning()
                        discovery.stop()
                        if (state.pcName.isNotBlank() && state.pcName != _host.value.trim()) {
                            prefs.edit().putString(KEY_LAST_PC_NAME, state.pcName).apply()
                        }
                        micSession.onConnectionStateChanged(state)
                    }
                    is ConnectionState.Connecting -> {
                        micSession.onConnectionStateChanged(state)
                    }
                    ConnectionState.Disconnected, is ConnectionState.Error -> {
                        val orchestratorActive = synchronized(orchestratorLock) {
                            orchestratorJob?.isActive == true
                        }
                        if (orchestratorActive) {
                            micSession.onConnectionStateChanged(state)
                            return@collect
                        }

                        if (userManuallyDisconnected || reconnectBudget.isExhausted) {
                            previouslyConnected = false
                            manualConnectInProgress = false
                            micSession.onConnectionStateChanged(state)
                            if (isUiAttached) discovery.start() else discovery.stop()
                            return@collect
                        }

                        // If a live connection unexpectedly dropped (after previously connecting),
                        // automatically re-run Wi-Fi first -> Internet (VPS) with a fresh 5-attempt budget.
                        val shouldRecover = previouslyConnected || (_autoConnect.value && !manualConnectInProgress)
                        previouslyConnected = false
                        manualConnectInProgress = false

                        if (shouldRecover && _autoConnect.value) {
                            startWifiFirstConnectCycle(freshNetwork = false, initialDelayMs = 1200L)
                            micSession.onConnectionStateChanged(state)
                        } else {
                            micSession.onConnectionStateChanged(state)
                            RemoteConnectionService.stop(appContext)
                            if (isUiAttached) discovery.start() else discovery.stop()
                        }
                    }
                }
            }
        }
    }

    private fun handleNetworkChanged(snapshot: NetworkSnapshot) {
        if (userManuallyDisconnected || reconnectBudget.isExhausted) return
        val activeOrAuto = _autoConnect.value ||
            connectionState.value is ConnectionState.Connected ||
            connectionState.value is ConnectionState.Connecting
        if (!activeOrAuto) return

        synchronized(orchestratorLock) {
            networkDebounceJob?.cancel()
            networkDebounceJob = scope.launch {
                // Brief debounce so rapid Wi-Fi <-> Cellular handoff + DHCP IPv4 assignment
                // settle into a single clean Wi-Fi -> Internet (VPS) switch.
                delay(NETWORK_SWITCH_DEBOUNCE_MS)
                if (userManuallyDisconnected || reconnectBudget.isExhausted) return@launch

                val current = networkMonitor.queryCurrentSnapshot()
                lastNetworkEventMs = SystemClock.elapsedRealtime()

                if (!current.hasNetwork) {
                    cancelOrchestrator()
                    client.evictConnections()
                    client.disconnectSilently()
                    discovery.stop()
                    discovery.clearDiscovered()
                    client.setConnectingStatus("Waiting for network...")
                    return@launch
                }

                // Network genuinely changed -> always tear down old-network sockets and
                // retry Local Wi-Fi first, then Internet (VPS) on the new network
                // (preserving remaining attempts in reconnectBudget so network flaps never loop forever).
                startWifiFirstConnectCycle(freshNetwork = true, initialDelayMs = 0L)
            }
        }
    }

    private fun startWifiFirstConnectCycle(freshNetwork: Boolean, initialDelayMs: Long) {
        if (userManuallyDisconnected || reconnectBudget.isExhausted) return
        hasEverStartedSession = true
        manualConnectInProgress = false

        synchronized(orchestratorLock) {
            orchestratorJob?.cancel()
            orchestratorJob = scope.launch {
                if (initialDelayMs > 0L) {
                    delay(initialDelayMs)
                }

                if (freshNetwork) {
                    client.evictConnections()
                    client.disconnectSilently()
                    discovery.clearDiscovered()
                }

                val result = connector.connectWithBudget(
                    budget = reconnectBudget,
                    freshNetwork = freshNetwork,
                    isCancelled = { !isActive || userManuallyDisconnected },
                    allowRetry = { _autoConnect.value },
                    onAttemptStarting = { attemptNumber, _ ->
                        activeAttemptNumber = attemptNumber
                    },
                    onWaitBeforeRetry = { failedAttempts, maxAttempts ->
                        client.setConnectingStatus("Retrying ($failedAttempts/$maxAttempts)...")
                        // Clear stale unreachable LAN entries before waiting so backoff pauses cleanly
                        // unless a live PC replies with a fresh UDP announce during the wait window.
                        discovery.clearDiscovered()
                        discovery.start()
                        val backoffMs = retryBackoffMs((failedAttempts - 1).coerceAtLeast(0))
                        withTimeoutOrNull(backoffMs) {
                            discovery.discoveredPcs.first { it.isNotEmpty() }
                        }
                    },
                )

                activeAttemptNumber = 0
                when (result) {
                    ReconnectCycleResult.CONNECTED_WIFI,
                    ReconnectCycleResult.CONNECTED_VPS,
                    -> {
                        reconnectBudget.onConnected()
                        ensureForegroundServiceRunning()
                    }
                    ReconnectCycleResult.NO_NETWORK -> {
                        discovery.stop()
                        client.disconnectSilently()
                        client.setConnectingStatus("Waiting for network...")
                        // Park until NetworkMonitor delivers an online network and cancels/restarts us
                    }
                    ReconnectCycleResult.EXHAUSTED -> {
                        client.setErrorStatus(
                            "Reconnect stopped (${reconnectBudget.maxAttempts}/${reconnectBudget.maxAttempts} attempts)"
                        )
                        if (isUiAttached) discovery.start() else discovery.stop()
                        RemoteConnectionService.stop(appContext)
                    }
                    ReconnectCycleResult.STOPPED -> {
                        if (connectionState.value is ConnectionState.Connecting) {
                            client.disconnect()
                        }
                        if (isUiAttached) discovery.start() else discovery.stop()
                        if (connectionState.value !is ConnectionState.Connected) {
                            RemoteConnectionService.stop(appContext)
                        }
                    }
                }
            }
        }
    }

    private fun cancelOrchestrator() {
        activeAttemptNumber = 0
        synchronized(orchestratorLock) {
            orchestratorJob?.cancel()
            orchestratorJob = null
        }
    }

    private fun selectPreferredPc(pcs: List<DiscoveredPc>): DiscoveredPc? {
        if (pcs.isEmpty()) return null
        val savedHost = _host.value.trim()
        val savedName = prefs.getString(KEY_LAST_PC_NAME, null)
        return pcs.firstOrNull { it.host == savedHost }
            ?: pcs.firstOrNull { !savedName.isNullOrBlank() && it.name.equals(savedName, ignoreCase = true) }
            ?: pcs.first()
    }

    companion object {
        private const val PREFS_NAME = "remotepad_prefs"
        private const val KEY_HOST = "last_host"
        private const val KEY_PORT = "last_port"
        private const val KEY_RELAY_URL = "relay_url"
        private const val KEY_RELAY_DEVICE_ID = "relay_device_id"
        private const val KEY_RELAY_TOKEN = "relay_token"
        private const val KEY_LAST_PC_NAME = "last_pc_name"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val DEFAULT_PORT = "8765"
        private const val DEFAULT_RELAY_URL = "ws://182.255.88.108:8770"
        private const val DEFAULT_RELAY_DEVICE_ID = ""
        private const val DEFAULT_RELAY_TOKEN = ""
        private const val NETWORK_SWITCH_DEBOUNCE_MS = 650L

        internal fun retryBackoffMs(attempt: Int): Long = when (attempt) {
            0 -> 3_000L
            1 -> 5_000L
            2 -> 8_000L
            else -> 12_000L
        }

        @Volatile
        private var instance: ConnectionManager? = null

        fun getInstance(context: Context): ConnectionManager {
            return instance ?: synchronized(this) {
                instance ?: ConnectionManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
