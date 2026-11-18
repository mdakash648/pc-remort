package com.remotepad.app

import com.remotepad.app.network.ConnectOutcome
import com.remotepad.app.network.ConnectionTransportAdapter
import com.remotepad.app.network.DiscoveredPc
import com.remotepad.app.network.Ipv4Subnet
import com.remotepad.app.network.NetworkSnapshot
import com.remotepad.app.network.ReconnectBudget
import com.remotepad.app.network.ReconnectCycleResult
import com.remotepad.app.network.RelayConfig
import com.remotepad.app.network.WifiFirstConnector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionOrchestratorTest {

    private class FakeTransportAdapter(
        var network: NetworkSnapshot,
        var savedHost: String = "",
        var savedPort: Int = 8765,
        var relay: RelayConfig? = RelayConfig("ws://182.255.88.108:8770", "akash-pc", "rpad-7f4c9a2e8b1d6305"),
        var discoveredPc: DiscoveredPc? = null,
        var lanSucceedsFor: Set<String> = emptySet(),
        var relaySucceeds: Boolean = true,
    ) : ConnectionTransportAdapter {
        val events = mutableListOf<String>()

        override fun currentNetwork(): NetworkSnapshot = network
        override fun savedLanHost(): String = savedHost
        override fun savedLanPort(): Int = savedPort
        override fun relayConfig(): RelayConfig? = relay

        override fun onSearchingWifi() {
            events.add("search_wifi")
        }

        override fun onDiscoveredPcSelected(pc: DiscoveredPc) {
            events.add("select_pc:${pc.host}:${pc.port}")
        }

        override suspend fun discoverLanPc(timeoutMs: Long, freshScan: Boolean): DiscoveredPc? {
            events.add("discover:timeout=$timeoutMs,fresh=$freshScan")
            return discoveredPc
        }

        override suspend fun tryConnectLan(host: String, port: Int, timeoutMs: Long): Boolean {
            events.add("lan:$host:$port")
            return "$host:$port" in lanSucceedsFor
        }

        override suspend fun tryConnectRelay(config: RelayConfig, timeoutMs: Long): Boolean {
            events.add("vps:${config.deviceId}")
            return relaySucceeds
        }

        override fun stopDiscovery() {
            events.add("stop_discovery")
        }
    }

    private fun homeWifiSnapshot(handle: Long = 100L) = NetworkSnapshot(
        hasNetwork = true,
        networkHandle = handle,
        isWifiOrEthernet = true,
        isCellular = false,
        ipv4Subnets = setOf(Ipv4Subnet("192.168.0.42", 24)),
    )

    private fun officeWifiSnapshot(handle: Long = 200L) = NetworkSnapshot(
        hasNetwork = true,
        networkHandle = handle,
        isWifiOrEthernet = true,
        isCellular = false,
        ipv4Subnets = setOf(Ipv4Subnet("10.20.30.15", 24)),
    )

    private fun cellularSnapshot(handle: Long = 300L) = NetworkSnapshot(
        hasNetwork = true,
        networkHandle = handle,
        isWifiOrEthernet = false,
        isCellular = true,
        ipv4Subnets = setOf(Ipv4Subnet("100.64.12.9", 32)),
    )

    @Test
    fun ipv4SubnetMatchingAndPrivateChecksWorkAccurately() {
        assertTrue(Ipv4Subnet.isSameSubnet("192.168.0.42", 24, "192.168.0.105"))
        assertFalse(Ipv4Subnet.isSameSubnet("192.168.0.42", 24, "192.168.1.105"))
        assertTrue(Ipv4Subnet.isSameSubnet("10.0.1.5", 16, "10.0.254.99"))
        assertFalse(Ipv4Subnet.isSameSubnet("10.0.1.5", 16, "10.1.0.5"))
        assertFalse(Ipv4Subnet.isSameSubnet("192.168.0.42", 24, "not-an-ip"))

        assertTrue(Ipv4Subnet.isPrivateIpv4("192.168.1.10"))
        assertTrue(Ipv4Subnet.isPrivateIpv4("10.0.0.2"))
        assertTrue(Ipv4Subnet.isPrivateIpv4("172.16.5.1"))
        assertFalse(Ipv4Subnet.isPrivateIpv4("182.255.88.108"))
    }

    @Test
    fun networkSnapshotDetectsRealTransitionsAndIgnoresNoise() {
        val home1 = homeWifiSnapshot(handle = 100L)
        val homeSame = homeWifiSnapshot(handle = 100L)
        val office = officeWifiSnapshot(handle = 200L)
        val cell = cellularSnapshot(handle = 300L)

        assertFalse(homeSame.hasMeaningfulChangeFrom(home1))
        assertTrue(office.hasMeaningfulChangeFrom(home1))
        assertTrue(cell.hasMeaningfulChangeFrom(home1))
        assertTrue(NetworkSnapshot.OFFLINE.hasMeaningfulChangeFrom(home1))
        assertTrue(home1.hasMeaningfulChangeFrom(NetworkSnapshot.OFFLINE))
        assertFalse(NetworkSnapshot.OFFLINE.hasMeaningfulChangeFrom(NetworkSnapshot.OFFLINE))

        val homeNewIp = home1.copy(ipv4Subnets = setOf(Ipv4Subnet("192.168.0.88", 24)))
        assertTrue(homeNewIp.hasMeaningfulChangeFrom(home1))
    }

    @Test
    fun triesWifiFirstAndStaysOnLanWhenDiscoveredPcConnects() = runBlocking {
        val pc = DiscoveredPc(name = "MdAkash", host = "192.168.0.105", port = 8765, lastSeenMs = 1L)
        val adapter = FakeTransportAdapter(
            network = homeWifiSnapshot(),
            discoveredPc = pc,
            lanSucceedsFor = setOf("192.168.0.105:8765"),
        )
        val connector = WifiFirstConnector(adapter)

        val outcome = connector.attemptOnce(freshNetwork = true)

        assertEquals(ConnectOutcome.CONNECTED_WIFI, outcome)
        assertTrue(adapter.events.contains("lan:192.168.0.105:8765"))
        assertFalse(adapter.events.any { it.startsWith("vps:") })
    }

    @Test
    fun fallsBackToVpsWhenDiscoveredLanPcFailsToConnect() = runBlocking {
        val pc = DiscoveredPc(name = "MdAkash", host = "192.168.0.105", port = 8765, lastSeenMs = 1L)
        val adapter = FakeTransportAdapter(
            network = homeWifiSnapshot(),
            discoveredPc = pc,
            lanSucceedsFor = emptySet(),
            relaySucceeds = true,
        )
        val connector = WifiFirstConnector(adapter)

        val outcome = connector.attemptOnce(freshNetwork = true)

        assertEquals(ConnectOutcome.CONNECTED_VPS, outcome)
        val lanIdx = adapter.events.indexOf("lan:192.168.0.105:8765")
        val vpsIdx = adapter.events.indexOf("vps:akash-pc")
        assertTrue("Expected LAN attempt before VPS", lanIdx in 0 until vpsIdx)
    }

    @Test
    fun triesSavedLanIpWhenSameSubnetAndDiscoveryFindsNothing() = runBlocking {
        val adapter = FakeTransportAdapter(
            network = homeWifiSnapshot(),
            savedHost = "192.168.0.105",
            savedPort = 8765,
            discoveredPc = null,
            lanSucceedsFor = setOf("192.168.0.105:8765"),
        )
        val connector = WifiFirstConnector(adapter)

        val outcome = connector.attemptOnce(freshNetwork = false)

        assertEquals(ConnectOutcome.CONNECTED_WIFI, outcome)
        assertTrue(adapter.events.contains("lan:192.168.0.105:8765"))
        assertFalse(adapter.events.any { it.startsWith("vps:") })
    }

    @Test
    fun skipsSavedLanIpOnDifferentWifiSubnetAndFallsBackToVps() = runBlocking {
        val adapter = FakeTransportAdapter(
            network = officeWifiSnapshot(),
            savedHost = "192.168.0.105",
            savedPort = 8765,
            discoveredPc = null,
            lanSucceedsFor = emptySet(),
            relaySucceeds = true,
        )
        val connector = WifiFirstConnector(adapter)

        val outcome = connector.attemptOnce(freshNetwork = true)

        assertEquals(ConnectOutcome.CONNECTED_VPS, outcome)
        assertFalse(adapter.events.any { it.startsWith("lan:") })
        assertTrue(adapter.events.contains("vps:akash-pc"))
    }

    @Test
    fun networkSwitchFromCellularToHomeWifiRetriesWifiFirstAndUpgradesToLan() = runBlocking {
        val adapter = FakeTransportAdapter(
            network = cellularSnapshot(),
            savedHost = "192.168.0.105",
            discoveredPc = null,
            relaySucceeds = true,
        )
        val connector = WifiFirstConnector(adapter)

        // 1. On Mobile Data: probes LAN briefly, then connects via Internet (VPS)
        val firstOutcome = connector.attemptOnce(freshNetwork = true)
        assertEquals(ConnectOutcome.CONNECTED_VPS, firstOutcome)
        assertTrue(adapter.events.contains("vps:akash-pc"))

        // 2. Network changes to Home Wi-Fi: tries Wi-Fi first -> connects via Local Wi-Fi
        adapter.events.clear()
        adapter.network = homeWifiSnapshot()
        adapter.discoveredPc = DiscoveredPc("MdAkash", "192.168.0.105", 8765, 2L)
        adapter.lanSucceedsFor = setOf("192.168.0.105:8765")

        val secondOutcome = connector.attemptOnce(freshNetwork = true)
        assertEquals(ConnectOutcome.CONNECTED_WIFI, secondOutcome)
        assertTrue(adapter.events.contains("lan:192.168.0.105:8765"))
        assertFalse(adapter.events.any { it.startsWith("vps:") })
    }

    @Test
    fun returnsNoNetworkImmediatelyWhenOffline() = runBlocking {
        val adapter = FakeTransportAdapter(network = NetworkSnapshot.OFFLINE)
        val connector = WifiFirstConnector(adapter)

        val outcome = connector.attemptOnce(freshNetwork = true)

        assertEquals(ConnectOutcome.NO_NETWORK, outcome)
        assertFalse(adapter.events.any { it.startsWith("lan:") || it.startsWith("vps:") })
    }

    @Test
    fun stopsAfterExactlyFiveReconnectAttemptsAndRefusesExtraAutoAttempts() = runBlocking {
        val adapter = FakeTransportAdapter(
            network = homeWifiSnapshot(),
            discoveredPc = null,
            relaySucceeds = false,
        )
        val connector = WifiFirstConnector(adapter)
        val budget = ReconnectBudget()
        val startedAttempts = mutableListOf<Int>()
        val waitAfterAttempts = mutableListOf<Int>()

        val result = connector.connectWithBudget(
            budget = budget,
            freshNetwork = true,
            onAttemptStarting = { attempt, _ -> startedAttempts.add(attempt) },
            onWaitBeforeRetry = { failed, _ -> waitAfterAttempts.add(failed) },
        )

        assertEquals(ReconnectCycleResult.EXHAUSTED, result)
        assertEquals(listOf(1, 2, 3, 4, 5), startedAttempts)
        assertEquals(listOf(1, 2, 3, 4), waitAfterAttempts)
        assertEquals(5, budget.failedAttempts)
        assertTrue(budget.isExhausted)

        // Calling again while exhausted without connecting/user-reset makes 0 additional attempts
        val eventsBefore = adapter.events.size
        val secondResult = connector.connectWithBudget(
            budget = budget,
            freshNetwork = true,
            onAttemptStarting = { attempt, _ -> startedAttempts.add(attempt) },
        )
        assertEquals(ReconnectCycleResult.EXHAUSTED, secondResult)
        assertEquals(eventsBefore, adapter.events.size)
        assertEquals(listOf(1, 2, 3, 4, 5), startedAttempts)
    }

    @Test
    fun resetsBudgetAfterConnectingSoNextDisconnectGetsFiveAttemptsAgain() = runBlocking {
        val adapter = FakeTransportAdapter(
            network = homeWifiSnapshot(),
            discoveredPc = null,
            relaySucceeds = false,
        )
        val connector = WifiFirstConnector(adapter)
        val budget = ReconnectBudget()

        // Episode 1: fails twice, succeeds on attempt 3 -> resets budget back to 0
        val episode1Attempts = mutableListOf<Int>()
        val episode1Result = connector.connectWithBudget(
            budget = budget,
            freshNetwork = true,
            onAttemptStarting = { attempt, _ ->
                episode1Attempts.add(attempt)
                if (attempt == 3) {
                    adapter.relaySucceeds = true
                }
            },
        )
        assertEquals(ReconnectCycleResult.CONNECTED_VPS, episode1Result)
        assertEquals(listOf(1, 2, 3), episode1Attempts)
        assertEquals(0, budget.failedAttempts)
        assertFalse(budget.isExhausted)

        // Episode 2: later disconnects while PC is unreachable -> must try full 5 times again
        adapter.relaySucceeds = false
        val episode2Attempts = mutableListOf<Int>()
        val episode2Result = connector.connectWithBudget(
            budget = budget,
            freshNetwork = false,
            onAttemptStarting = { attempt, _ -> episode2Attempts.add(attempt) },
        )
        assertEquals(ReconnectCycleResult.EXHAUSTED, episode2Result)
        assertEquals(listOf(1, 2, 3, 4, 5), episode2Attempts)
        assertTrue(budget.isExhausted)

        // Episode 3: user connects later (manual or retry) -> connected -> disconnects -> 5 attempts again
        budget.onConnected()
        assertFalse(budget.isExhausted)
        assertEquals(0, budget.failedAttempts)

        val episode3Attempts = mutableListOf<Int>()
        val episode3Result = connector.connectWithBudget(
            budget = budget,
            freshNetwork = false,
            onAttemptStarting = { attempt, _ -> episode3Attempts.add(attempt) },
        )
        assertEquals(ReconnectCycleResult.EXHAUSTED, episode3Result)
        assertEquals(listOf(1, 2, 3, 4, 5), episode3Attempts)
    }

    @Test
    fun networkSwitchMidReconnectPreservesRemainingBudgetUpToFiveTotal() = runBlocking {
        val adapter = FakeTransportAdapter(
            network = homeWifiSnapshot(),
            discoveredPc = null,
            relaySucceeds = false,
        )
        val connector = WifiFirstConnector(adapter)
        val budget = ReconnectBudget()

        // Simulate 2 failed attempts on Wi-Fi before a network switch cancels the first cycle
        val firstBatch = mutableListOf<Int>()
        var cancelled = false
        val firstResult = connector.connectWithBudget(
            budget = budget,
            freshNetwork = true,
            isCancelled = { cancelled },
            onAttemptStarting = { attempt, _ -> firstBatch.add(attempt) },
            onWaitBeforeRetry = { failed, _ ->
                if (failed == 2) cancelled = true
            },
        )
        assertEquals(ReconnectCycleResult.STOPPED, firstResult)
        assertEquals(listOf(1, 2), firstBatch)
        assertEquals(3, budget.remainingAttempts)

        // Network changes to cellular -> resumes using only the remaining 3 attempts (3, 4, 5)
        adapter.network = cellularSnapshot()
        val secondBatch = mutableListOf<Int>()
        val secondResult = connector.connectWithBudget(
            budget = budget,
            freshNetwork = true,
            onAttemptStarting = { attempt, _ -> secondBatch.add(attempt) },
        )
        assertEquals(ReconnectCycleResult.EXHAUSTED, secondResult)
        assertEquals(listOf(3, 4, 5), secondBatch)
        assertTrue(budget.isExhausted)
    }

    @Test
    fun offlineCheckDoesNotConsumeReconnectAttempts() = runBlocking {
        val adapter = FakeTransportAdapter(
            network = NetworkSnapshot.OFFLINE,
            relaySucceeds = false,
        )
        val connector = WifiFirstConnector(adapter)
        val budget = ReconnectBudget()

        val offlineResult = connector.connectWithBudget(budget = budget, freshNetwork = true)
        assertEquals(ReconnectCycleResult.NO_NETWORK, offlineResult)
        assertEquals(0, budget.failedAttempts)
        assertEquals(5, budget.remainingAttempts)

        // Once back online, all 5 attempts remain available
        adapter.network = homeWifiSnapshot()
        val attempts = mutableListOf<Int>()
        val onlineResult = connector.connectWithBudget(
            budget = budget,
            freshNetwork = true,
            onAttemptStarting = { attempt, _ -> attempts.add(attempt) },
        )
        assertEquals(ReconnectCycleResult.EXHAUSTED, onlineResult)
        assertEquals(listOf(1, 2, 3, 4, 5), attempts)
    }
}
