package com.remotepad.app.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.remotepad.app.data.model.IncomingMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

enum class TransportMode {
    LAN,
    P2P_CONNECTING,
    P2P_DIRECT,
    VPS_RELAY,
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val target: String) : ConnectionState
    data class Connected(
        val pcName: String,
        val target: String,
        val host: String,
        val filePort: Int = 8767,
        val micPort: Int = 8768,
        val micDevice: String = "",
        val viaRelay: Boolean = false,
        val transportMode: TransportMode = if (viaRelay) TransportMode.VPS_RELAY else TransportMode.LAN,
        val p2pRttMs: Int? = null,
        val connectedDevicesCount: Int = 1,
    ) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

data class RemoteVolumeState(
    val level: Float = 0.5f,
    val muted: Boolean = false,
    val synced: Boolean = false,
)

/**
 * SocketFactory that disables Nagle's algorithm (TCP_NODELAY) and marks sockets
 * for low-delay interactive traffic so small touchpad move frames are never held
 * back waiting for TCP Delayed ACKs.
 */
private class LowLatencySocketFactory : SocketFactory() {
    private val delegate: SocketFactory = getDefault()

    private fun configure(socket: Socket): Socket {
        runCatching { socket.tcpNoDelay = true }
        runCatching { socket.trafficClass = 0x10 } // IPTOS_LOWDELAY
        return socket
    }

    override fun createSocket(): Socket = configure(delegate.createSocket())

    override fun createSocket(host: String, port: Int): Socket =
        configure(delegate.createSocket(host, port))

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        configure(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket =
        configure(delegate.createSocket(host, port))

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = configure(delegate.createSocket(address, port, localAddress, localPort))
}

class LocalClient(context: Context? = null) {
    private val appContext = context?.applicationContext

    private val httpClient = OkHttpClient.Builder()
        .socketFactory(LowLatencySocketFactory())
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    // Fast TCP timeout on LAN so unreachable local IPs fall back to Internet (VPS) quickly;
    // slightly more generous timeout on Internet (VPS) for 4G/5G handshakes.
    private val lanHttpClient = httpClient.newBuilder()
        .connectTimeout(2200, TimeUnit.MILLISECONDS)
        .build()

    private val relayHttpClient = httpClient.newBuilder()
        .connectTimeout(6000, TimeUnit.MILLISECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val p2pClient = P2PUdpClient()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _connectedDevicesCount = MutableStateFlow(1)
    val connectedDevicesCount: StateFlow<Int> = _connectedDevicesCount.asStateFlow()

    private val _volumeState = MutableStateFlow(RemoteVolumeState())
    val volumeState: StateFlow<RemoteVolumeState> = _volumeState.asStateFlow()

    private val _screenFrames = MutableSharedFlow<ScreenFramePacket>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val screenFrames: SharedFlow<ScreenFramePacket> = _screenFrames.asSharedFlow()

    @Volatile
    private var socket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var currentTarget: String = ""

    private val wifiLockGuard = Any()
    private var wifiLock: WifiManager.WifiLock? = null

    init {
        p2pClient.sendWebSocketBinary = { bytes ->
            sendBinary(bytes)
        }
        p2pClient.onScreenFrame = { frame ->
            _screenFrames.tryEmit(frame)
        }
        scope.launch {
            p2pClient.state.collect { p2pState ->
                val current = _state.value
                if (current is ConnectionState.Connected && current.viaRelay) {
                    val updated = when (p2pState) {
                        is P2PState.Connected -> current.copy(
                            transportMode = TransportMode.P2P_DIRECT,
                            p2pRttMs = p2pState.rttMs,
                        )
                        P2PState.Punching -> current.copy(
                            transportMode = TransportMode.P2P_CONNECTING,
                            p2pRttMs = null,
                        )
                        P2PState.FallbackRelay, P2PState.Idle -> current.copy(
                            transportMode = TransportMode.VPS_RELAY,
                            p2pRttMs = null,
                        )
                    }
                    if (updated != current) {
                        _state.value = updated
                    }
                }
            }
        }
    }

    fun connect(host: String, port: Int) {
        closeActiveSocket()

        val cleanHost = host.trim()
        if (cleanHost.isEmpty()) {
            _state.value = ConnectionState.Error("Enter PC IP address")
            return
        }
        if (port !in 1..65535) {
            _state.value = ConnectionState.Error("Invalid port")
            return
        }

        val url = "ws://$cleanHost:$port"
        currentTarget = "Wi-Fi ($cleanHost:$port)"
        _state.value = ConnectionState.Connecting(currentTarget)

        val request = Request.Builder().url(url).build()
        socket = lanHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (socket !== webSocket) return
                acquireLowLatencyWifiLock()
                val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                webSocket.send(Protocol.encodeHello(deviceName = deviceName))
                _state.value = ConnectionState.Connected(
                    pcName = cleanHost,
                    target = currentTarget,
                    host = cleanHost,
                )
                startHeartbeat(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (socket !== webSocket) return
                when (val msg = Protocol.decodeIncoming(text)) {
                    is IncomingMessage.HelloAck -> {
                        if (msg.ok) {
                            _connectedDevicesCount.value = msg.connectedCount
                            _state.value = ConnectionState.Connected(
                                pcName = msg.pcName,
                                target = currentTarget,
                                host = cleanHost,
                                filePort = msg.filePort,
                                micPort = msg.micPort,
                                micDevice = msg.micDevice,
                                connectedDevicesCount = msg.connectedCount,
                            )
                        } else {
                            _state.value = ConnectionState.Error("Rejected by PC")
                            webSocket.close(1008, "Rejected")
                        }
                    }
                    is IncomingMessage.DevicesUpdate -> {
                        _connectedDevicesCount.value = msg.count
                        val cur = _state.value
                        if (cur is ConnectionState.Connected) {
                            _state.value = cur.copy(connectedDevicesCount = msg.count)
                        }
                    }
                    is IncomingMessage.VolumeState -> {
                        _volumeState.value = RemoteVolumeState(
                            level = msg.level,
                            muted = msg.muted,
                            synced = true,
                        )
                    }
                    is IncomingMessage.RelayReady,
                    is IncomingMessage.RelayError,
                    is IncomingMessage.RelayPeer,
                    is IncomingMessage.P2PAnswer,
                    IncomingMessage.Pong,
                    is IncomingMessage.Unknown,
                    null,
                    -> Unit
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (socket !== webSocket) return
                val raw = bytes.toByteArray()
                if (raw.size >= 4 && raw[0] == 'R'.code.toByte() && raw[1] == 'P'.code.toByte() && raw[2] == '2'.code.toByte() && raw[3] == 'P'.code.toByte()) {
                    p2pClient.onRawFilePacketReceived(raw)
                    return
                }
                val frame = ScreenFrameWire.decode(raw, raw.size, fromP2p = false) ?: return
                _screenFrames.tryEmit(frame)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (socket !== webSocket) return
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket !== webSocket) return
                stopHeartbeat()
                releaseLowLatencyWifiLock()
                socket = null
                _state.value = ConnectionState.Disconnected
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket !== webSocket) return
                stopHeartbeat()
                releaseLowLatencyWifiLock()
                socket = null
                val msg = t.message?.takeIf { it.isNotBlank() } ?: "Connection failed"
                _state.value = ConnectionState.Error(msg)
            }
        })
    }

    fun connectRelay(relayUrl: String, deviceId: String, token: String) {
        closeActiveSocket()

        val cleanUrl = relayUrl.trim()
        val cleanId = deviceId.trim()
        val cleanToken = token.trim()

        if (!cleanUrl.startsWith("ws://") && !cleanUrl.startsWith("wss://")) {
            _state.value = ConnectionState.Error("Relay URL must start with ws:// or wss://")
            return
        }
        if (cleanId.length < 3) {
            _state.value = ConnectionState.Error("Enter valid Device ID")
            return
        }
        if (cleanToken.length < 8) {
            _state.value = ConnectionState.Error("Token must be 8+ characters")
            return
        }

        currentTarget = "Internet ($cleanId)"
        _state.value = ConnectionState.Connecting(currentTarget)

        val request = runCatching { Request.Builder().url(cleanUrl).build() }.getOrElse {
            _state.value = ConnectionState.Error("Invalid Relay URL")
            return
        }

        socket = relayHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (socket !== webSocket) return
                acquireLowLatencyWifiLock()
                val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                webSocket.send(Protocol.encodeRelayRegister(cleanId, cleanToken, deviceName))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (socket !== webSocket) return
                when (val msg = Protocol.decodeIncoming(text)) {
                    is IncomingMessage.RelayReady -> {
                        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                        webSocket.send(Protocol.encodeHello(deviceName = deviceName))
                        _state.value = ConnectionState.Connected(
                            pcName = msg.pcName,
                            target = currentTarget,
                            host = "",
                            viaRelay = true,
                            transportMode = TransportMode.P2P_CONNECTING,
                        )
                        startHeartbeat(webSocket)
                        // Negotiate Direct P2P UDP in parallel; WebSocket relay stays live for instant input + fallback
                        p2pClient.start(
                            relayUrl = cleanUrl,
                            explicitUdpPort = msg.udpPort,
                            token = cleanToken,
                        ) { offerJson ->
                            if (socket === webSocket) webSocket.send(offerJson) else false
                        }
                    }
                    is IncomingMessage.P2PAnswer -> {
                        p2pClient.onAnswer(msg)
                    }
                    is IncomingMessage.RelayError -> {
                        p2pClient.stop()
                        stopHeartbeat()
                        releaseLowLatencyWifiLock()
                        socket = null
                        _state.value = ConnectionState.Error(msg.message)
                        webSocket.close(1000, msg.code)
                    }
                    is IncomingMessage.RelayPeer -> {
                        if (!msg.online) {
                            p2pClient.stop()
                            stopHeartbeat()
                            releaseLowLatencyWifiLock()
                            socket = null
                            _state.value = ConnectionState.Error("PC disconnected")
                            webSocket.close(1000, "PC disconnected")
                        }
                    }
                    is IncomingMessage.HelloAck -> {
                        if (msg.ok) {
                            _connectedDevicesCount.value = msg.connectedCount
                            val existing = _state.value as? ConnectionState.Connected
                            _state.value = ConnectionState.Connected(
                                pcName = msg.pcName,
                                target = currentTarget,
                                host = "",
                                filePort = msg.filePort,
                                micPort = msg.micPort,
                                micDevice = msg.micDevice,
                                viaRelay = true,
                                transportMode = existing?.transportMode ?: TransportMode.P2P_CONNECTING,
                                p2pRttMs = existing?.p2pRttMs,
                                connectedDevicesCount = msg.connectedCount,
                            )
                        }
                    }
                    is IncomingMessage.DevicesUpdate -> {
                        _connectedDevicesCount.value = msg.count
                        val cur = _state.value
                        if (cur is ConnectionState.Connected) {
                            _state.value = cur.copy(connectedDevicesCount = msg.count)
                        }
                    }
                    is IncomingMessage.VolumeState -> {
                        _volumeState.value = RemoteVolumeState(
                            level = msg.level,
                            muted = msg.muted,
                            synced = true,
                        )
                    }
                    IncomingMessage.Pong, is IncomingMessage.Unknown, null -> Unit
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (socket !== webSocket) return
                val raw = bytes.toByteArray()
                if (raw.size >= 4 && raw[0] == 'R'.code.toByte() && raw[1] == 'P'.code.toByte() && raw[2] == '2'.code.toByte() && raw[3] == 'P'.code.toByte()) {
                    p2pClient.onRawFilePacketReceived(raw)
                    return
                }
                val frame = ScreenFrameWire.decode(raw, raw.size, fromP2p = false) ?: return
                _screenFrames.tryEmit(frame)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (socket !== webSocket) return
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket !== webSocket) return
                p2pClient.stop()
                stopHeartbeat()
                releaseLowLatencyWifiLock()
                socket = null
                if (_state.value !is ConnectionState.Error) {
                    _state.value = ConnectionState.Disconnected
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket !== webSocket) return
                p2pClient.stop()
                stopHeartbeat()
                releaseLowLatencyWifiLock()
                socket = null
                val msg = t.message?.takeIf { it.isNotBlank() } ?: "Relay connection failed"
                _state.value = ConnectionState.Error(msg)
            }
        })
    }

    fun send(payload: String): Boolean {
        return socket?.send(payload) ?: false
    }

    fun sendBinary(bytes: ByteArray): Boolean {
        return socket?.send(ByteString.of(*bytes)) ?: false
    }

    /**
     * Send relative mouse move using the lowest-latency active transport:
     * Direct P2P UDP (~20-40ms) when punched over Internet, otherwise LAN/Relay WebSocket.
     */
    fun sendMove(dx: Float, dy: Float): Boolean {
        if (p2pClient.isConnected && p2pClient.sendMove(dx, dy)) {
            return true
        }
        return send(Protocol.encodeMove(dx, dy))
    }

    /**
     * Send scroll delta using Direct P2P UDP when available, otherwise LAN/Relay WebSocket.
     */
    fun sendScroll(dx: Float, dy: Float): Boolean {
        if (p2pClient.isConnected && p2pClient.sendScroll(dx, dy)) {
            return true
        }
        return send(Protocol.encodeScroll(dx, dy))
    }

    fun sendMoveAbs(normX: Float, normY: Float, monitor: Int = 0): Boolean {
        return send(Protocol.encodeMoveAbs(normX, normY, monitor))
    }

    fun sendScreenAck(seq: Int, fromP2p: Boolean): Boolean {
        if (fromP2p && p2pClient.isConnected && p2pClient.sendScreenAck(seq)) {
            return true
        }
        return send(Protocol.encodeScreenAck(seq))
    }

    fun resetScreenReassembler() {
        p2pClient.resetScreenReassembler()
    }

    fun queuedBytes(): Long {
        if (p2pClient.isConnected) return 0L
        return socket?.queueSize() ?: 0L
    }

    fun setConnectingStatus(targetLabel: String) {
        currentTarget = targetLabel
        _state.value = ConnectionState.Connecting(targetLabel)
    }

    fun setErrorStatus(message: String) {
        closeActiveSocket()
        _state.value = ConnectionState.Error(message)
    }

    fun evictConnections() {
        runCatching { httpClient.connectionPool.evictAll() }
    }

    fun disconnectSilently() {
        closeActiveSocket()
    }

    private fun closeActiveSocket() {
        p2pClient.stop()
        stopHeartbeat()
        releaseLowLatencyWifiLock()
        val current = socket
        socket = null
        current?.close(1000, "Switching connection")
    }

    fun disconnect() {
        closeActiveSocket()
        _state.value = ConnectionState.Disconnected
    }

    fun shutdown() {
        disconnect()
        httpClient.dispatcher.executorService.shutdown()
    }

    private fun acquireLowLatencyWifiLock() {
        val ctx = appContext ?: return
        synchronized(wifiLockGuard) {
            runCatching {
                val existing = wifiLock
                if (existing != null && existing.isHeld) return
                val wifiManager = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                val lock = wifiManager.createWifiLock(lockMode, "RemotePad:LowLatencyTouchpad")
                lock.setReferenceCounted(false)
                lock.acquire()
                wifiLock = lock
            }
        }
    }

    private fun releaseLowLatencyWifiLock() {
        synchronized(wifiLockGuard) {
            runCatching {
                wifiLock?.let { lock ->
                    if (lock.isHeld) {
                        lock.release()
                    }
                }
            }
            wifiLock = null
        }
    }

    private fun startHeartbeat(webSocket: WebSocket) {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive && socket === webSocket) {
                delay(15_000)
                if (socket === webSocket) {
                    webSocket.send(Protocol.encodePing())
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
}
