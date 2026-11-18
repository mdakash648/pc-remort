package com.remotepad.app.input

import com.remotepad.app.network.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface MicSessionAdapter {
    fun hasRecordAudioPermission(): Boolean
    fun currentConnectionState(): ConnectionState
    fun isAutoRecovering(): Boolean
    fun isStreamingTo(host: String, port: Int): Boolean
    fun startAudioStream(host: String, port: Int): Boolean
    fun stopAudioStream()
    fun acquireKeepAliveLocks()
    fun releaseKeepAliveLocks()
    fun requestMicrophoneForegroundService()
    fun releaseMicrophoneForegroundService()
}

/**
 * Process-scoped microphone session state machine shared by ConnectionManager and
 * RemoteConnectionService.
 *
 * Ensures that once the user manually turns Phone Mic ON:
 * 1. RemoteConnectionService is promoted to FOREGROUND_SERVICE_TYPE_MICROPHONE before
 *    AudioRecord begins capturing (required by Android 11–16 so mic audio is not silenced
 *    2–3 seconds after leaving the app or putting it in Recents).
 * 2. Closing MainActivity or swiping the app from Recents does NOT stop the mic stream.
 * 3. Brief Local Wi-Fi auto-reconnects pause UDP streaming without dropping the microphone
 *    foreground service, then automatically resume streaming when LAN reconnects.
 * 4. Explicit user Mic OFF, notification "Stop Mic", manual Disconnect, VPS-only switch,
 *    or exhausted reconnect cleanly stops audio capture, releases Wake/Wi-Fi locks, and
 *    downgrades the foreground service out of microphone mode.
 */
class MicSessionController(
    private val adapter: MicSessionAdapter,
) {
    private val lock = Any()

    private val _isMicEnabled = MutableStateFlow(false)
    val isMicEnabled: StateFlow<Boolean> = _isMicEnabled.asStateFlow()

    private val _isMicStreaming = MutableStateFlow(false)
    val isMicStreaming: StateFlow<Boolean> = _isMicStreaming.asStateFlow()

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val _micError = MutableStateFlow<String?>(null)
    val micError: StateFlow<String?> = _micError.asStateFlow()

    @Volatile
    private var micDesiredByUser = false

    @Volatile
    private var foregroundMicReady = false

    private var generation = 0L

    val shouldHoldMicrophoneForeground: Boolean
        get() = micDesiredByUser

    fun setMicEnabled(enabled: Boolean): Boolean {
        var shouldRequestFg = false
        var shouldReleaseFg = false

        val result = synchronized(lock) {
            _micError.value = null
            if (!enabled) {
                val wasActive = micDesiredByUser || _isMicEnabled.value || _isMicStreaming.value || foregroundMicReady
                stopMicStateLocked(errorMessage = null)
                shouldReleaseFg = wasActive
                return@synchronized false
            }

            if (!adapter.hasRecordAudioPermission()) {
                _micError.value = "Microphone permission is required for PC Mic"
                stopMicStateLocked(errorMessage = _micError.value)
                return@synchronized false
            }

            val connected = adapter.currentConnectionState() as? ConnectionState.Connected
            if (connected == null) {
                _micError.value = "Connect to PC first"
                stopMicStateLocked(errorMessage = _micError.value)
                return@synchronized false
            }

            if (connected.viaRelay || connected.host.isBlank()) {
                _micError.value = "PC Mic works on Local Wi-Fi only"
                stopMicStateLocked(errorMessage = _micError.value)
                return@synchronized false
            }

            generation += 1
            micDesiredByUser = true
            _isMicEnabled.value = true
            shouldRequestFg = true
            true
        }

        if (shouldRequestFg) {
            adapter.requestMicrophoneForegroundService()
        } else if (shouldReleaseFg) {
            adapter.releaseMicrophoneForegroundService()
        }
        return result
    }

    fun toggleMic(): Boolean = setMicEnabled(!_isMicEnabled.value)

    fun clearMicError() {
        _micError.value = null
    }

    /**
     * Invoked once RemoteConnectionService has successfully promoted itself with
     * FOREGROUND_SERVICE_TYPE_MICROPHONE.
     */
    fun onForegroundServiceMicReady() {
        var shouldReleaseFg = false

        synchronized(lock) {
            if (!micDesiredByUser) {
                foregroundMicReady = false
                shouldReleaseFg = true
                return@synchronized
            }

            foregroundMicReady = true
            val gen = generation
            val state = adapter.currentConnectionState()
            when (state) {
                is ConnectionState.Connected -> {
                    if (state.viaRelay || state.host.isBlank()) {
                        stopMicStateLocked(errorMessage = "PC Mic works on Local Wi-Fi only")
                        shouldReleaseFg = true
                    } else {
                        shouldReleaseFg = !startStreamForConnectedLocked(state, gen)
                    }
                }
                is ConnectionState.Connecting -> {
                    // Keep mic enabled + FGS active while LAN reconnect finishes
                    adapter.acquireKeepAliveLocks()
                    _isMicStreaming.value = false
                    _micLevel.value = 0f
                }
                ConnectionState.Disconnected, is ConnectionState.Error -> {
                    if (adapter.isAutoRecovering()) {
                        adapter.acquireKeepAliveLocks()
                        _isMicStreaming.value = false
                        _micLevel.value = 0f
                    } else {
                        stopMicStateLocked(errorMessage = null)
                        shouldReleaseFg = true
                    }
                }
            }
        }

        if (shouldReleaseFg) {
            adapter.releaseMicrophoneForegroundService()
        }
    }

    fun onForegroundServiceMicFailed(message: String) {
        synchronized(lock) {
            if (!micDesiredByUser && !_isMicEnabled.value) return
            stopMicStateLocked(errorMessage = message)
        }
    }

    fun onConnectionStateChanged(state: ConnectionState) {
        var shouldReleaseFg = false

        synchronized(lock) {
            if (!micDesiredByUser) return
            val gen = generation
            when (state) {
                is ConnectionState.Connected -> {
                    if (state.viaRelay || state.host.isBlank()) {
                        stopMicStateLocked(errorMessage = "PC Mic stopped — Local Wi-Fi only")
                        shouldReleaseFg = true
                    } else if (foregroundMicReady) {
                        shouldReleaseFg = !startStreamForConnectedLocked(state, gen)
                    }
                }
                is ConnectionState.Connecting -> {
                    pauseStreamForReconnectLocked()
                }
                ConnectionState.Disconnected, is ConnectionState.Error -> {
                    if (adapter.isAutoRecovering()) {
                        pauseStreamForReconnectLocked()
                    } else {
                        stopMicStateLocked(errorMessage = null)
                        shouldReleaseFg = true
                    }
                }
            }
        }

        if (shouldReleaseFg) {
            adapter.releaseMicrophoneForegroundService()
        }
    }

    fun onStreamLevelChanged(level: Float) {
        _micLevel.value = if (_isMicStreaming.value) level else 0f
    }

    fun onStreamError(message: String) {
        var shouldReleaseFg = false
        synchronized(lock) {
            if (!micDesiredByUser) return
            stopMicStateLocked(errorMessage = message)
            shouldReleaseFg = true
        }
        if (shouldReleaseFg) {
            adapter.releaseMicrophoneForegroundService()
        }
    }

    fun stopMicFromService() {
        synchronized(lock) {
            stopMicStateLocked(errorMessage = null)
        }
    }

    fun onUserDisconnect() {
        synchronized(lock) {
            stopMicStateLocked(errorMessage = null)
        }
    }

    fun onForegroundServiceDestroyed() {
        synchronized(lock) {
            stopMicStateLocked(errorMessage = null)
        }
    }

    private fun startStreamForConnectedLocked(connected: ConnectionState.Connected, expectedGen: Long): Boolean {
        if (!micDesiredByUser || generation != expectedGen) return false
        adapter.acquireKeepAliveLocks()
        if (adapter.isStreamingTo(connected.host, connected.micPort)) {
            _isMicStreaming.value = true
            return true
        }
        val started = adapter.startAudioStream(connected.host, connected.micPort)
        if (!micDesiredByUser || generation != expectedGen) {
            if (started) {
                adapter.stopAudioStream()
                adapter.releaseKeepAliveLocks()
            }
            return false
        }
        if (started) {
            _isMicStreaming.value = true
            return true
        }
        stopMicStateLocked(errorMessage = _micError.value)
        return false
    }

    private fun pauseStreamForReconnectLocked() {
        adapter.stopAudioStream()
        _isMicStreaming.value = false
        _micLevel.value = 0f
    }

    private fun stopMicStateLocked(errorMessage: String?) {
        generation += 1
        micDesiredByUser = false
        foregroundMicReady = false
        _isMicEnabled.value = false
        _isMicStreaming.value = false
        _micLevel.value = 0f
        if (errorMessage != null) {
            _micError.value = errorMessage
        }
        adapter.stopAudioStream()
        adapter.releaseKeepAliveLocks()
    }
}
