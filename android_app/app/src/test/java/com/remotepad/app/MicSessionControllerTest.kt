package com.remotepad.app

import com.remotepad.app.input.MicSessionAdapter
import com.remotepad.app.input.MicSessionController
import com.remotepad.app.network.ConnectionState
import com.remotepad.app.network.TransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicSessionControllerTest {

    private class FakeMicAdapter(
        var hasPermission: Boolean = true,
        var state: ConnectionState = lanConnected(),
        var autoRecovering: Boolean = true,
        var startStreamSucceeds: Boolean = true,
    ) : MicSessionAdapter {
        var streamingHost: String? = null
        var streamingPort: Int = -1
        var locksHeld: Boolean = false
        var fgRequests: Int = 0
        var fgReleases: Int = 0
        val events = mutableListOf<String>()

        override fun hasRecordAudioPermission(): Boolean = hasPermission
        override fun currentConnectionState(): ConnectionState = state
        override fun isAutoRecovering(): Boolean = autoRecovering

        override fun isStreamingTo(host: String, port: Int): Boolean {
            return streamingHost == host && streamingPort == port
        }

        override fun startAudioStream(host: String, port: Int): Boolean {
            events.add("start_stream:$host:$port")
            if (!startStreamSucceeds) return false
            streamingHost = host
            streamingPort = port
            return true
        }

        override fun stopAudioStream() {
            if (streamingHost != null) {
                events.add("stop_stream")
            }
            streamingHost = null
            streamingPort = -1
        }

        override fun acquireKeepAliveLocks() {
            if (!locksHeld) {
                locksHeld = true
                events.add("acquire_locks")
            }
        }

        override fun releaseKeepAliveLocks() {
            if (locksHeld) {
                locksHeld = false
                events.add("release_locks")
            }
        }

        override fun requestMicrophoneForegroundService() {
            fgRequests += 1
            events.add("request_mic_fgs")
        }

        override fun releaseMicrophoneForegroundService() {
            fgReleases += 1
            events.add("release_mic_fgs")
        }
    }

    @Test
    fun manualEnableRequestsMicrophoneForegroundBeforeStartingAudioStream() {
        val adapter = FakeMicAdapter()
        val controller = MicSessionController(adapter)

        assertTrue(controller.setMicEnabled(true))
        assertTrue(controller.isMicEnabled.value)
        assertTrue(controller.shouldHoldMicrophoneForeground)
        assertFalse(controller.isMicStreaming.value)
        assertEquals(listOf("request_mic_fgs"), adapter.events)

        controller.onForegroundServiceMicReady()
        assertTrue(controller.isMicEnabled.value)
        assertTrue(controller.isMicStreaming.value)
        assertTrue(adapter.locksHeld)
        assertEquals("192.168.0.105", adapter.streamingHost)
        assertEquals(8768, adapter.streamingPort)
        assertEquals(
            listOf("request_mic_fgs", "acquire_locks", "start_stream:192.168.0.105:8768"),
            adapter.events,
        )
    }

    @Test
    fun rejectsEnableWithoutPermissionOrLanConnection() {
        val noPerm = FakeMicAdapter(hasPermission = false)
        val c1 = MicSessionController(noPerm)
        assertFalse(c1.setMicEnabled(true))
        assertFalse(c1.isMicEnabled.value)
        assertNotNull(c1.micError.value)
        assertEquals(0, noPerm.fgRequests)

        val disconnected = FakeMicAdapter(state = ConnectionState.Disconnected)
        val c2 = MicSessionController(disconnected)
        assertFalse(c2.setMicEnabled(true))
        assertEquals("Connect to PC first", c2.micError.value)

        val relay = FakeMicAdapter(state = vpsConnected())
        val c3 = MicSessionController(relay)
        assertTrue(c3.setMicEnabled(true))
        assertTrue(c3.isMicEnabled.value)
        assertNull(c3.micError.value)
    }

    @Test
    fun briefLanReconnectPausesAudioThenAutomaticallyResumesWithoutDroppingMicFgs() {
        val adapter = FakeMicAdapter()
        val controller = MicSessionController(adapter)

        controller.setMicEnabled(true)
        controller.onForegroundServiceMicReady()
        assertTrue(controller.isMicStreaming.value)

        // Wi-Fi temporarily drops and auto-reconnect starts
        adapter.state = ConnectionState.Error("Socket closed")
        controller.onConnectionStateChanged(adapter.state)
        assertTrue("Mic should remain enabled during auto-reconnect", controller.isMicEnabled.value)
        assertTrue("Mic FGS must remain held during brief reconnect", controller.shouldHoldMicrophoneForeground)
        assertFalse("Stream should pause while disconnected", controller.isMicStreaming.value)
        assertEquals(0, adapter.fgReleases)

        adapter.state = ConnectionState.Connecting("Wi-Fi (1/5)...")
        controller.onConnectionStateChanged(adapter.state)
        assertTrue(controller.isMicEnabled.value)
        assertFalse(controller.isMicStreaming.value)

        // PC reconnects on Local Wi-Fi (even with a new LAN IP)
        adapter.state = lanConnected(host = "192.168.0.120", micPort = 8769)
        controller.onConnectionStateChanged(adapter.state)
        assertTrue(controller.isMicEnabled.value)
        assertTrue(controller.isMicStreaming.value)
        assertEquals("192.168.0.120", adapter.streamingHost)
        assertEquals(8769, adapter.streamingPort)
    }

    @Test
    fun micPersistsAcrossVpsSwitchAndStopsCleanlyWhenReconnectExhausted() {
        val adapter = FakeMicAdapter()
        val controller = MicSessionController(adapter)

        controller.setMicEnabled(true)
        controller.onForegroundServiceMicReady()
        assertTrue(controller.isMicStreaming.value)

        // Switches to VPS relay -> mic stays enabled and streams via relay tunnel
        adapter.state = vpsConnected()
        controller.onConnectionStateChanged(adapter.state)
        assertTrue("Mic remains enabled when switching to VPS", controller.isMicEnabled.value)
        assertTrue("Mic continues streaming over relay tunnel", controller.isMicStreaming.value)
        assertTrue(controller.shouldHoldMicrophoneForeground)
        assertTrue(adapter.locksHeld)
        assertEquals(0, adapter.fgReleases)
        assertNull(controller.micError.value)

        // Reconnect exhausted -> cleanly stops mic and releases FGS + locks
        adapter.autoRecovering = false
        adapter.state = ConnectionState.Error("Reconnect stopped (5/5 attempts)")
        controller.onConnectionStateChanged(adapter.state)
        assertFalse(controller.isMicEnabled.value)
        assertFalse(controller.isMicStreaming.value)
        assertFalse(controller.shouldHoldMicrophoneForeground)
        assertFalse(adapter.locksHeld)
        assertEquals(1, adapter.fgReleases)
    }

    @Test
    fun rapidDisableBeforeForegroundReadyPreventsStrayAudioStart() {
        val adapter = FakeMicAdapter()
        val controller = MicSessionController(adapter)

        assertTrue(controller.setMicEnabled(true))
        assertFalse(controller.setMicEnabled(false))

        // Delayed onStartCommand callback arrives after user already turned mic off
        controller.onForegroundServiceMicReady()
        assertFalse(controller.isMicEnabled.value)
        assertFalse(controller.isMicStreaming.value)
        assertNull(adapter.streamingHost)
        assertFalse(adapter.locksHeld)
    }

    @Test
    fun stopMicFromNotificationAndUserDisconnectCleanUpCompletely() {
        val adapter = FakeMicAdapter()
        val controller = MicSessionController(adapter)

        controller.setMicEnabled(true)
        controller.onForegroundServiceMicReady()
        controller.onStreamLevelChanged(0.75f)
        assertEquals(0.75f, controller.micLevel.value, 0.001f)

        controller.stopMicFromService()
        assertFalse(controller.isMicEnabled.value)
        assertFalse(controller.isMicStreaming.value)
        assertFalse(controller.shouldHoldMicrophoneForeground)
        assertFalse(adapter.locksHeld)
        assertEquals(0f, controller.micLevel.value, 0.001f)

        // Re-enable and disconnect by user
        controller.setMicEnabled(true)
        controller.onForegroundServiceMicReady()
        assertTrue(controller.isMicStreaming.value)

        controller.onUserDisconnect()
        assertFalse(controller.isMicEnabled.value)
        assertFalse(controller.isMicStreaming.value)
        assertFalse(adapter.locksHeld)
        assertNull(adapter.streamingHost)
    }

    companion object {
        private fun lanConnected(host: String = "192.168.0.105", micPort: Int = 8768) =
            ConnectionState.Connected(
                pcName = "DESKTOP-PC",
                target = "Wi-Fi ($host:8765)",
                host = host,
                micPort = micPort,
                micDevice = "CABLE Input",
                viaRelay = false,
                transportMode = TransportMode.LAN,
            )

        private fun vpsConnected() =
            ConnectionState.Connected(
                pcName = "DESKTOP-PC",
                target = "Internet (VPS)",
                host = "",
                viaRelay = true,
                transportMode = TransportMode.VPS_RELAY,
            )
    }
}
