package com.matyankass.nemulator

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.File
import java.io.IOException

/**
 * Звук Windows ↔ телефон. С той стороны — ALSA-плагин nemu внутри QEMU
 * (native/alsa-nemu): он слушает два сокета, out.sock (звук из Windows) и
 * in.sock (микрофон в Windows). Сокеты появляются, когда Windows впервые
 * открывает звук, поэтому подключаемся с повторами.
 */
class AudioBridge(private val runDir: File) {
    @Volatile private var stopped = false
    /** Выключенный микрофон шлёт тишину — так Windows не теряет устройство. */
    @Volatile var micEnabled = true

    private val threads = mutableListOf<Thread>()

    fun start(micAllowed: Boolean) {
        threads += Thread({ playback() }, "audio-out").also { it.start() }
        if (micAllowed) threads += Thread({ capture() }, "audio-in").also { it.start() }
    }

    fun stop() {
        stopped = true
        threads.forEach { it.interrupt() }
    }

    private fun connect(name: String): LocalSocket {
        val path = File(runDir, name)
        while (!stopped) {
            if (path.exists()) {
                try {
                    return LocalSocket().apply {
                        connect(LocalSocketAddress(path.path, LocalSocketAddress.Namespace.FILESYSTEM))
                    }
                } catch (_: IOException) {
                }
            }
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
            }
        }
        throw InterruptedException()
    }

    private fun playback() {
        while (!stopped) {
            try {
                connect("out.sock").use { s ->
                    val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                    val track = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_GAME)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build()
                        )
                        .setBufferSizeInBytes(maxOf(min * 2, RATE / 10 * 4))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                    try {
                        track.play()
                        val buf = ByteArray(4096)
                        val input = s.inputStream
                        while (!stopped) {
                            val n = input.read(buf)
                            if (n < 0) break
                            track.write(buf, 0, n)
                        }
                    } finally {
                        track.release()
                    }
                }
            } catch (_: InterruptedException) {
                return
            } catch (_: Exception) {
                sleepQuietly()
            }
        }
    }

    @SuppressLint("MissingPermission")  // запускается только после разрешения на микрофон
    private fun capture() {
        while (!stopped) {
            try {
                connect("in.sock").use { s ->
                    val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    val rec = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(min * 2, 8192),
                    )
                    try {
                        rec.startRecording()
                        val mono = ShortArray(441)  // 10 мс
                        val stereo = ByteArray(mono.size * 4)
                        val out = s.outputStream
                        while (!stopped) {
                            val n = rec.read(mono, 0, mono.size)
                            if (n <= 0) continue
                            val on = micEnabled
                            for (i in 0 until n) {
                                val v = if (on) mono[i].toInt() else 0
                                val lo = (v and 0xFF).toByte()
                                val hi = ((v shr 8) and 0xFF).toByte()
                                stereo[i * 4] = lo; stereo[i * 4 + 1] = hi
                                stereo[i * 4 + 2] = lo; stereo[i * 4 + 3] = hi
                            }
                            out.write(stereo, 0, n * 4)
                        }
                    } finally {
                        rec.release()
                    }
                }
            } catch (_: InterruptedException) {
                return
            } catch (_: Exception) {
                sleepQuietly()
            }
        }
    }

    private fun sleepQuietly() {
        try {
            Thread.sleep(1000)
        } catch (_: InterruptedException) {
        }
    }

    companion object {
        const val RATE = 44100
    }
}
