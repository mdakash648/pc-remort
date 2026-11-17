package com.remotepad.app.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

data class DiscoveredPc(
    val name: String,
    val host: String,
    val port: Int,
    val lastSeenMs: Long,
) {
    val key: String get() = "$host:$port"
}

object DiscoveryProtocol {
    const val DISCOVERY_PORT = 8766
    const val DISCOVER_PAYLOAD = """{"t":"discover","v":1}"""

    private val json = Json { ignoreUnknownKeys = true }

    fun parseAnnounce(payload: String, senderHost: String, nowMs: Long): DiscoveredPc? {
        if (senderHost.isBlank()) return null
        return try {
            val obj = json.parseToJsonElement(payload).jsonObject
            if (obj["t"]?.jsonPrimitive?.contentOrNull != "announce") return null
            val port = obj["port"]?.jsonPrimitive?.intOrNull ?: return null
            if (port !in 1..65535) return null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: senderHost
            DiscoveredPc(
                name = name,
                host = senderHost,
                port = port,
                lastSeenMs = nowMs,
            )
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Discovers RemotePad PC agents on the local Wi-Fi network using both:
 * 1. Active UDP broadcast probes to port 8766 (fast <50ms discovery).
 * 2. Passive listening on UDP port 8766 for periodic PC beacons (works even if
 *    Windows Firewall blocks inbound UDP probes on the PC).
 */
class LanDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _discoveredPcs = MutableStateFlow<List<DiscoveredPc>>(emptyList())
    val discoveredPcs: StateFlow<List<DiscoveredPc>> = _discoveredPcs.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val stateLock = Any()
    private var scanJob: Job? = null
    private var probeJob: Job? = null
    private var activeSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val probeTrigger = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        synchronized(stateLock) {
            if (scanJob?.isActive == true) {
                probeTrigger.trySend(Unit)
                return
            }
            acquireMulticastLock()
            _isScanning.value = true

            val socket = createDiscoverySocket()
            activeSocket = socket
            if (socket == null) {
                _isScanning.value = false
                releaseMulticastLock()
                return
            }

            scanJob = scope.launch {
                listenLoop(socket)
            }
            probeJob = scope.launch {
                probeLoop(socket)
            }
            probeTrigger.trySend(Unit)
        }
    }

    fun probeNow() {
        synchronized(stateLock) {
            if (scanJob?.isActive != true) {
                start()
                return
            }
        }
        probeTrigger.trySend(Unit)
    }

    fun stop() {
        synchronized(stateLock) {
            _isScanning.value = false
            probeJob?.cancel()
            probeJob = null
            scanJob?.cancel()
            scanJob = null
            runCatching { activeSocket?.close() }
            activeSocket = null
            releaseMulticastLock()
        }
    }

    fun shutdown() {
        stop()
        probeTrigger.close()
    }

    private fun createDiscoverySocket(): DatagramSocket? {
        // Bind to 8766 so we receive both PC periodic beacons and unicast replies to our probes.
        // Fall back to an ephemeral port if 8766 is already occupied by another app.
        return runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = SOCKET_TIMEOUT_MS
                bind(InetSocketAddress(DiscoveryProtocol.DISCOVERY_PORT))
            }
        }.getOrElse {
            runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = SOCKET_TIMEOUT_MS
                    bind(InetSocketAddress(0))
                }
            }.getOrNull()
        }
    }

    private suspend fun listenLoop(socket: DatagramSocket) {
        val buffer = ByteArray(2048)
        val pcsByKey = LinkedHashMap<String, DiscoveredPc>()

        try {
            while (scope.isActive && !socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    val senderHost = packet.address?.hostAddress.orEmpty()
                    val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    val now = SystemClock.elapsedRealtime()
                    val discovered = DiscoveryProtocol.parseAnnounce(text, senderHost, now)
                    if (discovered != null) {
                        pcsByKey[discovered.key] = discovered
                        publishFiltered(pcsByKey, now)
                    }
                } catch (_: SocketTimeoutException) {
                    val now = SystemClock.elapsedRealtime()
                    pruneStale(pcsByKey, now)
                } catch (_: Exception) {
                    if (socket.isClosed) break
                    delay(200)
                }
            }
        } finally {
            _isScanning.value = false
        }
    }

    private suspend fun probeLoop(socket: DatagramSocket) {
        val probeBytes = DiscoveryProtocol.DISCOVER_PAYLOAD.toByteArray(Charsets.UTF_8)
        while (scope.isActive && !socket.isClosed) {
            sendBroadcastProbe(socket, probeBytes)
            // Wait up to PROBE_INTERVAL_MS, waking early if probeNow() is called.
            var waited = 0L
            while (waited < PROBE_INTERVAL_MS && scope.isActive && !socket.isClosed) {
                if (probeTrigger.tryReceive().isSuccess) break
                delay(200)
                waited += 200
            }
        }
    }

    private fun sendBroadcastProbe(socket: DatagramSocket, probeBytes: ByteArray) {
        val targets = collectBroadcastAddresses()
        for (address in targets) {
            runCatching {
                val packet = DatagramPacket(
                    probeBytes,
                    probeBytes.size,
                    address,
                    DiscoveryProtocol.DISCOVERY_PORT,
                )
                socket.send(packet)
            }
        }
    }

    private fun collectBroadcastAddresses(): List<InetAddress> {
        val result = LinkedHashSet<InetAddress>()
        runCatching { result.add(InetAddress.getByName("255.255.255.255")) }
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                for (ifAddr in iface.interfaceAddresses) {
                    val bcast = ifAddr.broadcast
                    if (bcast is Inet4Address) {
                        result.add(bcast)
                    }
                }
            }
        }
        return result.toList()
    }

    private fun pruneStale(pcsByKey: MutableMap<String, DiscoveredPc>, nowMs: Long) {
        val removed = pcsByKey.entries.removeAll { (_, pc) ->
            (nowMs - pc.lastSeenMs) > STALE_TIMEOUT_MS
        }
        if (removed) {
            publishFiltered(pcsByKey, nowMs)
        }
    }

    private fun publishFiltered(pcsByKey: MutableMap<String, DiscoveredPc>, nowMs: Long) {
        pcsByKey.entries.removeAll { (_, pc) -> (nowMs - pc.lastSeenMs) > STALE_TIMEOUT_MS }
        _discoveredPcs.value = pcsByKey.values.sortedBy { it.name.lowercase() }
    }

    private fun acquireMulticastLock() {
        runCatching {
            val existing = multicastLock
            if (existing != null && existing.isHeld) return
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return
            val lock = wifiManager.createMulticastLock("RemotePad:LanDiscovery")
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
        }
    }

    private fun releaseMulticastLock() {
        runCatching {
            multicastLock?.let { lock ->
                if (lock.isHeld) lock.release()
            }
        }
        multicastLock = null
    }

    companion object {
        private const val SOCKET_TIMEOUT_MS = 1000
        private const val PROBE_INTERVAL_MS = 2500L
        private const val STALE_TIMEOUT_MS = 12_000L
    }
}
