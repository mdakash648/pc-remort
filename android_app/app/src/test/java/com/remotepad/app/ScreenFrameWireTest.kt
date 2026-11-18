package com.remotepad.app

import com.remotepad.app.network.P2PScreenReassembler
import com.remotepad.app.network.P2PWire
import com.remotepad.app.network.Protocol
import com.remotepad.app.network.ScreenFrameWire
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ScreenFrameWireTest {

    private fun buildRscrFrame(
        seq: Int,
        monitorIdx: Int,
        monitorCount: Int,
        width: Int,
        height: Int,
        cursorX: Int,
        cursorY: Int,
        hasCursor: Boolean,
        payload: ByteArray,
    ): ByteArray {
        val buf = ByteBuffer.allocate(ScreenFrameWire.HEADER_SIZE + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.put('R'.code.toByte())
        buf.put('S'.code.toByte())
        buf.put('C'.code.toByte())
        buf.put('R'.code.toByte())
        val flags = (0x02 or if (hasCursor) 0x04 else 0x00).toByte()
        buf.put(flags)
        buf.put(monitorIdx.toByte())
        buf.put(monitorCount.toByte())
        buf.put(0)
        buf.putInt(seq)
        buf.putShort(width.toShort())
        buf.putShort(height.toShort())
        buf.putShort(cursorX.toShort())
        buf.putShort(cursorY.toShort())
        buf.put(payload)
        return buf.array()
    }

    private fun buildP2pChunk(
        authTag: ByteArray,
        frameSeq: Int,
        chunkIdx: Int,
        totalChunks: Int,
        payload: ByteArray,
    ): ByteArray {
        val buf = ByteBuffer.allocate(P2PWire.SCREEN_CHUNK_HEADER_SIZE + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.put('R'.code.toByte())
        buf.put('P'.code.toByte())
        buf.put('2'.code.toByte())
        buf.put('P'.code.toByte())
        buf.put(P2PWire.PKT_SCREEN_CHUNK)
        buf.put(0)
        buf.put(0)
        buf.put(0)
        buf.put(authTag, 0, 8)
        buf.putInt(frameSeq)
        buf.putShort(chunkIdx.toShort())
        buf.putShort(totalChunks.toShort())
        buf.put(payload)
        return buf.array()
    }

    @Test
    fun decodesValidRscrFrameHeaderAndPayload() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22, 0xFF.toByte(), 0xD9.toByte())
        val raw = buildRscrFrame(
            seq = 42,
            monitorIdx = 1,
            monitorCount = 2,
            width = 1280,
            height = 720,
            cursorX = 32768,
            cursorY = 16384,
            hasCursor = true,
            payload = jpeg,
        )

        val decoded = ScreenFrameWire.decode(raw, raw.size, fromP2p = false)
        assertNotNull(decoded)
        assertEquals(42, decoded!!.seq)
        assertEquals(1, decoded.monitorIndex)
        assertEquals(2, decoded.monitorCount)
        assertEquals(1280, decoded.width)
        assertEquals(720, decoded.height)
        assertTrue(decoded.hasCursor)
        assertEquals(0.5f, decoded.cursorXNorm, 0.001f)
        assertEquals(0.25f, decoded.cursorYNorm, 0.001f)
        assertArrayEquals(jpeg, decoded.jpegBytes)
    }

    @Test
    fun reassemblesOutOfOrderP2pScreenChunksAndDropsStaleFrames() {
        val tag = P2PWire.deriveAuthTag("rpad-test-token", "sid-999999")
        val jpeg = ByteArray(2500) { idx -> (idx % 251).toByte() }
        val wire = buildRscrFrame(
            seq = 10,
            monitorIdx = 0,
            monitorCount = 1,
            width = 960,
            height = 540,
            cursorX = 1000,
            cursorY = 2000,
            hasCursor = false,
            payload = jpeg,
        )

        val chunkSize = 1180
        val chunks = wire.asList().chunked(chunkSize).map { it.toByteArray() }
        assertEquals(3, chunks.size)

        val reassembler = P2PScreenReassembler()

        // Deliver out of order: chunk 2, chunk 0, chunk 1
        val pkt2 = P2PWire.unpackScreenChunk(buildP2pChunk(tag, 10, 2, 3, chunks[2]))!!
        val pkt0 = P2PWire.unpackScreenChunk(buildP2pChunk(tag, 10, 0, 3, chunks[0]))!!
        val pkt1 = P2PWire.unpackScreenChunk(buildP2pChunk(tag, 10, 1, 3, chunks[1]))!!

        assertNull(reassembler.onChunk(pkt2.frameSeq, pkt2.chunkIdx, pkt2.totalChunks, pkt2.payload))
        assertNull(reassembler.onChunk(pkt0.frameSeq, pkt0.chunkIdx, pkt0.totalChunks, pkt0.payload))
        val completed = reassembler.onChunk(pkt1.frameSeq, pkt1.chunkIdx, pkt1.totalChunks, pkt1.payload)

        assertNotNull(completed)
        assertTrue(completed!!.fromP2p)
        assertEquals(10, completed.seq)
        assertEquals(960, completed.width)
        assertEquals(540, completed.height)
        assertArrayEquals(jpeg, completed.jpegBytes)

        // Older frame chunk (seq=9) must be ignored after seq=10 completed
        assertNull(reassembler.onChunk(9, 0, 1, wire))
    }

    @Test
    fun acceptsRestartedP2pSequenceAfterResetOrIdleGraceWindow() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x33, 0x44, 0xFF.toByte(), 0xD9.toByte())
        val frame100 = buildRscrFrame(
            seq = 100,
            monitorIdx = 0,
            monitorCount = 1,
            width = 960,
            height = 540,
            cursorX = 0,
            cursorY = 0,
            hasCursor = false,
            payload = jpeg,
        )
        val frame1 = buildRscrFrame(
            seq = 1,
            monitorIdx = 0,
            monitorCount = 1,
            width = 960,
            height = 540,
            cursorX = 0,
            cursorY = 0,
            hasCursor = false,
            payload = jpeg,
        )

        var fakeNowMs = 10_000L
        val reassembler = P2PScreenReassembler(
            streamRestartGraceMs = 1_500L,
            clockMs = { fakeNowMs },
        )

        assertNotNull(reassembler.onChunk(100, 0, 1, frame100))

        // Immediate older packet (seq=1 within grace window) is ignored
        fakeNowMs += 200L
        assertNull(reassembler.onChunk(1, 0, 1, frame1))

        // After reset(), seq=1 is accepted immediately
        reassembler.reset()
        val afterReset = reassembler.onChunk(1, 0, 1, frame1)
        assertNotNull(afterReset)
        assertEquals(1, afterReset!!.seq)

        // Advance past streamRestartGraceMs without explicit reset -> restarted seq=1 still accepted
        assertNotNull(reassembler.onChunk(100, 0, 1, frame100))
        fakeNowMs += 2_000L
        val afterGrace = reassembler.onChunk(1, 0, 1, frame1)
        assertNotNull(afterGrace)
        assertEquals(1, afterGrace!!.seq)
    }

    @Test
    fun encodesScreenAndMoveAbsProtocolMessages() {
        val absJson = Protocol.encodeMoveAbs(0.5f, 0.25f, monitor = 1)
        assertTrue(absJson.contains("\"t\":\"move_abs\""))
        assertTrue(absJson.contains("\"monitor\":1"))

        val startJson = Protocol.encodeScreenStart(
            quality = "hd",
            fps = 24,
            maxWidth = 1280,
            monitor = 0,
            preferP2p = true,
            hideCursor = true,
        )
        assertTrue(startJson.contains("\"t\":\"screen_start\""))
        assertTrue(startJson.contains("\"quality\":\"hd\""))
        assertTrue(startJson.contains("\"hide_cursor\":true"))

        val cfgJson = Protocol.encodeScreenConfig(quality = "fast", preferP2p = false, hideCursor = true)
        assertTrue(cfgJson.contains("\"t\":\"screen_cfg\""))
        assertTrue(cfgJson.contains("\"prefer_p2p\":false"))
        assertTrue(cfgJson.contains("\"hide_cursor\":true"))

        val ackJson = Protocol.encodeScreenAck(77)
        assertTrue(ackJson.contains("\"t\":\"screen_ack\""))
        assertTrue(ackJson.contains("\"seq\":77"))

        assertEquals("{\"t\":\"screen_stop\"}", Protocol.encodeScreenStop())
    }
}
