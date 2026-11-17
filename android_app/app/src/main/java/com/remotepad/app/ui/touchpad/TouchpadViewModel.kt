package com.remotepad.app.ui.touchpad

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remotepad.app.data.model.ButtonState
import com.remotepad.app.data.model.ModifierKey
import com.remotepad.app.data.model.ModifierState
import com.remotepad.app.data.model.MouseButton
import com.remotepad.app.input.AirMouseController
import com.remotepad.app.input.MicStreamer
import com.remotepad.app.network.ConnectionState
import com.remotepad.app.network.DiscoveredPc
import com.remotepad.app.network.LanDiscovery
import com.remotepad.app.network.LocalClient
import com.remotepad.app.network.P2PUdpClient
import com.remotepad.app.network.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

class TouchpadViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val client = LocalClient(application.applicationContext)
    private val discovery = LanDiscovery(application.applicationContext)

    val connectionState: StateFlow<ConnectionState> = client.state
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
        prefs.getString(KEY_RELAY_DEVICE_ID, DEFAULT_RELAY_DEVICE_ID) ?: DEFAULT_RELAY_DEVICE_ID
    )
    val relayDeviceId: StateFlow<String> = _relayDeviceId.asStateFlow()

    private val _relayToken = MutableStateFlow(
        prefs.getString(KEY_RELAY_TOKEN, DEFAULT_RELAY_TOKEN) ?: DEFAULT_RELAY_TOKEN
    )
    val relayToken: StateFlow<String> = _relayToken.asStateFlow()

    private val _autoConnect = MutableStateFlow(prefs.getBoolean(KEY_AUTO_CONNECT, true))
    val autoConnect: StateFlow<Boolean> = _autoConnect.asStateFlow()

    private val _sensitivity = MutableStateFlow(prefs.getFloat(KEY_SENSITIVITY, 1.2f))
    val sensitivity: StateFlow<Float> = _sensitivity.asStateFlow()

    private val _airMouseSpeed = MutableStateFlow(prefs.getFloat(KEY_AIR_MOUSE_SPEED, 1.2f))
    val airMouseSpeed: StateFlow<Float> = _airMouseSpeed.asStateFlow()

    private val _isAirMouseEnabled = MutableStateFlow(false)
    val isAirMouseEnabled: StateFlow<Boolean> = _isAirMouseEnabled.asStateFlow()

    private val _isKeyboardOpen = MutableStateFlow(false)
    val isKeyboardOpen: StateFlow<Boolean> = _isKeyboardOpen.asStateFlow()

    private val _pcVolume = MutableStateFlow(0.5f)
    val pcVolume: StateFlow<Float> = _pcVolume.asStateFlow()

    private val _pcMuted = MutableStateFlow(false)
    val pcMuted: StateFlow<Boolean> = _pcMuted.asStateFlow()

    private val _showVolumeModal = MutableStateFlow(false)
    val showVolumeModal: StateFlow<Boolean> = _showVolumeModal.asStateFlow()

    private val _isMicEnabled = MutableStateFlow(false)
    val isMicEnabled: StateFlow<Boolean> = _isMicEnabled.asStateFlow()

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val _micError = MutableStateFlow<String?>(null)
    val micError: StateFlow<String?> = _micError.asStateFlow()

    private val _modifierStates = MutableStateFlow(
        ModifierKey.entries.associateWith { ModifierState.OFF }
    )
    val modifierStates: StateFlow<Map<ModifierKey, ModifierState>> = _modifierStates.asStateFlow()

    private val airMouseController = AirMouseController(application.applicationContext) { dx, dy ->
        onAirMouseDelta(dx, dy)
    }.apply {
        speedMultiplier = _airMouseSpeed.value
    }
    val isGyroAvailable: Boolean get() = airMouseController.isAvailable

    private val micStreamer = MicStreamer(
        onLevelChanged = { lvl -> _micLevel.value = lvl },
        onError = { msg ->
            _micError.value = msg
            _isMicEnabled.value = false
        },
    )

    @Volatile
    private var appInForeground = true

    @Volatile
    private var mediaPanelOpen = false
    private var volumeSyncSentEnabled = false
    private var volumeModalDismissJob: Job? = null
    private var lastLocalVolumeSendMs = 0L
    private var lastLocalVolumeEditMs = 0L

    @Volatile
    private var userManuallyDisconnected = false
    private var lastAutoConnectAttemptMs = 0L
    private var lastRelayAutoFallbackMs = 0L
    private var internetFallbackJob: Job? = null

    // Event-driven coalescing delta buffers flushed on Dispatchers.IO with zero polling jitter
    private val deltaLock = Any()
    private var pendingMoveX = 0f
    private var pendingMoveY = 0f
    private var pendingScrollX = 0f
    private var pendingScrollY = 0f
    private val flushSignal = Channel<Unit>(Channel.CONFLATED)

    init {
        startCoalescedSenderLoop()
        observeDiscoveryAndAutoConnect()
        observeRemoteVolume()
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

    fun connectViaRelay() {
        cancelInternetFallback()
        userManuallyDisconnected = false
        val urlVal = _relayUrl.value.trim()
        val idVal = _relayDeviceId.value.trim()
        val tokenVal = _relayToken.value.trim()
        prefs.edit()
            .putString(KEY_RELAY_URL, urlVal)
            .putString(KEY_RELAY_DEVICE_ID, idVal)
            .putString(KEY_RELAY_TOKEN, tokenVal)
            .apply()
        client.connectRelay(urlVal, idVal, tokenVal)
    }

    fun onAutoConnectChanged(enabled: Boolean) {
        _autoConnect.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_CONNECT, enabled).apply()
        if (enabled) {
            userManuallyDisconnected = false
            lastAutoConnectAttemptMs = 0L
            lastRelayAutoFallbackMs = 0L
            val state = connectionState.value
            if (state !is ConnectionState.Connected && state !is ConnectionState.Connecting) {
                val target = selectPreferredPc(discoveredPcs.value)
                if (target != null) {
                    connectToDiscovered(target)
                } else {
                    discovery.probeNow()
                    scheduleInternetAutoFallback()
                }
            }
        } else {
            cancelInternetFallback()
        }
    }

    fun refreshDiscovery() {
        discovery.probeNow()
    }

    fun connectToDiscovered(pc: DiscoveredPc) {
        _host.value = pc.host
        _port.value = pc.port.toString()
        prefs.edit().putString(KEY_LAST_PC_NAME, pc.name).apply()
        connect()
    }

    fun onSensitivityChanged(value: Float) {
        val clamped = value.coerceIn(0.4f, 3.0f)
        _sensitivity.value = clamped
        prefs.edit().putFloat(KEY_SENSITIVITY, clamped).apply()
    }

    fun setAirMouseEnabled(enabled: Boolean) {
        val actual = enabled && isGyroAvailable
        _isAirMouseEnabled.value = actual
        updateAirMouseSensorState()
    }

    fun toggleAirMouse() {
        setAirMouseEnabled(!_isAirMouseEnabled.value)
    }

    fun onAirMouseSpeedChanged(value: Float) {
        val stepped = (kotlin.math.round(value * 10f) / 10f).coerceIn(AIR_MOUSE_MIN_SPEED, AIR_MOUSE_MAX_SPEED)
        _airMouseSpeed.value = stepped
        airMouseController.speedMultiplier = stepped
        prefs.edit().putFloat(KEY_AIR_MOUSE_SPEED, stepped).apply()
    }

    fun increaseAirMouseSpeed() {
        onAirMouseSpeedChanged(_airMouseSpeed.value + AIR_MOUSE_SPEED_STEP)
    }

    fun decreaseAirMouseSpeed() {
        onAirMouseSpeedChanged(_airMouseSpeed.value - AIR_MOUSE_SPEED_STEP)
    }

    fun setAppInForeground(inForeground: Boolean) {
        appInForeground = inForeground
        if (!inForeground && _showVolumeModal.value) {
            dismissVolumeModal()
        }
        updateAirMouseSensorState()
        updateVolumeSyncState()
    }

    fun setMediaPanelOpen(open: Boolean) {
        mediaPanelOpen = open
        updateVolumeSyncState()
    }

    fun onHardwareVolumeKey(up: Boolean) {
        if (!_showVolumeModal.value) {
            _showVolumeModal.value = true
            updateVolumeSyncState()
        }
        scheduleVolumeModalDismiss()
        if (connectionState.value is ConnectionState.Connected) {
            client.send(Protocol.encodeMedia(if (up) "vol_up" else "vol_down"))
        } else {
            // Update local visual indicator gracefully even when disconnected
            val step = if (up) 0.04f else -0.04f
            _pcVolume.value = (_pcVolume.value + step).coerceIn(0f, 1f)
            if (up) _pcMuted.value = false
        }
    }

    fun keepVolumeModalAlive(dragging: Boolean = false) {
        if (!_showVolumeModal.value) return
        if (dragging) {
            volumeModalDismissJob?.cancel()
            volumeModalDismissJob = null
        } else {
            scheduleVolumeModalDismiss()
        }
    }

    fun dismissVolumeModal() {
        volumeModalDismissJob?.cancel()
        volumeModalDismissJob = null
        if (_showVolumeModal.value) {
            _showVolumeModal.value = false
            updateVolumeSyncState()
        }
    }

    private fun scheduleVolumeModalDismiss() {
        volumeModalDismissJob?.cancel()
        volumeModalDismissJob = viewModelScope.launch {
            delay(VOLUME_MODAL_AUTO_DISMISS_MS)
            _showVolumeModal.value = false
            updateVolumeSyncState()
        }
    }

    fun setPcVolume(level: Float, immediate: Boolean = false) {
        val clamped = level.coerceIn(0f, 1f)
        val now = SystemClock.elapsedRealtime()
        lastLocalVolumeEditMs = now
        _pcVolume.value = clamped
        if (clamped > 0f) {
            _pcMuted.value = false
        }

        if (connectionState.value !is ConnectionState.Connected) return
        if (immediate || (now - lastLocalVolumeSendMs) >= VOLUME_SEND_THROTTLE_MS) {
            lastLocalVolumeSendMs = now
            client.send(Protocol.encodeVolumeSet(clamped))
        }
    }

    private fun updateVolumeSyncState() {
        val isConnected = connectionState.value is ConnectionState.Connected
        val shouldSync = (mediaPanelOpen || _showVolumeModal.value) && appInForeground && isConnected
        if (!isConnected) {
            volumeSyncSentEnabled = false
            return
        }
        if (shouldSync != volumeSyncSentEnabled) {
            volumeSyncSentEnabled = shouldSync
            client.send(Protocol.encodeVolumeSync(shouldSync))
        }
    }

    private fun observeRemoteVolume() {
        viewModelScope.launch {
            client.volumeState.collect { remote ->
                if (!remote.synced) return@collect
                // Ignore stale echoes while the user is actively dragging the volume arc
                val now = SystemClock.elapsedRealtime()
                if (now - lastLocalVolumeEditMs < VOLUME_ECHO_GUARD_MS) return@collect
                _pcVolume.value = remote.level
                _pcMuted.value = remote.muted
            }
        }
    }

    private fun updateAirMouseSensorState() {
        val shouldRun = _isAirMouseEnabled.value &&
            appInForeground &&
            connectionState.value is ConnectionState.Connected
        if (shouldRun) {
            airMouseController.start()
        } else {
            airMouseController.stop()
        }
    }

    fun setMicEnabled(enabled: Boolean) {
        _micError.value = null
        if (!enabled) {
            _isMicEnabled.value = false
            micStreamer.stop()
            return
        }
        val connected = connectionState.value as? ConnectionState.Connected
        if (connected == null) {
            _micError.value = "Connect to PC first"
            _isMicEnabled.value = false
            return
        }
        if (connected.viaRelay || connected.host.isBlank()) {
            _micError.value = "PC Mic works on Local Wi-Fi only"
            _isMicEnabled.value = false
            return
        }
        val started = micStreamer.start(connected.host, connected.micPort)
        _isMicEnabled.value = started
    }

    fun toggleMic() {
        setMicEnabled(!_isMicEnabled.value)
    }

    fun toggleKeyboard() {
        _isKeyboardOpen.update { !it }
    }

    fun setKeyboardOpen(open: Boolean) {
        _isKeyboardOpen.value = open
    }

    fun connect() {
        cancelInternetFallback()
        userManuallyDisconnected = false
        val hostVal = _host.value.trim()
        val portVal = _port.value.toIntOrNull() ?: 8765
        prefs.edit()
            .putString(KEY_HOST, hostVal)
            .putString(KEY_PORT, portVal.toString())
            .apply()
        client.connect(hostVal, portVal)
    }

    fun disconnect() {
        cancelInternetFallback()
        userManuallyDisconnected = true
        setMicEnabled(false)
        releaseAllModifiers()
        clearPendingDeltas()
        client.disconnect()
    }

    fun onMoveDelta(dxPx: Float, dyPx: Float, density: Float) {
        if (connectionState.value !is ConnectionState.Connected) return
        val scale = _sensitivity.value / density.coerceAtLeast(1f) * 1.6f
        synchronized(deltaLock) {
            pendingMoveX += dxPx * scale
            pendingMoveY += dyPx * scale
        }
        flushSignal.trySend(Unit)
    }

    fun onAirMouseDelta(dx: Float, dy: Float) {
        if (connectionState.value !is ConnectionState.Connected) return
        synchronized(deltaLock) {
            pendingMoveX += dx
            pendingMoveY += dy
        }
        flushSignal.trySend(Unit)
    }

    fun onScrollDelta(dxPx: Float, dyPx: Float, density: Float) {
        if (connectionState.value !is ConnectionState.Connected) return
        val scale = 1f / (density.coerceAtLeast(1f) * 32f)
        synchronized(deltaLock) {
            pendingScrollX += dxPx * scale
            pendingScrollY += -dyPx * scale
        }
        flushSignal.trySend(Unit)
    }

    fun onClick(button: MouseButton, count: Int = 1) {
        flushPendingMoveNow()
        client.send(Protocol.encodeClick(button, count))
    }

    fun onButton(button: MouseButton, down: Boolean) {
        flushPendingMoveNow()
        val state = if (down) ButtonState.DOWN else ButtonState.UP
        client.send(Protocol.encodeButton(button, state))
    }

    fun sendGesture(gesture: String) {
        client.send(Protocol.encodeGesture(gesture))
    }

    fun sendMediaAction(action: String) {
        client.send(Protocol.encodeMedia(action))
    }

    fun sendPowerAction(action: String) {
        client.send(Protocol.encodePower(action))
    }

    fun sendKeyCombo(keys: List<String>) {
        if (keys.isEmpty()) return
        client.send(Protocol.encodeKeyCombo(keys))
    }

    /**
     * Cycles a sticky modifier:
     * OFF -> ACTIVE (one-shot for next key) -> LOCKED (held via keyevt down) -> OFF (keyevt up)
     */
    fun toggleModifier(mod: ModifierKey) {
        val current = _modifierStates.value[mod] ?: ModifierState.OFF
        val next = when (current) {
            ModifierState.OFF -> ModifierState.ACTIVE
            ModifierState.ACTIVE -> {
                client.send(Protocol.encodeKeyEvent(mod.wireValue, ButtonState.DOWN))
                ModifierState.LOCKED
            }
            ModifierState.LOCKED -> {
                client.send(Protocol.encodeKeyEvent(mod.wireValue, ButtonState.UP))
                ModifierState.OFF
            }
        }
        _modifierStates.update { it + (mod to next) }
    }

    fun releaseAllModifiers() {
        val states = _modifierStates.value
        states.forEach { (mod, state) ->
            if (state == ModifierState.LOCKED) {
                client.send(Protocol.encodeKeyEvent(mod.wireValue, ButtonState.UP))
            }
        }
        _modifierStates.value = ModifierKey.entries.associateWith { ModifierState.OFF }
    }

    fun sendSpecialKey(keyWireValue: String) {
        val activeMods = consumeActiveModifiers()
        val keys = activeMods + keyWireValue
        client.send(Protocol.encodeKeyCombo(keys))
    }

    fun sendTypedText(text: String) {
        if (text.isEmpty()) return

        val states = _modifierStates.value
        val activeMods = states.filterValues { it == ModifierState.ACTIVE }.keys.map { it.wireValue }
        val hasLockedMod = states.values.any { it == ModifierState.LOCKED }

        if ((activeMods.isNotEmpty() || hasLockedMod) && text.length == 1) {
            val mappedKey = mapCharToShortcutKey(text[0])
            if (mappedKey != null) {
                clearActiveModifiers()
                client.send(Protocol.encodeKeyCombo(activeMods + mappedKey))
                return
            }
        }

        if (activeMods.isNotEmpty()) {
            clearActiveModifiers()
        }
        client.send(Protocol.encodeText(text))
    }

    fun sendParagraphText(text: String) {
        if (text.isEmpty()) return
        clearActiveModifiers()
        client.send(Protocol.encodePaste(text))
    }

    private fun consumeActiveModifiers(): List<String> {
        val active = _modifierStates.value
            .filterValues { it == ModifierState.ACTIVE }
            .keys
            .map { it.wireValue }
        if (active.isNotEmpty()) {
            clearActiveModifiers()
        }
        return active
    }

    private fun clearActiveModifiers() {
        _modifierStates.update { map ->
            map.mapValues { (_, state) ->
                if (state == ModifierState.ACTIVE) ModifierState.OFF else state
            }
        }
    }

    private fun mapCharToShortcutKey(ch: Char): String? {
        val lower = ch.lowercaseChar()
        return when {
            lower in 'a'..'z' -> lower.toString()
            lower in '0'..'9' -> lower.toString()
            lower == ' ' -> "space"
            lower in ";=,-./`[\\]'" -> lower.toString()
            else -> null
        }
    }

    private fun startCoalescedSenderLoop() {
        viewModelScope.launch(Dispatchers.IO) {
            var lastSendMs = 0L
            for (signal in flushSignal) {
                while (isActive) {
                    if (connectionState.value !is ConnectionState.Connected) {
                        clearPendingDeltas()
                        break
                    }
                    if (!hasSendableDeltas()) break

                    // Backpressure: if OkHttp still has unsent move frames queued on the socket,
                    // wait briefly so new touch deltas coalesce into one fresh packet instead of
                    // building up stale lag in the send buffer.
                    if (client.queuedBytes() > MAX_QUEUED_BYTES) {
                        delay(BACKPRESSURE_DELAY_MS)
                        continue
                    }

                    val now = SystemClock.elapsedRealtime()
                    val sinceLast = now - lastSendMs
                    if (sinceLast in 0 until MIN_SEND_INTERVAL_MS) {
                        delay(MIN_SEND_INTERVAL_MS - sinceLast)
                    }

                    val sentAny = flushPendingDeltasNow()
                    if (!sentAny) break
                    lastSendMs = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    private fun hasSendableDeltas(): Boolean {
        synchronized(deltaLock) {
            return abs(pendingMoveX) >= MIN_MOVE_EPSILON ||
                abs(pendingMoveY) >= MIN_MOVE_EPSILON ||
                abs(pendingScrollX) >= MIN_SCROLL_EPSILON ||
                abs(pendingScrollY) >= MIN_SCROLL_EPSILON
        }
    }

    private fun flushPendingMoveNow(): Boolean {
        var mx = 0f
        var my = 0f
        synchronized(deltaLock) {
            if (abs(pendingMoveX) >= MIN_MOVE_EPSILON || abs(pendingMoveY) >= MIN_MOVE_EPSILON) {
                mx = pendingMoveX
                my = pendingMoveY
                pendingMoveX = 0f
                pendingMoveY = 0f
            }
        }
        if (mx == 0f && my == 0f) return false
        return client.sendMove(mx, my)
    }

    private fun flushPendingDeltasNow(): Boolean {
        var mx = 0f
        var my = 0f
        var sx = 0f
        var sy = 0f
        synchronized(deltaLock) {
            if (abs(pendingMoveX) >= MIN_MOVE_EPSILON || abs(pendingMoveY) >= MIN_MOVE_EPSILON) {
                mx = pendingMoveX
                my = pendingMoveY
                pendingMoveX = 0f
                pendingMoveY = 0f
            }
            if (abs(pendingScrollX) >= MIN_SCROLL_EPSILON || abs(pendingScrollY) >= MIN_SCROLL_EPSILON) {
                sx = pendingScrollX
                sy = pendingScrollY
                pendingScrollX = 0f
                pendingScrollY = 0f
            }
        }

        var sent = false
        if (mx != 0f || my != 0f) {
            sent = client.sendMove(mx, my) || sent
        }
        if (sx != 0f || sy != 0f) {
            sent = client.sendScroll(sx, sy) || sent
        }
        return sent
    }

    private fun clearPendingDeltas() {
        synchronized(deltaLock) {
            pendingMoveX = 0f
            pendingMoveY = 0f
            pendingScrollX = 0f
            pendingScrollY = 0f
        }
    }

    private fun observeDiscoveryAndAutoConnect() {
        // Keep LAN discovery active whenever disconnected, and stop it once connected
        // to save battery and keep Wi-Fi dedicated to low-latency touchpad frames.
        viewModelScope.launch {
            connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        cancelInternetFallback()
                        discovery.stop()
                        updateAirMouseSensorState()
                        updateVolumeSyncState()
                        if (state.pcName.isNotBlank() && state.pcName != _host.value.trim()) {
                            prefs.edit().putString(KEY_LAST_PC_NAME, state.pcName).apply()
                        }
                    }
                    is ConnectionState.Connecting -> {
                        cancelInternetFallback()
                        discovery.stop()
                        updateAirMouseSensorState()
                        updateVolumeSyncState()
                    }
                    ConnectionState.Disconnected, is ConnectionState.Error -> {
                        setMicEnabled(false)
                        updateAirMouseSensorState()
                        updateVolumeSyncState()
                        discovery.start()
                        if (_autoConnect.value && !userManuallyDisconnected) {
                            scheduleInternetAutoFallback()
                        }
                    }
                }
            }
        }

        // Tier 1: Auto-connect to the preferred Local Wi-Fi PC immediately when discovered
        // (highest priority, 2-5 ms latency).
        viewModelScope.launch {
            combine(
                discoveredPcs,
                connectionState,
                autoConnect,
            ) { pcs, state, isAutoEnabled ->
                Triple(pcs, state, isAutoEnabled)
            }.collect { (pcs, state, isAutoEnabled) ->
                if (!isAutoEnabled || userManuallyDisconnected) return@collect
                if (state is ConnectionState.Connected || state is ConnectionState.Connecting) {
                    return@collect
                }
                val target = selectPreferredPc(pcs) ?: return@collect
                cancelInternetFallback()

                val now = SystemClock.elapsedRealtime()
                if (now - lastAutoConnectAttemptMs < AUTO_CONNECT_COOLDOWN_MS) {
                    return@collect
                }
                lastAutoConnectAttemptMs = now
                connectToDiscovered(target)
            }
        }
    }

    /**
     * Tier 2 & 3 auto-fallback: if Local Wi-Fi finds no PC within LAN_DISCOVERY_GRACE_MS
     * (e.g., user is outside on mobile data / external Wi-Fi), automatically connect via
     * Internet (VPS), which immediately upgrades to Direct P2P UDP (~25-40 ms) and falls
     * back to VPS Relay if strict NAT blocks UDP.
     */
    private fun scheduleInternetAutoFallback() {
        if (!_autoConnect.value || userManuallyDisconnected) return
        if (_relayUrl.value.isBlank() || _relayDeviceId.value.trim().length < 3 || _relayToken.value.trim().length < 8) {
            return
        }
        if (internetFallbackJob?.isActive == true) return
        internetFallbackJob = viewModelScope.launch {
            delay(LAN_DISCOVERY_GRACE_MS)
            if (!_autoConnect.value || userManuallyDisconnected) return@launch
            val state = connectionState.value
            if (state is ConnectionState.Connected || state is ConnectionState.Connecting) return@launch
            if (discoveredPcs.value.isNotEmpty()) return@launch

            val now = SystemClock.elapsedRealtime()
            if (now - lastRelayAutoFallbackMs < RELAY_AUTO_FALLBACK_COOLDOWN_MS) return@launch
            lastRelayAutoFallbackMs = now
            connectViaRelay()
        }
    }

    private fun cancelInternetFallback() {
        internetFallbackJob?.cancel()
        internetFallbackJob = null
    }

    private fun selectPreferredPc(pcs: List<DiscoveredPc>): DiscoveredPc? {
        if (pcs.isEmpty()) return null
        val savedHost = _host.value.trim()
        val savedName = prefs.getString(KEY_LAST_PC_NAME, null)
        return pcs.firstOrNull { it.host == savedHost }
            ?: pcs.firstOrNull { !savedName.isNullOrBlank() && it.name.equals(savedName, ignoreCase = true) }
            ?: pcs.first()
    }

    override fun onCleared() {
        super.onCleared()
        micStreamer.stop()
        airMouseController.stop()
        flushSignal.close()
        discovery.shutdown()
        client.shutdown()
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
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_AIR_MOUSE_SPEED = "air_mouse_speed"
        private const val DEFAULT_PORT = "8765"
        private const val DEFAULT_RELAY_URL = "ws://182.255.88.108:8770"
        private const val DEFAULT_RELAY_DEVICE_ID = "akash-pc"
        private const val DEFAULT_RELAY_TOKEN = "rpad-7f4c9a2e8b1d6305"
        private const val AIR_MOUSE_MIN_SPEED = 0.2f
        private const val AIR_MOUSE_MAX_SPEED = 4.0f
        private const val AIR_MOUSE_SPEED_STEP = 0.2f
        private const val VOLUME_SEND_THROTTLE_MS = 35L
        private const val VOLUME_ECHO_GUARD_MS = 180L
        private const val VOLUME_MODAL_AUTO_DISMISS_MS = 3000L
        private const val AUTO_CONNECT_COOLDOWN_MS = 3000L
        private const val LAN_DISCOVERY_GRACE_MS = 2800L
        private const val RELAY_AUTO_FALLBACK_COOLDOWN_MS = 12_000L
        private const val MIN_SEND_INTERVAL_MS = 8L
        private const val BACKPRESSURE_DELAY_MS = 4L
        private const val MAX_QUEUED_BYTES = 256L
        private const val MIN_MOVE_EPSILON = 0.05f
        private const val MIN_SCROLL_EPSILON = 0.02f
    }
}
