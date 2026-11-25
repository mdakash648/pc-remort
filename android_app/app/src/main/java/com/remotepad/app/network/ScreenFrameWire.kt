package com.remotepad.app.network

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ScreenFramePacket(
    val seq: Int,
    val monitorIndex: Int,
    val monitorCount: Int,
    val width: Int,
    val height: Int,
    val cursorXNorm: Float,
    val cursorYNorm: Float,
    val hasCursor: Boolean,
    val jpegBytes: ByteArray,
    val fromP2p: Boolean,
    val roiNorm: FloatArray? = null,
)

/**
 * Pure-JVM decoder for the 20-byte standard or 28-byte ROI binary RSCR screen frame header + JPEG payload.
 * Byte-for-byte compatible with pc_agent/features/screen_capture.py.
 */
object ScreenFrameWire {
    const val HEADER_SIZE = 20
    const val ROI_HEADER_SIZE = 28
    private val MAGIC = byteArrayOf(
        'R'.code.toByte(),
        'S'.code.toByte(),
        'C'.code.toByte(),
        'R'.code.toByte(),
    )
    private const val FLAG_HAS_CURSOR = 0x04
    private const val FLAG_ROI = 0x08

    fun isScreenFrame(data: ByteArray, length: Int = data.size): Boolean {
        if (length < HEADER_SIZE || data.size < length) return false
        return data[0] == MAGIC[0] &&
            data[1] == MAGIC[1] &&
            data[2] == MAGIC[2] &&
            data[3] == MAGIC[3]
    }

    fun decode(data: ByteArray, length: Int = data.size, fromP2p: Boolean = false): ScreenFramePacket? {
        if (!isScreenFrame(data, length)) return null
        val flags = data[4].toInt() and 0xFF
        val hasRoi = (flags and FLAG_ROI) != 0
        val headerSize = if (hasRoi) ROI_HEADER_SIZE else HEADER_SIZE
        if (length < headerSize) return null
        val payloadLen = length - headerSize
        if (payloadLen <= 0) return null

        val buf = ByteBuffer.wrap(data, 0, headerSize).order(ByteOrder.BIG_ENDIAN)
        val _flags = buf.get(4).toInt() and 0xFF
        val monitorIndex = buf.get(5).toInt() and 0xFF
        val monitorCount = (buf.get(6).toInt() and 0xFF).coerceAtLeast(1)
        val seq = buf.getInt(8)
        val width = buf.getShort(12).toInt() and 0xFFFF
        val height = buf.getShort(14).toInt() and 0xFFFF
        val cxRaw = buf.getShort(16).toInt() and 0xFFFF
        val cyRaw = buf.getShort(18).toInt() and 0xFFFF
        if (width == 0 || height == 0) return null

        val roiNorm = if (hasRoi) {
            val rx = (buf.getShort(20).toInt() and 0xFFFF) / 65535f
            val ry = (buf.getShort(22).toInt() and 0xFFFF) / 65535f
            val rw = (buf.getShort(24).toInt() and 0xFFFF) / 65535f
            val rh = (buf.getShort(26).toInt() and 0xFFFF) / 65535f
            floatArrayOf(rx, ry, rw, rh)
        } else {
            null
        }

        val jpeg = ByteArray(payloadLen)
        System.arraycopy(data, headerSize, jpeg, 0, payloadLen)

        return ScreenFramePacket(
            seq = seq,
            monitorIndex = monitorIndex,
            monitorCount = monitorCount,
            width = width,
            height = height,
            cursorXNorm = (cxRaw / 65535f).coerceIn(0f, 1f),
            cursorYNorm = (cyRaw / 65535f).coerceIn(0f, 1f),
            hasCursor = (flags and FLAG_HAS_CURSOR) != 0,
            jpegBytes = jpeg,
            fromP2p = fromP2p,
            roiNorm = roiNorm,
        )
    }
}

/**
 * Bounded-memory, newest-frame-wins reassembler for P2P UDP PKT_SCREEN_CHUNK datagrams.
 */
class P2PScreenReassembler(
    private val maxConcurrentFrames: Int = 3,
    private val streamRestartGraceMs: Long = 1_500L,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private class PendingFrame(
        val totalChunks: Int,
        val chunks: Array<ByteArray?> = arrayOfNulls(totalChunks),
        var receivedCount: Int = 0,
        var totalBytes: Int = 0,
    )

    private val lock = Any()
    private val pending = LinkedHashMap<Int, PendingFrame>()
    private var lastCompletedSeq: Int = -1
    private var lastCompletedTimeMs: Long = 0L
    private var hasCompleted: Boolean = false

    fun reset() {
        synchronized(lock) {
            pending.clear()
            lastCompletedSeq = -1
            lastCompletedTimeMs = 0L
            hasCompleted = false
        }
    }

    fun onChunk(
        frameSeq: Int,
        chunkIdx: Int,
        totalChunks: Int,
        payload: ByteArray,
    ): ScreenFramePacket? {
        if (totalChunks < 1 || totalChunks > 320 || chunkIdx !in 0 until totalChunks) {
            return null
        }

        val nowMs = clockMs()
        val assembled: ByteArray = synchronized(lock) {
            if (hasCompleted && !isNewerSeq(frameSeq, lastCompletedSeq)) {
                // If PC restarted the screen stream (seq restarted from 1) after a pause/re-entry,
                // accept the new sequence cleanly instead of dropping every P2P frame forever.
                if ((nowMs - lastCompletedTimeMs) >= streamRestartGraceMs) {
                    pending.clear()
                    hasCompleted = false
                    lastCompletedSeq = -1
                } else {
                    return null
                }
            }

            var entry = pending[frameSeq]
            if (entry == null) {
                // Evict oldest incomplete frames when newer frames arrive
                while (pending.size >= maxConcurrentFrames) {
                    val oldestKey = pending.keys.firstOrNull() ?: break
                    pending.remove(oldestKey)
                }
                entry = PendingFrame(totalChunks)
                pending[frameSeq] = entry
            } else if (entry.totalChunks != totalChunks) {
                return null
            }

            if (entry.chunks[chunkIdx] == null) {
                entry.chunks[chunkIdx] = payload
                entry.receivedCount += 1
                entry.totalBytes += payload.size
            }

            if (entry.receivedCount < entry.totalChunks) {
                return null
            }

            val out = ByteArray(entry.totalBytes)
            var offset = 0
            for (piece in entry.chunks) {
                val bytes = piece ?: return null
                System.arraycopy(bytes, 0, out, offset, bytes.size)
                offset += bytes.size
            }

            lastCompletedSeq = frameSeq
            lastCompletedTimeMs = nowMs
            hasCompleted = true
            pending.keys.removeAll { key -> !isNewerSeq(key, frameSeq) }
            out
        }

        return ScreenFrameWire.decode(assembled, assembled.size, fromP2p = true)
    }

    private fun isNewerSeq(candidate: Int, reference: Int): Boolean {
        val diff = candidate - reference
        return diff > 0
    }
}
