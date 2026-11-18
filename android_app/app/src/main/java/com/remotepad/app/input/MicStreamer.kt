package com.remotepad.app.input

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Low-latency phone microphone UDP streamer (48 kHz, 16-bit mono PCM, 10 ms frames).
 *
 * Packet layout (968 bytes, fits in a single unfragmented Wi-Fi UDP packet):
 * - Bytes 0..3 : ASCII magic "RMIC"
 * - Bytes 4..7 : big-endian uint32 sequence number
 * - Bytes 8..  : little-endian 16-bit signed mono PCM samples (480 samples = 960 bytes)
 */
class MicStreamer(
    private val onLevelChanged: (Float) -> Unit,
    private val onError: (String) -> Unit,
) {
    @Volatile
    private var running = false
    @Volatile
    private var activeHost: String? = null
    @Volatile
    private var activePort: Int = -1
    @Volatile
    private var activeRecorder: AudioRecord? = null
    private var workerThread: Thread? = null
    private val lock = Any()

    val isStreaming: Boolean
        get() = running

    fun isStreamingTo(host: String, port: Int): Boolean {
        val cleanHost = host.trim()
        return running && activeHost == cleanHost && activePort == port
    }

    @SuppressLint("MissingPermission")
    fun start(host: String, port: Int): Boolean {
        synchronized(lock) {
            val cleanHost = host.trim()
            if (cleanHost.isEmpty() || port !in 1..65535) {
                stopLocked()
                onError("Invalid PC address for microphone")
                return false
            }
            if (running && activeHost == cleanHost && activePort == port && workerThread?.isAlive == true) {
                return true
            }
            stopLocked()

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) {
                onError("Microphone format unsupported on this phone")
                return false
            }

            val bufferSize = maxOf(minBuf * 2, FRAME_BYTES * 8)
            val recorder = createAudioRecord(bufferSize)
            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { recorder?.release() }
                onError("Could not open phone microphone")
                return false
            }

            running = true
            activeHost = cleanHost
            activePort = port
            activeRecorder = recorder
            workerThread = thread(name = "remotepad-mic-streamer", isDaemon = true) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                streamLoop(recorder, bufferSize, cleanHost, port)
            }
            return true
        }
    }

    fun stop() {
        synchronized(lock) {
            stopLocked()
        }
    }

    private fun stopLocked() {
        running = false
        activeHost = null
        activePort = -1
        val t = workerThread
        workerThread = null
        if (t != null && t.isAlive && t !== Thread.currentThread()) {
            runCatching { t.join(150) }
            if (t.isAlive) {
                runCatching { activeRecorder?.stop() }
                runCatching { t.join(300) }
            }
        }
        onLevelChanged(0f)
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(bufferSize: Int): AudioRecord? {
        // VOICE_COMMUNICATION enables hardware AGC/NS/AEC; fall back to raw MIC if needed
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
        )
        for (src in sources) {
            val rec = runCatching {
                AudioRecord(
                    src,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
            }.getOrNull()
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                return rec
            }
            runCatching { rec?.release() }
        }
        return null
    }

    private fun attachAudioEffects(recorder: AudioRecord): Pair<NoiseSuppressor?, AcousticEchoCanceler?> {
        val sessionId = recorder.audioSessionId
        val ns = if (NoiseSuppressor.isAvailable()) {
            runCatching { NoiseSuppressor.create(sessionId)?.apply { enabled = true } }.getOrNull()
        } else {
            null
        }
        val aec = if (AcousticEchoCanceler.isAvailable()) {
            runCatching { AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } }.getOrNull()
        } else {
            null
        }
        return ns to aec
    }

    private fun releaseRecorder(
        recorder: AudioRecord?,
        noiseSuppressor: NoiseSuppressor?,
        echoCanceler: AcousticEchoCanceler?,
    ) {
        runCatching { recorder?.stop() }
        runCatching { noiseSuppressor?.release() }
        runCatching { echoCanceler?.release() }
        runCatching { recorder?.release() }
    }

    private fun streamLoop(initialRecorder: AudioRecord, bufferSize: Int, host: String, port: Int) {
        var recorder: AudioRecord? = initialRecorder
        var noiseSuppressor: NoiseSuppressor? = null
        var echoCanceler: AcousticEchoCanceler? = null
        var socket: DatagramSocket? = null

        try {
            val (ns, aec) = attachAudioEffects(initialRecorder)
            noiseSuppressor = ns
            echoCanceler = aec

            val address = InetAddress.getByName(host)
            socket = DatagramSocket().apply {
                runCatching { trafficClass = 0x10 } // IPTOS_LOWDELAY
            }

            val packetBuf = ByteArray(HEADER_BYTES + FRAME_BYTES)
            val pcmShortBuf = ShortArray(FRAME_SAMPLES)

            initialRecorder.startRecording()
            if (initialRecorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("Could not start microphone recording")
            }

            var seq = 1
            var lastLevelMs = 0L
            var consecutiveReadErrors = 0
            var recoveryAttempts = 0

            while (running) {
                val activeRec = recorder ?: break
                val readSamples = activeRec.read(pcmShortBuf, 0, FRAME_SAMPLES, AudioRecord.READ_BLOCKING)
                if (readSamples <= 0) {
                    if (!running) break
                    consecutiveReadErrors += 1
                    runCatching { Thread.sleep(READ_ERROR_SLEEP_MS) }
                    if (consecutiveReadErrors >= MAX_CONSECUTIVE_READ_ERRORS) {
                        if (recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) {
                            throw IllegalStateException("Microphone stream interrupted")
                        }
                        recoveryAttempts += 1
                        consecutiveReadErrors = 0
                        releaseRecorder(recorder, noiseSuppressor, echoCanceler)
                        recorder = null
                        noiseSuppressor = null
                        echoCanceler = null
                        activeRecorder = null

                        runCatching { Thread.sleep(RECOVERY_DELAY_MS) }
                        if (!running) break

                        val recreated = createAudioRecord(bufferSize)
                            ?: throw IllegalStateException("Could not reopen phone microphone")
                        recorder = recreated
                        activeRecorder = recreated
                        val (newNs, newAec) = attachAudioEffects(recreated)
                        noiseSuppressor = newNs
                        echoCanceler = newAec
                        recreated.startRecording()
                        if (recreated.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                            throw IllegalStateException("Could not resume microphone recording")
                        }
                    }
                    continue
                }
                consecutiveReadErrors = 0
                recoveryAttempts = 0

                val totalBytes = packMicPacket(packetBuf, seq++, pcmShortBuf, readSamples)
                val dgram = DatagramPacket(packetBuf, totalBytes, address, port)
                runCatching { socket.send(dgram) }

                val now = SystemClock.elapsedRealtime()
                if (now - lastLevelMs >= 65L) {
                    lastLevelMs = now
                    var sumSq = 0.0
                    for (i in 0 until readSamples) {
                        val norm = pcmShortBuf[i] / 32768.0
                        sumSq += norm * norm
                    }
                    val rms = sqrt(sumSq / readSamples).toFloat()
                    val visualLevel = (rms * 3.8f).coerceIn(0f, 1f)
                    onLevelChanged(visualLevel)
                }
            }
        } catch (e: Exception) {
            if (running) {
                onError(e.message ?: "Microphone stream stopped")
            }
        } finally {
            running = false
            activeHost = null
            activePort = -1
            activeRecorder = null
            onLevelChanged(0f)
            releaseRecorder(recorder, noiseSuppressor, echoCanceler)
            runCatching { socket?.close() }
        }
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        private const val FRAME_SAMPLES = 480 // 10 ms at 48 kHz
        private const val FRAME_BYTES = FRAME_SAMPLES * 2 // 960 bytes
        const val HEADER_BYTES = 8
        private const val READ_ERROR_SLEEP_MS = 25L
        private const val RECOVERY_DELAY_MS = 180L
        private const val MAX_CONSECUTIVE_READ_ERRORS = 10
        private const val MAX_RECOVERY_ATTEMPTS = 3

        /**
         * Pack 16-bit PCM samples into an RMIC UDP packet without overwriting the header:
         * [0..3]="RMIC", [4..7]=big-endian uint32 seq, [8..]=little-endian PCM16 samples.
         */
        fun packMicPacket(
            dest: ByteArray,
            seq: Int,
            samples: ShortArray,
            sampleCount: Int,
        ): Int {
            dest[0] = 'R'.code.toByte()
            dest[1] = 'M'.code.toByte()
            dest[2] = 'I'.code.toByte()
            dest[3] = 'C'.code.toByte()
            dest[4] = ((seq ushr 24) and 0xFF).toByte()
            dest[5] = ((seq ushr 16) and 0xFF).toByte()
            dest[6] = ((seq ushr 8) and 0xFF).toByte()
            dest[7] = (seq and 0xFF).toByte()
            var offset = HEADER_BYTES
            for (i in 0 until sampleCount) {
                val s = samples[i].toInt()
                dest[offset++] = (s and 0xFF).toByte()
                dest[offset++] = ((s ushr 8) and 0xFF).toByte()
            }
            return offset
        }
    }
}
