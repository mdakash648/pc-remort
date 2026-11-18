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
import com.remotepad.app.network.ConnectionManager
import com.remotepad.app.network.ConnectionState
import com.remotepad.app.network.DiscoveredPc
import com.remotepad.app.network.P2PUdpClient
import com.remotepad.app.network.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

class TouchpadViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val connectionManager = ConnectionManager.getInstance(application.applicationContext)

    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState
    val p2pClient: P2PUdpClient get() = connectionManager.p2pClient
    val discoveredPcs: StateFlow<List<DiscoveredPc>> = connectionManager.discoveredPcs
    val isScanning: StateFlow<Boolean> = connectionManager.isScanning

    val host: StateFlow<String> = connectionManager.host
    val port: StateFlow<String> = connectionManager.port
    val relayUrl: StateFlow<String> = connectionManager.relayUrl
    val relayDeviceId: StateFlow<String> = connectionManager.relayDeviceId
    val relayToken: StateFlow<String> = connectionManager.relayToken
    val autoConnect: StateFlow<Boolean> = connectionManager.autoConnect

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

    val isMicEnabled: StateFlow<Boolean> = connectionManager.isMicEnabled
    val micLevel: StateFlow<Float> = connectionManager.micLevel
    val micError: StateFlow<String?> = connectionManager.micError

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

    @Volatile
    private var appInForeground = true

    @Volatile
    private var mediaPanelOpen = false
    private var volumeSyncSentEnabled = false
    private var volumeModalDismissJob: Job? = null
    private var lastLocalVolumeSendMs = 0L
    private var lastLocalVolumeEditMs = 0L

    // Event-driven coalescing delta buffers flushed on Dispatchers.IO with zero polling jitter
    private val deltaLock = Any()
    private var pendingMoveX = 0f
    private var pendingMoveY = 0f
    private var pendingScrollX = 0f
    private var pendingScrollY = 0f
    private val flushSignal = Channel<Unit>(Channel.CONFLATED)

    init {
        startCoalescedSenderLoop()
        observeConnectionForUiFeatures()
        observeRemoteVolume()
    }

    fun onAppOpened(isFreshLaunch: Boolean) {
        connectionManager.onAppOpened(isFreshLaunch = isFreshLaunch)
    }

    fun onHostChanged(value: String) = connectionManager.onHostChanged(value)

    fun onPortChanged(value: String) = connectionManager.onPortChanged(value)

    fun onRelayUrlChanged(value: String) = connectionManager.onRelayUrlChanged(value)

    fun onRelayDeviceIdChanged(value: String) = connectionManager.onRelayDeviceIdChanged(value)

    fun onRelayTokenChanged(value: String) = connectionManager.onRelayTokenChanged(value)

    fun connectViaRelay() = connectionManager.connectManualRelay()

    fun onAutoConnectChanged(enabled: Boolean) = connectionManager.onAutoConnectChanged(enabled)

    fun refreshDiscovery() = connectionManager.refreshDiscovery()

    fun connectToDiscovered(pc: DiscoveredPc) = connectionManager.connectToDiscovered(pc)

    fun reconnectWifiFirst() = connectionManager.reconnectWifiFirst()

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
            connectionManager.send(Protocol.encodeMedia(if (up) "vol_up" else "vol_down"))
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
            connectionManager.send(Protocol.encodeVolumeSet(clamped))
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
            connectionManager.send(Protocol.encodeVolumeSync(shouldSync))
        }
    }

    private fun observeRemoteVolume() {
        viewModelScope.launch {
            connectionManager.volumeState.collect { remote ->
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
        connectionManager.setMicEnabled(enabled)
    }

    fun toggleMic() {
        connectionManager.toggleMic()
    }

    fun clearMicError() {
        connectionManager.clearMicError()
    }

    fun toggleKeyboard() {
        _isKeyboardOpen.update { !it }
    }

    fun setKeyboardOpen(open: Boolean) {
        _isKeyboardOpen.value = open
    }

    fun connect() = connectionManager.connectManualLan()

    fun disconnect() {
        releaseAllModifiers()
        clearPendingDeltas()
        connectionManager.disconnectByUser()
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
        connectionManager.send(Protocol.encodeClick(button, count))
    }

    fun onButton(button: MouseButton, down: Boolean) {
        flushPendingMoveNow()
        val state = if (down) ButtonState.DOWN else ButtonState.UP
        connectionManager.send(Protocol.encodeButton(button, state))
    }

    fun sendGesture(gesture: String) {
        connectionManager.send(Protocol.encodeGesture(gesture))
    }

    fun sendMediaAction(action: String) {
        connectionManager.send(Protocol.encodeMedia(action))
    }

    fun sendPowerAction(action: String) {
        connectionManager.send(Protocol.encodePower(action))
    }

    fun sendKeyCombo(keys: List<String>) {
        if (keys.isEmpty()) return
        connectionManager.send(Protocol.encodeKeyCombo(keys))
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
                connectionManager.send(Protocol.encodeKeyEvent(mod.wireValue, ButtonState.DOWN))
                ModifierState.LOCKED
            }
            ModifierState.LOCKED -> {
                connectionManager.send(Protocol.encodeKeyEvent(mod.wireValue, ButtonState.UP))
                ModifierState.OFF
            }
        }
        _modifierStates.update { it + (mod to next) }
    }

    fun releaseAllModifiers() {
        val states = _modifierStates.value
        states.forEach { (mod, state) ->
            if (state == ModifierState.LOCKED) {
                connectionManager.send(Protocol.encodeKeyEvent(mod.wireValue, ButtonState.UP))
            }
        }
        _modifierStates.value = ModifierKey.entries.associateWith { ModifierState.OFF }
    }

    fun sendSpecialKey(keyWireValue: String) {
        val activeMods = consumeActiveModifiers()
        val keys = activeMods + keyWireValue
        connectionManager.send(Protocol.encodeKeyCombo(keys))
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
                connectionManager.send(Protocol.encodeKeyCombo(activeMods + mappedKey))
                return
            }
        }

        if (activeMods.isNotEmpty()) {
            clearActiveModifiers()
        }
        connectionManager.send(Protocol.encodeText(text))
    }

    fun sendParagraphText(text: String) {
        if (text.isEmpty()) return
        clearActiveModifiers()
        connectionManager.send(Protocol.encodePaste(text))
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

                    if (connectionManager.queuedBytes() > MAX_QUEUED_BYTES) {
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
        return connectionManager.sendMove(mx, my)
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
            sent = connectionManager.sendMove(mx, my) || sent
        }
        if (sx != 0f || sy != 0f) {
            sent = connectionManager.sendScroll(sx, sy) || sent
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

    private fun observeConnectionForUiFeatures() {
        viewModelScope.launch {
            connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        updateAirMouseSensorState()
                        updateVolumeSyncState()
                    }
                    is ConnectionState.Connecting -> {
                        updateAirMouseSensorState()
                        updateVolumeSyncState()
                    }
                    ConnectionState.Disconnected, is ConnectionState.Error -> {
                        updateAirMouseSensorState()
                        updateVolumeSyncState()
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Stop UI-bound sensors, but DO NOT stop the background microphone, disconnect
        // ConnectionManager, or stop RemoteConnectionService so both PC connection and
        // any manually enabled Phone Mic stay alive after closing the app.
        airMouseController.stop()
        releaseAllModifiers()
        flushSignal.close()
        connectionManager.onUiDetached()
    }

    companion object {
        private const val PREFS_NAME = "remotepad_prefs"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_AIR_MOUSE_SPEED = "air_mouse_speed"
        private const val AIR_MOUSE_MIN_SPEED = 0.2f
        private const val AIR_MOUSE_MAX_SPEED = 4.0f
        private const val AIR_MOUSE_SPEED_STEP = 0.2f
        private const val VOLUME_SEND_THROTTLE_MS = 35L
        private const val VOLUME_ECHO_GUARD_MS = 180L
        private const val VOLUME_MODAL_AUTO_DISMISS_MS = 3000L
        private const val MIN_SEND_INTERVAL_MS = 8L
        private const val BACKPRESSURE_DELAY_MS = 4L
        private const val MAX_QUEUED_BYTES = 256L
        private const val MIN_MOVE_EPSILON = 0.05f
        private const val MIN_SCROLL_EPSILON = 0.02f
    }
}
