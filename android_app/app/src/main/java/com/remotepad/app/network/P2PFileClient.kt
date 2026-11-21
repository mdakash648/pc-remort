package com.remotepad.app.network

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reliable Direct P2P UDP File Transfer client (windowed 64-bit Selective-ACK).
 * Streams directory browsing, downloads (PC -> Phone), and uploads (Phone -> PC)
 * directly over the punched P2PUdpClient socket with ZERO data touching the VPS relay.
 */
class P2PFileClient(
    private val httpHelper: FileTransferClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun fetchInfo(p2p: P2PUdpClient, fallbackName: String): PcFileInfo {
        val reqJson = buildJsonObject { put("op", "info") }.toString()
        val out = ByteArrayOutputStream(4096)
        receiveStream(
            p2p = p2p,
            requestJson = reqJson,
            cancelToken = AtomicBoolean(false),
            output = out,
            onProgress = { _, _ -> },
        )
        return httpHelper.parseInfoJson(out.toString(Charsets.UTF_8.name()), fallbackName)
    }

    fun listDirectory(p2p: P2PUdpClient, path: String?): PcDirectoryListing {
        val reqJson = buildJsonObject {
            put("op", "list")
            if (!path.isNullOrBlank()) put("path", path)
        }.toString()
        val out = ByteArrayOutputStream(8192)
        receiveStream(
            p2p = p2p,
            requestJson = reqJson,
            cancelToken = AtomicBoolean(false),
            output = out,
            onProgress = { _, _ -> },
        )
        return httpHelper.parseListJson(out.toString(Charsets.UTF_8.name()))
    }

    fun downloadFile(
        context: Context,
        p2p: P2PUdpClient,
        remotePath: String,
        fileName: String,
        expectedSize: Long,
        cancelToken: AtomicBoolean,
        onProgress: (transferred: Long, total: Long) -> Unit,
    ): DownloadedFileResult {
        val reqJson = buildJsonObject {
            put("op", "download")
            put("path", remotePath)
        }.toString()

        return httpHelper.saveDownloadWithWriter(
            context = context,
            fileName = fileName,
            expectedSize = expectedSize,
            onProgress = onProgress,
        ) { outStream ->
            receiveStream(
                p2p = p2p,
                requestJson = reqJson,
                cancelToken = cancelToken,
                output = outStream,
                onProgress = onProgress,
            )
        }
    }

    fun uploadFile(
        context: Context,
        p2p: P2PUdpClient,
        targetDir: String?,
        uri: Uri,
        meta: LocalFileMeta,
        cancelToken: AtomicBoolean,
        onProgress: (transferred: Long, total: Long) -> Unit,
    ): UploadedFileResult {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Unable to open selected file")
        return input.use { stream ->
            uploadStream(
                p2p = p2p,
                targetDir = targetDir,
                fileName = meta.displayName,
                expectedSize = meta.size,
                cancelToken = cancelToken,
                input = stream,
                onProgress = onProgress,
            )
        }
    }

    private fun receiveStream(
        p2p: P2PUdpClient,
        requestJson: String,
        cancelToken: AtomicBoolean,
        output: OutputStream,
        onProgress: (transferred: Long, total: Long) -> Unit,
    ): Long {
        val authTag = p2p.currentAuthTag() ?: p2p.ensureAuthTag()
        val xferId = p2p.allocateTransferId()
        val rxQueue = LinkedBlockingQueue<UnpackedFilePacket>()
        p2p.registerFileListener(xferId) { pkt -> rxQueue.offer(pkt) }

        val reqPacket = P2PWire.packFileJson(P2PWire.PKT_FILE_REQ, authTag, xferId, requestJson)

        try {
            var totalBytes = -1L
            var respReceived = false
            var baseIdx = 0
            var eofIdx = -1
            var written = 0L
            val ooo = HashMap<Int, ByteArray>()

            var inOrderSinceAck = 0
            var lastAckMs = 0L
            var lastProgressMs = 0L
            var lastReqSendMs = 0L
            var lastRxMs = SystemClock.elapsedRealtime()

            fun sendAckNow() {
                var mask = 0L
                for (k in ooo.keys) {
                    val bit = k - baseIdx - 1
                    if (bit in 0..63) {
                        mask = mask or (1L shl bit)
                    }
                }
                inOrderSinceAck = 0
                lastAckMs = SystemClock.elapsedRealtime()
                p2p.sendRawFilePacket(P2PWire.packFileAck(authTag, xferId, baseIdx, mask))
            }

            // Send initial request immediately
            if (!p2p.sendRawFilePacket(reqPacket)) {
                throw IOException("Cannot send file request to PC")
            }
            lastReqSendMs = SystemClock.elapsedRealtime()

            while (true) {
                if (cancelToken.get()) {
                    p2p.sendRawFilePacket(P2PWire.packFileAbort(authTag, xferId, "Cancelled"))
                    throw IOException("Cancelled")
                }
                if (!p2p.canSendFilePackets) {
                    throw IOException("File transfer connection lost")
                }

                val now = SystemClock.elapsedRealtime()
                if (!respReceived) {
                    if (now - lastRxMs > REQ_TIMEOUT_MS) {
                        throw IOException("PC did not respond over Direct P2P")
                    }
                    if (now - lastReqSendMs >= REQ_RETRY_MS) {
                        p2p.sendRawFilePacket(reqPacket)
                        lastReqSendMs = now
                    }
                } else if (now - lastRxMs > STALL_TIMEOUT_MS) {
                    p2p.sendRawFilePacket(P2PWire.packFileAbort(authTag, xferId, "Timeout"))
                    throw IOException("P2P transfer timed out")
                }

                val pkt = rxQueue.poll(25, TimeUnit.MILLISECONDS)
                if (pkt == null) {
                    if (respReceived && (inOrderSinceAck > 0 || ooo.isNotEmpty()) &&
                        (SystemClock.elapsedRealtime() - lastAckMs) >= ACK_INTERVAL_MS
                    ) {
                        sendAckNow()
                    }
                    continue
                }

                lastRxMs = SystemClock.elapsedRealtime()

                when (pkt.type) {
                    P2PWire.PKT_FILE_ABORT -> {
                        val reason = String(pkt.body, Charsets.UTF_8).ifBlank { "Transfer aborted" }
                        throw IOException(reason)
                    }
                    P2PWire.PKT_FILE_RESP -> {
                        val obj = json.parseToJsonElement(String(pkt.body, Charsets.UTF_8)).jsonObject
                        val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: false
                        if (!ok) {
                            val err = obj["error"]?.jsonPrimitive?.contentOrNull ?: "Request failed"
                            throw IOException(err)
                        }
                        totalBytes = obj["size"]?.jsonPrimitive?.longOrNull ?: totalBytes
                        respReceived = true
                    }
                    P2PWire.PKT_FILE_DATA -> {
                        if (pkt.body.size < 4) continue
                        respReceived = true
                        val chunkIdx = ByteBuffer.wrap(pkt.body, 0, 4).order(ByteOrder.BIG_ENDIAN).int
                        val payloadLen = pkt.body.size - 4
                        val isEof = (pkt.flags.toInt() and P2PWire.FLAG_EOF.toInt()) != 0
                        if (isEof) {
                            eofIdx = chunkIdx
                        }

                        var needImmediateAck = false
                        if (chunkIdx == baseIdx) {
                            if (payloadLen > 0) {
                                output.write(pkt.body, 4, payloadLen)
                                written += payloadLen
                            }
                            baseIdx++
                            inOrderSinceAck++
                            while (true) {
                                val nextBuf = ooo.remove(baseIdx) ?: break
                                if (nextBuf.isNotEmpty()) {
                                    output.write(nextBuf)
                                    written += nextBuf.size
                                }
                                baseIdx++
                                inOrderSinceAck++
                            }
                        } else if (chunkIdx > baseIdx && chunkIdx <= baseIdx + 64) {
                            if (!ooo.containsKey(chunkIdx)) {
                                ooo[chunkIdx] = pkt.body.copyOfRange(4, pkt.body.size)
                            }
                            needImmediateAck = true
                        } else {
                            // Duplicate of already received chunk -> ACK immediately so sender moves on
                            needImmediateAck = true
                        }

                        val nowProgress = SystemClock.elapsedRealtime()
                        if (nowProgress - lastProgressMs >= PROGRESS_REPORT_MS) {
                            lastProgressMs = nowProgress
                            onProgress(written, if (totalBytes >= 0L) totalBytes else written)
                        }

                        if (eofIdx >= 0 && baseIdx > eofIdx) {
                            // Send final ACK twice for reliability
                            sendAckNow()
                            sendAckNow()
                            onProgress(written, if (totalBytes >= 0L) totalBytes else written)
                            return written
                        }

                        if (needImmediateAck || inOrderSinceAck >= ACK_EVERY_N ||
                            (nowProgress - lastAckMs) >= ACK_INTERVAL_MS
                        ) {
                            sendAckNow()
                        }
                    }
                }
            }
        } finally {
            p2p.unregisterFileListener(xferId)
        }
    }

    private class InFlightChunk(
        val packet: ByteArray,
        var sentAtMs: Long,
        var rtoMs: Long,
    )

    private fun uploadStream(
        p2p: P2PUdpClient,
        targetDir: String?,
        fileName: String,
        expectedSize: Long,
        cancelToken: AtomicBoolean,
        input: InputStream,
        onProgress: (transferred: Long, total: Long) -> Unit,
    ): UploadedFileResult {
        val authTag = p2p.currentAuthTag() ?: p2p.ensureAuthTag()
        val xferId = p2p.allocateTransferId()
        val rxQueue = LinkedBlockingQueue<UnpackedFilePacket>()
        p2p.registerFileListener(xferId) { pkt -> rxQueue.offer(pkt) }

        val reqJson = buildJsonObject {
            put("op", "upload")
            put("name", fileName)
            if (!targetDir.isNullOrBlank()) put("dir", targetDir)
            if (expectedSize >= 0L) put("size", expectedSize)
        }.toString()
        val reqPacket = P2PWire.packFileJson(P2PWire.PKT_FILE_REQ, authTag, xferId, reqJson)

        try {
            // 1. Handshake: wait for PC PKT_FILE_RESP(ok=true)
            var chunkSize = P2PWire.FILE_CHUNK_SIZE
            var ready = false
            val handshakeStart = SystemClock.elapsedRealtime()
            var lastReqSendMs = 0L

            while (!ready) {
                if (cancelToken.get()) {
                    p2p.sendRawFilePacket(P2PWire.packFileAbort(authTag, xferId, "Cancelled"))
                    throw IOException("Cancelled")
                }
                if (!p2p.canSendFilePackets) throw IOException("File transfer connection lost")
                val now = SystemClock.elapsedRealtime()
                if (now - handshakeStart > REQ_TIMEOUT_MS) {
                    throw IOException("PC did not respond to upload request")
                }
                if (now - lastReqSendMs >= REQ_RETRY_MS) {
                    p2p.sendRawFilePacket(reqPacket)
                    lastReqSendMs = now
                }
                val pkt = rxQueue.poll(30, TimeUnit.MILLISECONDS) ?: continue
                if (pkt.type == P2PWire.PKT_FILE_ABORT) {
                    throw IOException(String(pkt.body, Charsets.UTF_8).ifBlank { "Upload aborted" })
                }
                if (pkt.type == P2PWire.PKT_FILE_RESP) {
                    val obj = json.parseToJsonElement(String(pkt.body, Charsets.UTF_8)).jsonObject
                    if (obj["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                        throw IOException(obj["error"]?.jsonPrimitive?.contentOrNull ?: "Upload rejected")
                    }
                    chunkSize = (obj["chunk_size"]?.jsonPrimitive?.intOrNull ?: P2PWire.FILE_CHUNK_SIZE)
                        .coerceIn(256, 1200)
                    ready = true
                }
            }

            // 2. Windowed SACK upload streaming directly from InputStream
            val readBuf = ByteArray(chunkSize)
            var nextChunk = readNextChunk(input, readBuf)
            var eofRead = false
            var totalChunks = Int.MAX_VALUE
            var lastChunkPacket: ByteArray? = null

            var baseIdx = 0
            var nextIdx = 0
            val acked = HashSet<Int>()
            val inFlight = LinkedHashMap<Int, InFlightChunk>()
            val chunkSizes = HashMap<Int, Int>()
            var ackedBytes = 0L
            var lastAckMs = SystemClock.elapsedRealtime()
            var lastProgressMs = 0L
            var doneResult: UploadedFileResult? = null
            var lastDonePokeMs = 0L

            while (doneResult == null) {
                if (cancelToken.get()) {
                    p2p.sendRawFilePacket(P2PWire.packFileAbort(authTag, xferId, "Cancelled"))
                    throw IOException("Cancelled")
                }
                if (!p2p.canSendFilePackets) throw IOException("File transfer connection lost")

                val now = SystemClock.elapsedRealtime()
                if (now - lastAckMs > STALL_TIMEOUT_MS) {
                    p2p.sendRawFilePacket(P2PWire.packFileAbort(authTag, xferId, "Timeout"))
                    throw IOException("P2P upload timed out")
                }

                // Fill window with fresh chunks from InputStream
                var sentBatch = 0
                while (!eofRead && nextIdx < baseIdx + UPLOAD_WINDOW_SIZE && sentBatch < 16) {
                    val currentData = nextChunk
                    val idx = nextIdx
                    if (currentData == null) {
                        // 0-byte file: emit a single empty EOF chunk (idx=0)
                        val pktBytes = P2PWire.packFileData(authTag, xferId, idx, ByteArray(0), 0, isEof = true)
                        inFlight[idx] = InFlightChunk(pktBytes, now, INITIAL_RTO_MS)
                        chunkSizes[idx] = 0
                        lastChunkPacket = pktBytes
                        p2p.sendRawFilePacket(pktBytes)
                        nextIdx++
                        totalChunks = nextIdx
                        eofRead = true
                        sentBatch++
                    } else {
                        val lookahead = readNextChunk(input, readBuf)
                        val isEof = (lookahead == null)
                        val pktBytes = P2PWire.packFileData(authTag, xferId, idx, currentData, currentData.size, isEof)
                        inFlight[idx] = InFlightChunk(pktBytes, now, INITIAL_RTO_MS)
                        chunkSizes[idx] = currentData.size
                        if (isEof) {
                            lastChunkPacket = pktBytes
                            totalChunks = idx + 1
                            eofRead = true
                        }
                        p2p.sendRawFilePacket(pktBytes)
                        nextChunk = lookahead
                        nextIdx++
                        sentBatch++
                    }
                }

                // Retransmit timed-out packets in flight
                val nowRetx = SystemClock.elapsedRealtime()
                var retxCount = 0
                for ((idx, entry) in inFlight.entries) {
                    if (idx < baseIdx || acked.contains(idx)) continue
                    if (nowRetx - entry.sentAtMs >= entry.rtoMs) {
                        entry.sentAtMs = nowRetx
                        entry.rtoMs = (entry.rtoMs * 3L / 2L).coerceAtMost(MAX_RTO_MS)
                        p2p.sendRawFilePacket(entry.packet)
                        retxCount++
                        if (retxCount >= 12) break
                    }
                }

                // If all chunks are ACKed but final RESP(done=true) hasn't arrived yet, poke PC
                if (eofRead && baseIdx >= totalChunks && lastChunkPacket != null) {
                    if (nowRetx - lastDonePokeMs >= 350L) {
                        p2p.sendRawFilePacket(lastChunkPacket)
                        lastDonePokeMs = nowRetx
                    }
                }

                // Drain incoming ACKs / final RESP
                val waitMs = if (sentBatch > 0 && !eofRead && nextIdx < baseIdx + UPLOAD_WINDOW_SIZE) 1L else 20L
                var pkt = rxQueue.poll(waitMs, TimeUnit.MILLISECONDS)
                while (pkt != null) {
                    lastAckMs = SystemClock.elapsedRealtime()
                    when (pkt.type) {
                        P2PWire.PKT_FILE_ABORT -> {
                            throw IOException(String(pkt.body, Charsets.UTF_8).ifBlank { "Upload aborted" })
                        }
                        P2PWire.PKT_FILE_RESP -> {
                            val obj = json.parseToJsonElement(String(pkt.body, Charsets.UTF_8)).jsonObject
                            if (obj["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                                throw IOException(obj["error"]?.jsonPrimitive?.contentOrNull ?: "Upload failed")
                            }
                            if (obj["done"]?.jsonPrimitive?.booleanOrNull == true) {
                                val savedName = obj["name"]?.jsonPrimitive?.contentOrNull ?: fileName
                                val savedPath = obj["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                val savedSize = obj["size"]?.jsonPrimitive?.longOrNull ?: ackedBytes
                                onProgress(savedSize, savedSize)
                                doneResult = UploadedFileResult(
                                    savedName = savedName,
                                    savedPath = savedPath,
                                    size = savedSize,
                                )
                                break
                            }
                        }
                        P2PWire.PKT_FILE_ACK -> {
                            if (pkt.body.size >= 12) {
                                val buf = ByteBuffer.wrap(pkt.body).order(ByteOrder.BIG_ENDIAN)
                                val ackBase = buf.int
                                val sackMask = buf.long
                                if (ackBase > baseIdx) {
                                    for (i in baseIdx until ackBase) {
                                        inFlight.remove(i)
                                        if (!acked.remove(i)) {
                                            ackedBytes += (chunkSizes.remove(i) ?: 0)
                                        }
                                    }
                                    baseIdx = ackBase
                                }
                                if (sackMask != 0L) {
                                    for (bit in 0 until 64) {
                                        if (((sackMask ushr bit) and 1L) != 0L) {
                                            val sIdx = ackBase + 1 + bit
                                            if (sIdx >= baseIdx && acked.add(sIdx)) {
                                                inFlight.remove(sIdx)
                                                ackedBytes += (chunkSizes.remove(sIdx) ?: 0)
                                            }
                                        }
                                    }
                                }
                                val nowProg = SystemClock.elapsedRealtime()
                                if (nowProg - lastProgressMs >= PROGRESS_REPORT_MS) {
                                    lastProgressMs = nowProg
                                    val total = if (expectedSize >= 0L) expectedSize else ackedBytes
                                    onProgress(ackedBytes, total)
                                }
                            }
                        }
                    }
                    pkt = rxQueue.poll()
                }
            }
            return doneResult
        } finally {
            p2p.unregisterFileListener(xferId)
        }
    }

    private fun readNextChunk(input: InputStream, scratch: ByteArray): ByteArray? {
        var filled = 0
        while (filled < scratch.size) {
            val r = input.read(scratch, filled, scratch.size - filled)
            if (r <= 0) break
            filled += r
        }
        return if (filled == 0) null else scratch.copyOf(filled)
    }

    companion object {
        private const val UPLOAD_WINDOW_SIZE = 48
        private const val INITIAL_RTO_MS = 180L
        private const val MAX_RTO_MS = 600L
        private const val REQ_RETRY_MS = 250L
        private const val REQ_TIMEOUT_MS = 4_500L
        private const val STALL_TIMEOUT_MS = 15_000L
        private const val ACK_EVERY_N = 8
        private const val ACK_INTERVAL_MS = 25L
        private const val PROGRESS_REPORT_MS = 80L
    }
}
