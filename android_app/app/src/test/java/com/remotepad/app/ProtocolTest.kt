package com.remotepad.app

import com.remotepad.app.data.model.ButtonState
import com.remotepad.app.data.model.IncomingMessage
import com.remotepad.app.data.model.MouseButton
import com.remotepad.app.data.model.P2PEndpoint
import com.remotepad.app.input.MicStreamer
import com.remotepad.app.network.DiscoveredPc
import com.remotepad.app.network.DiscoveryProtocol
import com.remotepad.app.network.P2PWire
import com.remotepad.app.network.Protocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProtocolTest {

    @Test
    fun encodeMessagesMatchCommonOverviewSpec() {
        assertEquals("""{"t":"move","dx":5.25,"dy":-3.5}""", Protocol.encodeMove(5.251f, -3.499f))
        assertEquals("""{"t":"move","dx":0.0,"dy":0.0}""", Protocol.encodeMove(-0.001f, Float.NaN))
        assertEquals(
            """{"t":"btn","b":"left","s":"down"}""",
            Protocol.encodeButton(MouseButton.LEFT, ButtonState.DOWN),
        )
        assertEquals(
            """{"t":"click","b":"right","n":2}""",
            Protocol.encodeClick(MouseButton.RIGHT, 2),
        )
        assertEquals("""{"t":"scroll","dx":0.0,"dy":-1.5}""", Protocol.encodeScroll(0f, -1.5f))
        assertEquals("""{"t":"key","keys":["ctrl","c"]}""", Protocol.encodeKeyCombo(listOf("ctrl", "c")))
        assertEquals(
            """{"t":"keyevt","k":"shift","s":"down"}""",
            Protocol.encodeKeyEvent("shift", ButtonState.DOWN),
        )
        assertEquals("""{"t":"text","v":"আমার সোনার বাংলা"}""", Protocol.encodeText("আমার সোনার বাংলা"))
        assertEquals("""{"t":"paste","v":"Line 1\n\nLine 2"}""", Protocol.encodePaste("Line 1\n\nLine 2"))
        assertEquals("""{"t":"gesture","g":"task_view"}""", Protocol.encodeGesture("task_view"))
        assertEquals("""{"t":"media","a":"vol_up"}""", Protocol.encodeMedia("vol_up"))
        assertEquals("""{"t":"power","a":"lock"}""", Protocol.encodePower("lock"))
        assertEquals("""{"t":"vol_set","v":0.75}""", Protocol.encodeVolumeSet(0.75f))
        assertEquals("""{"t":"vol_sync","enable":true}""", Protocol.encodeVolumeSync(true))
        assertEquals("""{"t":"ping"}""", Protocol.encodePing())
        val hello = Protocol.encodeHello(deviceName = "Pixel")
        assertTrue(hello.contains(""""t":"hello""""))
        assertTrue(hello.contains(""""v":1"""))
    }

    @Test
    fun decodeIncomingHandlesKnownUnknownAndInvalid() {
        val ack = Protocol.decodeIncoming(
            """{"t":"hello_ack","v":1,"ok":true,"name":"DESKTOP-1","file_port":8767,"mic_port":8768,"mic_device":"CABLE Input"}"""
        )
        assertEquals(
            IncomingMessage.HelloAck(
                version = 1,
                ok = true,
                pcName = "DESKTOP-1",
                filePort = 8767,
                micPort = 8768,
                micDevice = "CABLE Input",
            ),
            ack,
        )

        val ackLegacy = Protocol.decodeIncoming("""{"t":"hello_ack","v":1,"ok":true,"name":"OLD-PC"}""")
        assertEquals(IncomingMessage.HelloAck(version = 1, ok = true, pcName = "OLD-PC", filePort = 8767), ackLegacy)

        assertEquals(
            IncomingMessage.VolumeState(level = 0.42f, muted = true),
            Protocol.decodeIncoming("""{"t":"vol_state","v":0.42,"muted":true}"""),
        )
        assertEquals(
            IncomingMessage.RelayReady(pcName = "MdAkash"),
            Protocol.decodeIncoming("""{"t":"relay_ready","ok":true,"role":"controller","name":"MdAkash"}"""),
        )
        assertEquals(
            IncomingMessage.RelayError(code = "host_offline", message = "PC is offline"),
            Protocol.decodeIncoming("""{"t":"relay_error","code":"host_offline","message":"PC is offline"}"""),
        )
        assertEquals(IncomingMessage.Pong, Protocol.decodeIncoming("""{"t":"pong"}"""))
        assertEquals(
            IncomingMessage.Unknown("future_type"),
            Protocol.decodeIncoming("""{"t":"future_type","x":1}"""),
        )
        assertNull(Protocol.decodeIncoming("{bad_json"))
    }

    @Test
    fun parseDiscoveryAnnounceHandlesValidAndInvalidPayloads() {
        val pc = DiscoveryProtocol.parseAnnounce(
            payload = """{"t":"announce","v":1,"name":"MY-DESKTOP","port":8765}""",
            senderHost = "192.168.1.42",
            nowMs = 1234L,
        )
        assertEquals(
            DiscoveredPc(name = "MY-DESKTOP", host = "192.168.1.42", port = 8765, lastSeenMs = 1234L),
            pc,
        )

        assertNull(DiscoveryProtocol.parseAnnounce("""{"t":"discover","v":1}""", "192.168.1.42", 1L))
        assertNull(DiscoveryProtocol.parseAnnounce("""{"t":"announce","port":99999}""", "192.168.1.42", 1L))
        assertNull(DiscoveryProtocol.parseAnnounce("{invalid", "192.168.1.42", 1L))
    }

    @Test
    fun packMicPacketPreservesRmicHeaderAndLittleEndianPcm() {
        val buf = ByteArray(MicStreamer.HEADER_BYTES + 4)
        val samples = shortArrayOf(0x0102, -1)
        val len = MicStreamer.packMicPacket(buf, seq = 42, samples = samples, sampleCount = 2)
        assertEquals(12, len)
        assertEquals("RMIC", String(buf, 0, 4, Charsets.US_ASCII))
        assertEquals(0.toByte(), buf[4])
        assertEquals(0.toByte(), buf[5])
        assertEquals(0.toByte(), buf[6])
        assertEquals(42.toByte(), buf[7])
        // 0x0102 little-endian -> 0x02, 0x01
        assertEquals(0x02.toByte(), buf[8])
        assertEquals(0x01.toByte(), buf[9])
        // -1 little-endian -> 0xFF, 0xFF
        assertEquals(0xFF.toByte(), buf[10])
        assertEquals(0xFF.toByte(), buf[11])
    }

    @Test
    fun p2pSignalingAndBinaryPacketsMatchPythonWireFormat() {
        val offer = Protocol.encodeP2POffer(
            sid = "abc12345",
            udpPort = 8771,
            endpoints = listOf(P2PEndpoint("103.1.2.3", 44123)),
        )
        assertTrue(offer.contains(""""t":"p2p_offer""""))
        assertTrue(offer.contains(""""udp_port":8771"""))
        assertTrue(offer.contains(""""ip":"103.1.2.3""""))

        val ready = Protocol.decodeIncoming(
            """{"t":"relay_ready","ok":true,"role":"controller","name":"MdAkash","udpPort":8771}"""
        )
        assertEquals(IncomingMessage.RelayReady(pcName = "MdAkash", udpPort = 8771), ready)

        val answer = Protocol.decodeIncoming(
            """{"t":"p2p_answer","sid":"abc12345","endpoints":[{"ip":"103.1.2.4","port":51234},{"ip":"192.168.0.10","port":51234}]}"""
        )
        assertEquals(
            IncomingMessage.P2PAnswer(
                sid = "abc12345",
                endpoints = listOf(
                    P2PEndpoint("103.1.2.4", 51234),
                    P2PEndpoint("192.168.0.10", 51234),
                ),
            ),
            answer,
        )

        val tag = P2PWire.deriveAuthTag("rpad-7f4c9a2e8b1d6305", "sid001")
        assertEquals(8, tag.size)
        assertTrue(P2PWire.constantTimeEquals(tag, P2PWire.deriveAuthTag("rpad-7f4c9a2e8b1d6305", "sid001")))
        assertFalse(P2PWire.constantTimeEquals(tag, P2PWire.deriveAuthTag("wrong-token", "sid001")))

        val moveBytes = P2PWire.packDeltaPacket(P2PWire.PKT_MOVE, tag, seq = 99, dx = 12.5f, dy = -4.25f)
        assertEquals(P2PWire.PACKET_SIZE, moveBytes.size)
        val unpacked = P2PWire.unpackPacket(moveBytes)!!
        assertEquals(P2PWire.PKT_MOVE, unpacked.type)
        assertArrayEquals(tag, unpacked.authTag)
        assertEquals(99, unpacked.seq)
        val payloadBuf = ByteBuffer.wrap(unpacked.payload8).order(ByteOrder.BIG_ENDIAN)
        assertEquals(12.5f, payloadBuf.float, 0.0001f)
        assertEquals(-4.25f, payloadBuf.float, 0.0001f)

        val punchBytes = P2PWire.packControlPacket(P2PWire.PKT_PUNCH, tag, seq = 7, timestampMs = 1700000123456L)
        val unpackedPunch = P2PWire.unpackPacket(punchBytes)!!
        assertEquals(P2PWire.PKT_PUNCH, unpackedPunch.type)
        assertEquals(1700000123456L, P2PWire.readTimestamp(unpackedPunch.payload8))

        assertEquals("182.255.88.108" to 8771, P2PWire.parseRelayHostAndUdpPort("ws://182.255.88.108:8770"))
        assertEquals("182.255.88.108" to 9999, P2PWire.parseRelayHostAndUdpPort("ws://182.255.88.108:8770", 9999))

        // Variable-length P2P file transfer packets
        val reqBytes = P2PWire.packFileJson(P2PWire.PKT_FILE_REQ, tag, xferId = 7, jsonBody = """{"op":"info"}""")
        val uReq = P2PWire.unpackFilePacket(reqBytes)!!
        assertEquals(P2PWire.PKT_FILE_REQ, uReq.type)
        assertEquals(7, uReq.xferId)
        assertEquals("""{"op":"info"}""", String(uReq.body, Charsets.UTF_8))

        val payload = byteArrayOf(10, 20, 30, 40)
        val dataBytes = P2PWire.packFileData(tag, xferId = 7, chunkIdx = 15, payload = payload, isEof = true)
        val uData = P2PWire.unpackFilePacket(dataBytes)!!
        assertEquals(P2PWire.PKT_FILE_DATA, uData.type)
        assertEquals(P2PWire.FLAG_EOF, uData.flags)
        assertEquals(7, uData.xferId)
        assertEquals(15, ByteBuffer.wrap(uData.body, 0, 4).order(ByteOrder.BIG_ENDIAN).int)
        assertArrayEquals(payload, uData.body.copyOfRange(4, uData.body.size))

        val ackBytes = P2PWire.packFileAck(tag, xferId = 7, baseIdx = 12, sackMask = 0b10101L)
        val uAck = P2PWire.unpackFilePacket(ackBytes)!!
        assertEquals(P2PWire.PKT_FILE_ACK, uAck.type)
        val ackBuf = ByteBuffer.wrap(uAck.body).order(ByteOrder.BIG_ENDIAN)
        assertEquals(12, ackBuf.int)
        assertEquals(0b10101L, ackBuf.long)
    }
}
