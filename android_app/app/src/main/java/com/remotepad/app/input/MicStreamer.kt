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
    private var workerThread: Thread? = null
    private val lock = Any()

    @SuppressLint("MissingPermission")
    fun start(host: String, port: Int): Boolean {
        synchronized(lock) {
            stopLocked()
            val cleanHost = host.trim()
            if (cleanHost.isEmpty() || port !in 1..65535) {
                onError("Invalid PC address for microphone")
                return false
            }

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
            workerThread = thread(name = "remotepad-mic-streamer", isDaemon = true) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                streamLoop(recorder, cleanHost, port)
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
        val t = workerThread
        workerThread = null
        if (t != null && t.isAlive && t !== Thread.currentThread()) {
            runCatching { t.join(400) }
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

    private fun streamLoop(recorder: AudioRecord, host: String, port: Int) {
        var noiseSuppressor: NoiseSuppressor? = null
        var echoCanceler: AcousticEchoCanceler? = null
        var socket: DatagramSocket? = null

        try {
            val sessionId = recorder.audioSessionId
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = runCatching {
                    NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                }.getOrNull()
            }
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = runCatching {
                    AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                }.getOrNull()
            }

            val address = InetAddress.getByName(host)
            socket = DatagramSocket().apply {
                runCatching { trafficClass = 0x10 } // IPTOS_LOWDELAY
            }

            val packetBuf = ByteArray(HEADER_BYTES + FRAME_BYTES)
            val pcmShortBuf = ShortArray(FRAME_SAMPLES)

            recorder.startRecording()
            var seq = 1
            var lastLevelMs = 0L

            while (running) {
                val readSamples = recorder.read(pcmShortBuf, 0, FRAME_SAMPLES, AudioRecord.READ_BLOCKING)
                if (readSamples <= 0) continue

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
            onLevelChanged(0f)
            runCatching { recorder.stop() }
            runCatching { noiseSuppressor?.release() }
            runCatching { echoCanceler?.release() }
            runCatching { recorder.release() }
            runCatching { socket?.close() }
        }
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        private const val FRAME_SAMPLES = 480 // 10 ms at 48 kHz
        private const val FRAME_BYTES = FRAME_SAMPLES * 2 // 960 bytes
        const val HEADER_BYTES = 8

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
