package com.remotepad.app.ui.screen

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remotepad.app.data.model.ButtonState
import com.remotepad.app.data.model.MouseButton
import com.remotepad.app.network.ConnectionManager
import com.remotepad.app.network.ConnectionState
import com.remotepad.app.network.Protocol
import com.remotepad.app.network.TransportMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ScreenQuality(
    val label: String,
    val wireValue: String,
    val maxWidth: Int,
    val fps: Int,
) {
    AUTO("Auto", "auto", 1920, 30),
    HD("HD", "hd", 1920, 30),
    BALANCED("Balanced", "balanced", 1440, 24),
    FAST("Fast", "fast", 960, 30),
}

enum class ScreenInputMode(val label: String) {
    TOUCHPAD("Touchpad"),
    DIRECT_TOUCH("Touch"),
}

data class RemoteScreenUiState(
    val frameBitmap: ImageBitmap? = null,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val monitorIndex: Int = 0,
    val monitorCount: Int = 1,
    val cursorXNorm: Float = 0.5f,
    val cursorYNorm: Float = 0.5f,
    val hasCursor: Boolean = false,
    val fps: Int = 0,
    val receivingViaP2p: Boolean = false,
    val p2pFallbackActive: Boolean = false,
    val quality: ScreenQuality = ScreenQuality.AUTO,
    val inputMode: ScreenInputMode = ScreenInputMode.TOUCHPAD,
    val isStreaming: Boolean = false,
)

class RemoteScreenViewModel(application: Application) : AndroidViewModel(application) {
    private val connectionManager = ConnectionManager.getInstance(application.applicationContext)
    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState

    private val _uiState = MutableStateFlow(RemoteScreenUiState())
    val uiState: StateFlow<RemoteScreenUiState> = _uiState.asStateFlow()

    @Volatile
    private var screenVisible = false

    @Volatile
    private var appInForeground = true

    @Volatile
    private var streamStartedOnServer = false

    @Volatile
    private var preferP2p = true

    @Volatile
    private var lastFrameElapsedMs = 0L

    @Volatile
    private var lastDirectMoveMs = 0L

    @Volatile
    private var lastLocalCursorSyncMs = 0L

    private var watchdogJob: Job? = null

    init {
        observeConnection()
        observeFrames()
    }

    fun setScreenVisible(visible: Boolean) {
        if (screenVisible == visible) return
        screenVisible = visible
        syncStreamingState(forceRestart = visible)
    }

    fun setAppInForeground(inForeground: Boolean) {
        if (appInForeground == inForeground) return
        appInForeground = inForeground
        syncStreamingState(forceRestart = false)
    }

    fun setQuality(quality: ScreenQuality) {
        val connected = connectionState.value as? ConnectionState.Connected
        val retryP2p = connected?.transportMode == TransportMode.P2P_DIRECT && !preferP2p
        if (retryP2p) {
            preferP2p = true
            connectionManager.resetScreenReassembler()
            lastFrameElapsedMs = SystemClock.elapsedRealtime()
        }
        _uiState.update {
            it.copy(
                quality = quality,
                p2pFallbackActive = if (retryP2p) false else it.p2pFallbackActive,
            )
        }
        if (streamStartedOnServer) {
            connectionManager.send(
                Protocol.encodeScreenConfig(
                    quality = quality.wireValue,
                    fps = quality.fps,
                    maxWidth = quality.maxWidth,
                    preferP2p = if (retryP2p) true else null,
                )
            )
            if (retryP2p) {
                startP2pWatchdog()
            }
        }
    }

    fun setInputMode(mode: ScreenInputMode) {
        _uiState.update { it.copy(inputMode = mode) }
    }

    fun toggleInputMode() {
        val next = if (_uiState.value.inputMode == ScreenInputMode.TOUCHPAD) {
            ScreenInputMode.DIRECT_TOUCH
        } else {
            ScreenInputMode.TOUCHPAD
        }
        setInputMode(next)
    }

    fun selectMonitor(index: Int) {
        val count = _uiState.value.monitorCount.coerceAtLeast(1)
        val target = ((index % count) + count) % count
        _uiState.update { it.copy(monitorIndex = target) }
        if (streamStartedOnServer) {
            connectionManager.send(Protocol.encodeScreenConfig(monitor = target))
        }
    }

    fun cycleMonitor() {
        val state = _uiState.value
        if (state.monitorCount <= 1) return
        selectMonitor((state.monitorIndex + 1) % state.monitorCount)
    }

    fun refreshFrame() {
        val connected = connectionState.value as? ConnectionState.Connected ?: return
        val st = _uiState.value
        preferP2p = true
        streamStartedOnServer = true
        connectionManager.resetScreenReassembler()
        lastFrameElapsedMs = SystemClock.elapsedRealtime()
        _uiState.update { it.copy(p2pFallbackActive = false) }
        connectionManager.send(
            Protocol.encodeScreenStart(
                quality = st.quality.wireValue,
                fps = st.quality.fps,
                maxWidth = st.quality.maxWidth,
                monitor = st.monitorIndex,
                preferP2p = preferP2p,
                hideCursor = connected.viaRelay,
            )
        )
        startP2pWatchdog()
    }

    /**
     * Syncs the phone's 60/120fps AnyDesk-style local cursor position to the PC over P2P/VPS.
     * Hover glides are gently throttled (~18 Hz) so the PC doesn't waste bandwidth, while drags
     * and forced flushes (before click/scroll/lift) deliver exact coordinates immediately.
     */
    fun syncLocalCursorToPc(
        normX: Float,
        normY: Float,
        force: Boolean = false,
        isDraggingButton: Boolean = false,
    ) {
        if (connectionState.value !is ConnectionState.Connected) return
        val now = SystemClock.elapsedRealtime()
        val minIntervalMs = if (isDraggingButton) 35L else 55L
        if (!force && (now - lastLocalCursorSyncMs) < minIntervalMs) return
        lastLocalCursorSyncMs = now
        connectionManager.sendMoveAbs(normX, normY, _uiState.value.monitorIndex)
    }

    fun onDirectMove(normX: Float, normY: Float) {
        if (connectionState.value !is ConnectionState.Connected) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastDirectMoveMs < 12L) return
        lastDirectMoveMs = now
        connectionManager.sendMoveAbs(normX, normY, _uiState.value.monitorIndex)
    }

    fun onDirectClick(normX: Float, normY: Float, button: MouseButton = MouseButton.LEFT, count: Int = 1) {
        if (connectionState.value !is ConnectionState.Connected) return
        connectionManager.sendMoveAbs(normX, normY, _uiState.value.monitorIndex)
        connectionManager.send(Protocol.encodeClick(button, count))
    }

    fun onDirectButton(normX: Float, normY: Float, button: MouseButton, down: Boolean) {
        if (connectionState.value !is ConnectionState.Connected) return
        connectionManager.sendMoveAbs(normX, normY, _uiState.value.monitorIndex)
        connectionManager.send(
            Protocol.encodeButton(button, if (down) ButtonState.DOWN else ButtonState.UP)
        )
    }

    private fun observeConnection() {
        viewModelScope.launch {
            var lastWasConnected = false
            var lastTransport: TransportMode? = null
            connectionState.collect { state ->
                val connected = state as? ConnectionState.Connected
                val isConnected = connected != null
                if (!isConnected) {
                    streamStartedOnServer = false
                    stopP2pWatchdog()
                    _uiState.update { it.copy(isStreaming = false, fps = 0) }
                } else {
                    val transportChanged = lastTransport != connected.transportMode
                    if (!lastWasConnected || transportChanged) {
                        preferP2p = true
                        syncStreamingState(forceRestart = true)
                    }
                }
                lastWasConnected = isConnected
                lastTransport = connected?.transportMode
            }
        }
    }

    private fun observeFrames() {
        viewModelScope.launch(Dispatchers.Default) {
            val argbOpts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val fastOpts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            var fpsWindowStart = SystemClock.elapsedRealtime()
            var framesInWindow = 0

            connectionManager.screenFrames.collect { packet ->
                // Acknowledge receipt right away so PC flow-control stays buttery smooth
                connectionManager.sendScreenAck(packet.seq, packet.fromP2p)

                val decodeOpts = if (_uiState.value.quality == ScreenQuality.FAST) fastOpts else argbOpts
                val bmp = runCatching {
                    BitmapFactory.decodeByteArray(
                        packet.jpegBytes,
                        0,
                        packet.jpegBytes.size,
                        decodeOpts,
                    )
                }.getOrNull() ?: return@collect

                val now = SystemClock.elapsedRealtime()
                lastFrameElapsedMs = now
                framesInWindow += 1
                var updatedFps = _uiState.value.fps
                val elapsed = now - fpsWindowStart
                if (elapsed >= 1000L) {
                    updatedFps = ((framesInWindow * 1000L) / elapsed).toInt().coerceIn(0, 60)
                    framesInWindow = 0
                    fpsWindowStart = now
                }

                val imgBitmap = bmp.asImageBitmap()
                _uiState.update { current ->
                    current.copy(
                        frameBitmap = imgBitmap,
                        frameWidth = packet.width,
                        frameHeight = packet.height,
                        monitorIndex = packet.monitorIndex,
                        monitorCount = packet.monitorCount,
                        cursorXNorm = packet.cursorXNorm,
                        cursorYNorm = packet.cursorYNorm,
                        hasCursor = packet.hasCursor,
                        fps = updatedFps,
                        receivingViaP2p = packet.fromP2p,
                        p2pFallbackActive = if (packet.fromP2p) false else current.p2pFallbackActive,
                        isStreaming = streamStartedOnServer,
                    )
                }
            }
        }
    }

    private fun syncStreamingState(forceRestart: Boolean) {
        val connected = connectionState.value as? ConnectionState.Connected
        val shouldStream = screenVisible && appInForeground && connected != null

        if (shouldStream) {
            if (!streamStartedOnServer || forceRestart) {
                streamStartedOnServer = true
                preferP2p = true
                connectionManager.resetScreenReassembler()
                lastFrameElapsedMs = SystemClock.elapsedRealtime()
                val st = _uiState.value
                connectionManager.send(
                    Protocol.encodeScreenStart(
                        quality = st.quality.wireValue,
                        fps = st.quality.fps,
                        maxWidth = st.quality.maxWidth,
                        monitor = st.monitorIndex,
                        preferP2p = preferP2p,
                        hideCursor = connected.viaRelay,
                    )
                )
                _uiState.update { it.copy(isStreaming = true, p2pFallbackActive = false) }
                startP2pWatchdog()
            }
        } else {
            if (streamStartedOnServer) {
                streamStartedOnServer = false
                if (connected != null) {
                    connectionManager.send(Protocol.encodeScreenStop())
                }
            }
            connectionManager.resetScreenReassembler()
            stopP2pWatchdog()
            _uiState.update { it.copy(isStreaming = false, fps = 0, p2pFallbackActive = false) }
        }
    }

    /**
     * If Direct P2P UDP is active but carrier/NAT genuinely blocks large UDP screen frames even
     * after PC adaptive shrinkage & pacing, fall back to WebSocket binary frames after 5s so the
     * user never sees a black screen.
     */
    private fun startP2pWatchdog() {
        stopP2pWatchdog()
        watchdogJob = viewModelScope.launch {
            while (isActive && streamStartedOnServer) {
                delay(1_000L)
                val connected = connectionState.value as? ConnectionState.Connected ?: break
                if (connected.transportMode == TransportMode.P2P_DIRECT && preferP2p) {
                    val sinceLastFrame = SystemClock.elapsedRealtime() - lastFrameElapsedMs
                    if (sinceLastFrame >= P2P_FALLBACK_TIMEOUT_MS) {
                        preferP2p = false
                        _uiState.update { it.copy(p2pFallbackActive = true) }
                        connectionManager.send(Protocol.encodeScreenConfig(preferP2p = false))
                        lastFrameElapsedMs = SystemClock.elapsedRealtime()
                    }
                }
            }
        }
    }

    private fun stopP2pWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopP2pWatchdog()
        if (streamStartedOnServer) {
            streamStartedOnServer = false
            connectionManager.send(Protocol.encodeScreenStop())
        }
    }

    companion object {
        private const val P2P_FALLBACK_TIMEOUT_MS = 5_000L
    }
}
