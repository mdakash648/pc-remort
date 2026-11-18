package com.remotepad.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address

data class Ipv4Subnet(
    val address: String,
    val prefixLength: Int,
) {
    fun contains(candidateHost: String): Boolean = isSameSubnet(address, prefixLength, candidateHost)

    companion object {
        fun parseIpv4ToInt(host: String): Int? {
            val parts = host.trim().split('.')
            if (parts.size != 4) return null
            var result = 0
            for (part in parts) {
                if (part.isEmpty() || part.length > 3) return null
                val octet = part.toIntOrNull() ?: return null
                if (octet !in 0..255) return null
                result = (result shl 8) or octet
            }
            return result
        }

        fun isPrivateIpv4(host: String): Boolean {
            val ip = parseIpv4ToInt(host) ?: return false
            val b0 = (ip ushr 24) and 0xFF
            val b1 = (ip ushr 16) and 0xFF
            return b0 == 10 ||
                (b0 == 172 && b1 in 16..31) ||
                (b0 == 192 && b1 == 168)
        }

        fun isSameSubnet(localIp: String, prefixLength: Int, targetIp: String): Boolean {
            val localInt = parseIpv4ToInt(localIp) ?: return false
            val targetInt = parseIpv4ToInt(targetIp) ?: return false
            val clampedPrefix = prefixLength.coerceIn(0, 32)
            val mask = if (clampedPrefix == 0) 0 else (-1 shl (32 - clampedPrefix))
            return (localInt and mask) == (targetInt and mask)
        }
    }
}

data class NetworkSnapshot(
    val hasNetwork: Boolean,
    val networkHandle: Long = 0L,
    val isWifiOrEthernet: Boolean = false,
    val isCellular: Boolean = false,
    val isVpn: Boolean = false,
    val ipv4Subnets: Set<Ipv4Subnet> = emptySet(),
) {
    fun matchesLocalSubnet(candidateHost: String): Boolean {
        if (!hasNetwork || ipv4Subnets.isEmpty()) return false
        return ipv4Subnets.any { it.contains(candidateHost) }
    }

    /**
     * Returns true only when the underlying active network, transport type, or local IPv4
     * address changed — ignoring noisy Android callbacks like Wi-Fi RSSI / bandwidth updates.
     */
    fun hasMeaningfulChangeFrom(previous: NetworkSnapshot): Boolean {
        if (hasNetwork != previous.hasNetwork) return true
        if (!hasNetwork) return false
        if (networkHandle != previous.networkHandle) return true
        if (isWifiOrEthernet != previous.isWifiOrEthernet) return true
        if (isCellular != previous.isCellular) return true
        if (isVpn != previous.isVpn) return true
        if (ipv4Subnets != previous.ipv4Subnets) return true
        return false
    }

    companion object {
        val OFFLINE = NetworkSnapshot(hasNetwork = false)
    }
}

/**
 * Monitors Android's active default network via [ConnectivityManager.registerDefaultNetworkCallback]
 * and emits clean [NetworkSnapshot] transitions whenever the user switches between Wi-Fi networks,
 * Wi-Fi <-> Mobile Data, or offline <-> online.
 */
class NetworkMonitor(
    context: Context,
    private val onNetworkChanged: (NetworkSnapshot) -> Unit,
) {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val lock = Any()
    private var registered = false
    private var lastSnapshot: NetworkSnapshot = NetworkSnapshot.OFFLINE

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            dispatchCurrentSnapshot()
        }

        override fun onLost(network: Network) {
            // During Wi-Fi <-> Cellular handoff, Android may call onAvailable(newNet) BEFORE
            // onLost(oldNet). Always inspect cm.activeNetwork instead of assuming offline.
            val active = cm?.activeNetwork
            if (active == null || active == network) {
                emitIfChanged(NetworkSnapshot.OFFLINE)
            } else {
                dispatchCurrentSnapshot()
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (network == cm?.activeNetwork) {
                dispatchCurrentSnapshot()
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (network == cm?.activeNetwork) {
                dispatchCurrentSnapshot()
            }
        }
    }

    fun start() {
        synchronized(lock) {
            if (registered) return
            // Capture the current snapshot BEFORE registering so the immediate initial
            // callback from Android for the already-active network does not cause a false switch.
            lastSnapshot = queryCurrentSnapshot()
            val manager = cm ?: return
            runCatching {
                manager.registerDefaultNetworkCallback(callback)
                registered = true
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!registered) return
            registered = false
            runCatching { cm?.unregisterNetworkCallback(callback) }
        }
    }

    fun queryCurrentSnapshot(): NetworkSnapshot {
        val manager = cm ?: return NetworkSnapshot.OFFLINE
        val active = manager.activeNetwork ?: return NetworkSnapshot.OFFLINE
        val caps = manager.getNetworkCapabilities(active) ?: return NetworkSnapshot.OFFLINE
        val linkProps = manager.getLinkProperties(active)

        val isWifiOrEth = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val isCell = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

        val subnets = LinkedHashSet<Ipv4Subnet>()
        linkProps?.linkAddresses?.forEach { linkAddr ->
            val addr = linkAddr.address
            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                val hostAddr = addr.hostAddress
                if (!hostAddr.isNullOrBlank()) {
                    subnets.add(Ipv4Subnet(hostAddr, linkAddr.prefixLength))
                }
            }
        }

        return NetworkSnapshot(
            hasNetwork = true,
            networkHandle = active.networkHandle,
            isWifiOrEthernet = isWifiOrEth,
            isCellular = isCell,
            isVpn = isVpn,
            ipv4Subnets = subnets,
        )
    }

    private fun dispatchCurrentSnapshot() {
        val snapshot = queryCurrentSnapshot()
        emitIfChanged(snapshot)
    }

    private fun emitIfChanged(snapshot: NetworkSnapshot) {
        val changed = synchronized(lock) {
            if (snapshot.hasMeaningfulChangeFrom(lastSnapshot)) {
                lastSnapshot = snapshot
                true
            } else {
                false
            }
        }
        if (changed) {
            onNetworkChanged(snapshot)
        }
    }
}
