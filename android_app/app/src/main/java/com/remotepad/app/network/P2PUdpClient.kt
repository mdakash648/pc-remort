package com.remotepad.app.network

import android.os.Looper
import android.os.SystemClock
import com.remotepad.app.data.model.IncomingMessage
import com.remotepad.app.data.model.P2PEndpoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

sealed interface P2PState {
    data object Idle : P2PState
    data object Punching : P2PState
    data class Connected(
        val peerHost: String,
        val peerPort: Int,
        val rttMs: Int,
    ) : P2PState
    data object FallbackRelay : P2PState
}

data class UnpackedP2PPacket(
    val type: Byte,
    val authTag: ByteArray,
    val seq: Int,
    val payload8: ByteArray,
)

data class UnpackedFilePacket(
    val type: Byte,
    val flags: Byte,
    val authTag: ByteArray,
    val xferId: Int,
    val body: ByteArray,
)

/**
 * Pure JVM wire helpers for the authenticated RemotePad Direct P2P UDP protocol
 * (fixed 28-byte control/input packets + variable-length reliable file transfer packets).
 * Byte-for-byte compatible with pc_agent/core/p2p.py and pc_agent/core/p2p_file.py.
 */
object P2PWire {
    const val PACKET_SIZE = 28
    const val FILE_HEADER_SIZE = 20
    const val FILE_CHUNK_SIZE = 1150
    private val MAGIC = byteArrayOf('R'.code.toByte(), 'P'.code.toByte(), '2'.code.toByte(), 'P'.code.toByte())

    const val PKT_PUNCH: Byte = 0x01
    const val PKT_PUNCH_ACK: Byte = 0x02
    const val PKT_MOVE: Byte = 0x03
    const val PKT_SCROLL: Byte = 0x04
    const val PKT_KEEPALIVE: Byte = 0x05
    const val PKT_KEEPALIVE_ACK: Byte = 0x06

    const val PKT_FILE_REQ: Byte = 0x10
    const val PKT_FILE_RESP: Byte = 0x11
    const val PKT_FILE_DATA: Byte = 0x12
    const val PKT_FILE_ACK: Byte = 0x13
    const val PKT_FILE_ABORT: Byte = 0x14

    const val FLAG_EOF: Byte = 0x01

    fun isFilePacketType(type: Byte): Boolean =
        type == PKT_FILE_REQ ||
            type == PKT_FILE_RESP ||
            type == PKT_FILE_DATA ||
            type == PKT_FILE_ACK ||
            type == PKT_FILE_ABORT

    fun deriveAuthTag(token: String, sid: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$token:$sid".toByteArray(Charsets.UTF_8))
        return digest.copyOf(8)
    }

    fun packControlPacket(type: Byte, authTag: ByteArray, seq: Int, timestampMs: Long): ByteArray {
        val buf = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.put(MAGIC)
        buf.put(type)
        buf.put(0)
        buf.put(0)
        buf.put(0)
        buf.put(authTag, 0, 8)
        buf.putInt(seq)
        buf.putLong(timestampMs)
        return buf.array()
    }

    fun packDeltaPacket(type: Byte, authTag: ByteArray, seq: Int, dx: Float, dy: Float): ByteArray {
        val buf = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.put(MAGIC)
        buf.put(type)
        buf.put(0)
        buf.put(0)
        buf.put(0)
        buf.put(authTag, 0, 8)
        buf.putInt(seq)
        buf.putFloat(if (dx.isFinite()) dx else 0f)
        buf.putFloat(if (dy.isFinite()) dy else 0f)
        return buf.array()
    }

    fun unpackPacket(data: ByteArray, length: Int = data.size): UnpackedP2PPacket? {
        if (length != PACKET_SIZE || data.size < PACKET_SIZE) return null
        if (data[0] != MAGIC[0] || data[1] != MAGIC[1] || data[2] != MAGIC[2] || data[3] != MAGIC[3]) {
            return null
        }
        val buf = ByteBuffer.wrap(data, 0, PACKET_SIZE).order(ByteOrder.BIG_ENDIAN)
        val type = buf.get(4)
        val authTag = ByteArray(8)
        System.arraycopy(data, 8, authTag, 0, 8)
        val seq = buf.getInt(16)
        val payload = ByteArray(8)
        System.arraycopy(data, 20, payload, 0, 8)
        return UnpackedP2PPacket(type = type, authTag = authTag, seq = seq, payload8 = payload)
    }

    fun readTimestamp(payload8: ByteArray): Long =
        ByteBuffer.wrap(payload8).order(ByteOrder.BIG_ENDIAN).long

    fun packFileJson(type: Byte, authTag: ByteArray, xferId: Int, jsonBody: String): ByteArray {
        val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(FILE_HEADER_SIZE + bodyBytes.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(MAGIC)
        buf.put(type)
        buf.put(0)
        buf.put(0)
        buf.put(0)
        buf.put(authTag, 0, 8)
        buf.putInt(xferId)
        buf.put(bodyBytes)
        return buf.array()
    }

    fun packFileData(
        authTag: ByteArray,
        xferId: Int,
        chunkIdx: Int,
        payload: ByteArray,
        payloadLen: Int = payload.size,
        isEof: Boolean = false,
    ): ByteArray {
        val buf = ByteBuffer.allocate(FILE_HEADER_SIZE + 4 + payloadLen).order(ByteOrder.BIG_ENDIAN)
        buf.put(MAGIC)
        buf.put(PKT_FILE_DATA)
        buf.put(if (isEof) FLAG_EOF else 0)
        buf.put(0)
        buf.put(0)
        buf.put(authTag, 0, 8)
        buf.putInt(xferId)
        buf.putInt(chunkIdx)
        if (payloadLen > 0) {
            buf.put(payload, 0, payloadLen)
        }
        return buf.array()
    }

    fun packFileAck(authTag: ByteArray, xferId: Int, baseIdx: Int, sackMask: Long): ByteArray {
        val buf = ByteBuffer.allocate(FILE_HEADER_SIZE + 12).order(ByteOrder.BIG_ENDIAN)
        buf.put(MAGIC)
        buf.put(PKT_FILE_ACK)
        buf.put(0)
        buf.put(0)
        buf.put(0)
        buf.put(authTag, 0, 8)
        buf.putInt(xferId)
        buf.putInt(baseIdx)
        buf.putLong(sackMask)
        return buf.array()
    }

    fun packFileAbort(authTag: ByteArray, xferId: Int, reason: String = ""): ByteArray {
        val reasonBytes = reason.toByteArray(Charsets.UTF_8).let {
            if (it.size > 256) it.copyOf(256) else it
        }
        val buf = ByteBuffer.allocate(FILE_HEADER_SIZE + reasonBytes.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(MAGIC)
        buf.put(PKT_FILE_ABORT)
        buf.put(0)
        buf.put(0)
        buf.put(0)
        buf.put(authTag, 0, 8)
        buf.putInt(xferId)
        buf.put(reasonBytes)
        return buf.array()
    }

    fun unpackFilePacket(data: ByteArray, length: Int = data.size): UnpackedFilePacket? {
        if (length < FILE_HEADER_SIZE || data.size < length) return null
        if (data[0] != MAGIC[0] || data[1] != MAGIC[1] || data[2] != MAGIC[2] || data[3] != MAGIC[3]) {
            return null
        }
        val type = data[4]
        if (!isFilePacketType(type)) return null
        val flags = data[5]
        val authTag = ByteArray(8)
        System.arraycopy(data, 8, authTag, 0, 8)
        val xferId = ByteBuffer.wrap(data, 16, 4).order(ByteOrder.BIG_ENDIAN).int
        val bodyLen = length - FILE_HEADER_SIZE
        val body = ByteArray(bodyLen)
        if (bodyLen > 0) {
            System.arraycopy(data, FILE_HEADER_SIZE, body, 0, bodyLen)
        }
        return UnpackedFilePacket(
            type = type,
            flags = flags,
            authTag = authTag,
            xferId = xferId,
            body = body,
        )
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }

    fun parseRelayHostAndUdpPort(relayUrl: String, explicitUdpPort: Int? = null): Pair<String, Int>? {
        val uri = runCatching { URI(relayUrl.trim()) }.getOrNull() ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        if (explicitUdpPort != null && explicitUdpPort in 1..65535) {
            return host to explicitUdpPort
        }
        val wsPort = if (uri.port in 1..65535) uri.port else if (uri.scheme == "wss") 443 else 80
        val udpPort = if (wsPort < 65535) wsPort + 1 else 8771
        return host to udpPort
    }
}

/**
 * Handles Direct P2P UDP endpoint discovery (via VPS UDP reflector), simultaneous UDP
 * hole punching, live RTT keepalives, and ultra-low-latency direct move/scroll datagrams.
 */
class P2PUdpClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow<P2PState>(P2PState.Idle)
    val state: StateFlow<P2PState> = _state.asStateFlow()

    val isConnected: Boolean
        get() = _state.value is P2PState.Connected && peerAddress != null && udpSocket?.isClosed == false

    @Volatile
    private var udpSocket: DatagramSocket? = null

    @Volatile
    private var currentSid: String = ""

    @Volatile
    private var authTag: ByteArray = ByteArray(0)

    @Volatile
    private var peerAddress: InetSocketAddress? = null

    @Volatile
    private var lastRxElapsedMs: Long = 0L

    private val txSeq = AtomicInteger(0)
    private val nextXferId = AtomicInteger(1)
    private val fileListeners = ConcurrentHashMap<Int, (UnpackedFilePacket) -> Unit>()

    @Volatile
    private var stunNonce: String = ""

    @Volatile
    private var stunDeferred: CompletableDeferred<P2PEndpoint>? = null

    private var sessionJob: Job? = null
    private var receiveJob: Job? = null
    private var punchJob: Job? = null
    private var keepaliveJob: Job? = null

    fun start(
        relayUrl: String,
        explicitUdpPort: Int?,
        token: String,
        sendWsJson: (String) -> Boolean,
    ) {
        stop()

        val stunTarget = P2PWire.parseRelayHostAndUdpPort(relayUrl, explicitUdpPort)
        if (stunTarget == null || token.length < 8) {
            _state.value = P2PState.FallbackRelay
            return
        }

        val sid = generateHexId(16)
        val tag = P2PWire.deriveAuthTag(token, sid)
        currentSid = sid
        authTag = tag
        peerAddress = null
        txSeq.set(0)
        _state.value = P2PState.Punching

        sessionJob = scope.launch {
            val socket = runCatching {
                DatagramSocket(0).apply {
                    soTimeout = 250
                    runCatching { trafficClass = 0x10 } // IPTOS_LOWDELAY
                    runCatching { sendBufferSize = 262_144 }
                    runCatching { receiveBufferSize = 262_144 }
                }
            }.getOrNull()

            if (socket == null) {
                _state.value = P2PState.FallbackRelay
                return@launch
            }
            udpSocket = socket
            startReceiveLoop(socket, sid, tag)

            val (stunHost, stunPort) = stunTarget
            val endpoints = mutableListOf<P2PEndpoint>()
            val seen = HashSet<Pair<String, Int>>()

            val publicEp = discoverPublicEndpoint(socket, stunHost, stunPort)
            if (publicEp != null && seen.add(publicEp.ip to publicEp.port)) {
                endpoints.add(publicEp)
            }
            for (localEp in collectLocalEndpoints(socket.localPort)) {
                if (seen.add(localEp.ip to localEp.port)) {
                    endpoints.add(localEp)
                }
            }

            if (endpoints.isEmpty() || currentSid != sid) {
                if (currentSid == sid) {
                    _state.value = P2PState.FallbackRelay
                }
                return@launch
            }

            val sent = sendWsJson(Protocol.encodeP2POffer(sid = sid, udpPort = stunPort, endpoints = endpoints))
            if (!sent && currentSid == sid) {
                _state.value = P2PState.FallbackRelay
                return@launch
            }

            // Overall handshake watchdog: if no P2P link comes up within 5.5s, settle cleanly on VPS Relay
            delay(5_500)
            if (currentSid == sid && _state.value is P2PState.Punching) {
                _state.value = P2PState.FallbackRelay
            }
        }
    }

    fun onAnswer(answer: IncomingMessage.P2PAnswer) {
        val sid = currentSid
        val tag = authTag
        val socket = udpSocket
        if (sid.isBlank() || answer.sid != sid || tag.size != 8 || socket == null || socket.isClosed) {
            return
        }
        if (answer.endpoints.isEmpty()) {
            if (_state.value is P2PState.Punching) {
                _state.value = P2PState.FallbackRelay
            }
            return
        }

        punchJob?.cancel()
        punchJob = scope.launch {
            val targets = answer.endpoints.mapNotNull { ep ->
                runCatching { InetSocketAddress(InetAddress.getByName(ep.ip), ep.port) }.getOrNull()
            }
            if (targets.isEmpty()) {
                if (currentSid == sid && _state.value is P2PState.Punching) {
                    _state.value = P2PState.FallbackRelay
                }
                return@launch
            }

            // Simultaneous UDP Hole Punching: 30 rounds * 100ms = 3.0s
            for (round in 0 until 30) {
                if (!isActive || currentSid != sid || socket.isClosed) return@launch
                if (_state.value is P2PState.Connected) return@launch

                val pktBytes = P2PWire.packControlPacket(
                    type = P2PWire.PKT_PUNCH,
                    authTag = tag,
                    seq = txSeq.incrementAndGet(),
                    timestampMs = System.currentTimeMillis(),
                )
                for (target in targets) {
                    runCatching {
                        socket.send(DatagramPacket(pktBytes, pktBytes.size, target))
                    }
                }
                delay(100)
            }

            if (currentSid == sid && _state.value is P2PState.Punching) {
                _state.value = P2PState.FallbackRelay
            }
        }
    }

    fun sendMove(dx: Float, dy: Float): Boolean =
        sendDeltaPacket(P2PWire.PKT_MOVE, dx, dy)

    fun sendScroll(dx: Float, dy: Float): Boolean =
        sendDeltaPacket(P2PWire.PKT_SCROLL, dx, dy)

    fun allocateTransferId(): Int = nextXferId.getAndIncrement()

    fun currentAuthTag(): ByteArray? {
        val tag = authTag
        return if (isConnected && tag.size == 8) tag else null
    }

    fun registerFileListener(xferId: Int, listener: (UnpackedFilePacket) -> Unit) {
        fileListeners[xferId] = listener
    }

    fun unregisterFileListener(xferId: Int) {
        fileListeners.remove(xferId)
    }

    fun sendRawFilePacket(packetBytes: ByteArray): Boolean {
        val socket = udpSocket ?: return false
        val target = peerAddress ?: return false
        if (_state.value !is P2PState.Connected || socket.isClosed) {
            return false
        }
        return runCatching {
            socket.send(DatagramPacket(packetBytes, packetBytes.size, target))
            true
        }.getOrDefault(false)
    }

    private fun abortAllFileListeners(reason: String) {
        val tag = if (authTag.size == 8) authTag else ByteArray(8)
        val reasonBytes = reason.toByteArray(Charsets.UTF_8)
        val snapshot = fileListeners.entries.toList()
        fileListeners.clear()
        for ((xferId, cb) in snapshot) {
            runCatching {
                cb(
                    UnpackedFilePacket(
                        type = P2PWire.PKT_FILE_ABORT,
                        flags = 0,
                        authTag = tag,
                        xferId = xferId,
                        body = reasonBytes,
                    )
                )
            }
        }
    }

    private fun sendDeltaPacket(type: Byte, dx: Float, dy: Float): Boolean {
        val socket = udpSocket ?: return false
        val target = peerAddress ?: return false
        val tag = authTag
        if (_state.value !is P2PState.Connected || socket.isClosed || tag.size != 8) {
            return false
        }
        val payload = P2PWire.packDeltaPacket(
            type = type,
            authTag = tag,
            seq = txSeq.incrementAndGet(),
            dx = dx,
            dy = dy,
        )
        val packet = DatagramPacket(payload, payload.size, target)

        // TouchpadViewModel's coalescer already runs on Dispatchers.IO; guard against
        // NetworkOnMainThreadException if invoked from a UI gesture tap flush.
        val onMain = runCatching { Looper.myLooper() == Looper.getMainLooper() }.getOrDefault(false)
        return if (onMain) {
            scope.launch {
                runCatching { socket.send(packet) }
            }
            true
        } else {
            runCatching {
                socket.send(packet)
                true
            }.getOrDefault(false)
        }
    }

    fun stop() {
        abortAllFileListeners("P2P disconnected")
        currentSid = ""
        authTag = ByteArray(0)
        peerAddress = null
        stunNonce = ""
        stunDeferred?.cancel()
        stunDeferred = null

        sessionJob?.cancel()
        sessionJob = null
        punchJob?.cancel()
        punchJob = null
        keepaliveJob?.cancel()
        keepaliveJob = null
        receiveJob?.cancel()
        receiveJob = null

        runCatching { udpSocket?.close() }
        udpSocket = null
        _state.value = P2PState.Idle
    }

    private fun startReceiveLoop(socket: DatagramSocket, sid: String, tag: ByteArray) {
        receiveJob?.cancel()
        receiveJob = scope.launch {
            val buf = ByteArray(2048)
            while (isActive && currentSid == sid && !socket.isClosed) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break
                }

                val len = packet.length
                if (len <= 0) continue

                // 1. JSON STUN response from VPS UDP reflector
                if (buf[0] == '{'.code.toByte()) {
                    handleStunReply(buf, len)
                    continue
                }

                // 2. Variable-length authenticated P2P file transfer packet
                if (len >= P2PWire.FILE_HEADER_SIZE && P2PWire.isFilePacketType(buf[4])) {
                    val filePkt = P2PWire.unpackFilePacket(buf, len) ?: continue
                    if (!P2PWire.constantTimeEquals(filePkt.authTag, tag)) continue
                    lastRxElapsedMs = SystemClock.elapsedRealtime()
                    fileListeners[filePkt.xferId]?.invoke(filePkt)
                    continue
                }

                // 3. Authenticated 28-byte P2P control datagram
                val unpacked = P2PWire.unpackPacket(buf, len) ?: continue
                if (!P2PWire.constantTimeEquals(unpacked.authTag, tag)) continue

                val sender = InetSocketAddress(packet.address, packet.port)
                lastRxElapsedMs = SystemClock.elapsedRealtime()

                when (unpacked.type) {
                    P2PWire.PKT_PUNCH -> {
                        val echoedTs = P2PWire.readTimestamp(unpacked.payload8)
                        val ackBytes = P2PWire.packControlPacket(
                            type = P2PWire.PKT_PUNCH_ACK,
                            authTag = tag,
                            seq = txSeq.incrementAndGet(),
                            timestampMs = echoedTs,
                        )
                        runCatching {
                            socket.send(DatagramPacket(ackBytes, ackBytes.size, sender))
                        }
                        if (peerAddress == null) {
                            peerAddress = sender
                        }
                    }
                    P2PWire.PKT_PUNCH_ACK -> {
                        val echoedTs = P2PWire.readTimestamp(unpacked.payload8)
                        val rtt = (System.currentTimeMillis() - echoedTs).coerceIn(1L, 9999L).toInt()
                        peerAddress = sender
                        val hostStr = sender.address?.hostAddress ?: sender.hostString
                        val wasConnected = _state.value is P2PState.Connected
                        _state.value = P2PState.Connected(
                            peerHost = hostStr,
                            peerPort = sender.port,
                            rttMs = rtt,
                        )
                        if (!wasConnected) {
                            startKeepaliveLoop(socket, sid, tag)
                        }
                    }
                    P2PWire.PKT_KEEPALIVE -> {
                        val echoedTs = P2PWire.readTimestamp(unpacked.payload8)
                        val ackBytes = P2PWire.packControlPacket(
                            type = P2PWire.PKT_KEEPALIVE_ACK,
                            authTag = tag,
                            seq = txSeq.incrementAndGet(),
                            timestampMs = echoedTs,
                        )
                        runCatching {
                            socket.send(DatagramPacket(ackBytes, ackBytes.size, sender))
                        }
                    }
                    P2PWire.PKT_KEEPALIVE_ACK -> {
                        val echoedTs = P2PWire.readTimestamp(unpacked.payload8)
                        val rtt = (System.currentTimeMillis() - echoedTs).coerceIn(1L, 9999L).toInt()
                        peerAddress = sender
                        val hostStr = sender.address?.hostAddress ?: sender.hostString
                        _state.value = P2PState.Connected(
                            peerHost = hostStr,
                            peerPort = sender.port,
                            rttMs = rtt,
                        )
                    }
                }
            }
        }
    }

    private fun startKeepaliveLoop(socket: DatagramSocket, sid: String, tag: ByteArray) {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            while (isActive && currentSid == sid && !socket.isClosed) {
                delay(3_000)
                if (currentSid != sid || socket.isClosed) break
                val target = peerAddress ?: break

                val sinceRx = SystemClock.elapsedRealtime() - lastRxElapsedMs
                if (sinceRx > 9_500L) {
                    // NAT mapping dropped or network switched -> seamlessly fall back to VPS Relay
                    abortAllFileListeners("Direct P2P connection lost")
                    peerAddress = null
                    _state.value = P2PState.FallbackRelay
                    break
                }

                val ka = P2PWire.packControlPacket(
                    type = P2PWire.PKT_KEEPALIVE,
                    authTag = tag,
                    seq = txSeq.incrementAndGet(),
                    timestampMs = System.currentTimeMillis(),
                )
                runCatching {
                    socket.send(DatagramPacket(ka, ka.size, target))
                }
            }
        }
    }

    private suspend fun discoverPublicEndpoint(
        socket: DatagramSocket,
        stunHost: String,
        stunPort: Int,
    ): P2PEndpoint? {
        val target = runCatching {
            InetSocketAddress(InetAddress.getByName(stunHost), stunPort)
        }.getOrNull() ?: return null

        val nonce = generateHexId(12)
        val deferred = CompletableDeferred<P2PEndpoint>()
        stunNonce = nonce
        stunDeferred = deferred

        val probeBytes = """{"t":"p2p_stun","nonce":"$nonce"}""".toByteArray(Charsets.UTF_8)
        return try {
            for (attempt in 0 until 4) {
                if (socket.isClosed) return null
                runCatching {
                    socket.send(DatagramPacket(probeBytes, probeBytes.size, target))
                }
                val result = withTimeoutOrNull(280L) { deferred.await() }
                if (result != null) return result
            }
            null
        } finally {
            if (stunDeferred === deferred) {
                stunDeferred = null
            }
        }
    }

    private fun handleStunReply(buf: ByteArray, length: Int) {
        val deferred = stunDeferred ?: return
        if (deferred.isCompleted) return
        val expectedNonce = stunNonce
        runCatching {
            val raw = String(buf, 0, length, Charsets.UTF_8)
            val obj = json.parseToJsonElement(raw).jsonObject
            if (obj["t"]?.jsonPrimitive?.contentOrNull != "p2p_stun_res") return
            if (obj["nonce"]?.jsonPrimitive?.contentOrNull != expectedNonce) return
            val ip = obj["ip"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val port = obj["port"]?.jsonPrimitive?.intOrNull ?: -1
            if (ip.isNotEmpty() && port in 1..65535) {
                deferred.complete(P2PEndpoint(ip = ip, port = port))
            }
        }
    }

    private fun collectLocalEndpoints(localPort: Int): List<P2PEndpoint> {
        if (localPort !in 1..65535) return emptyList()
        val result = mutableListOf<P2PEndpoint>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress
                        if (!ip.isNullOrBlank()) {
                            result.add(P2PEndpoint(ip = ip, port = localPort))
                        }
                    }
                }
            }
        }
        return result
    }

    private fun generateHexId(chars: Int): String {
        val bytes = ByteArray((chars + 1) / 2)
        random.nextBytes(bytes)
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.substring(0, chars)
    }
}
