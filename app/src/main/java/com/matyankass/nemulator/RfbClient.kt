package com.matyankass.nemulator

import android.graphics.Bitmap
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Клиент VNC (RFB 3.8) к QEMU через unix-сокет. QEMU крутится на том же
 * телефоне, поэтому кадры идут «сырыми» (Raw + CopyRect) — сжимать
 * дороже, чем копировать.
 */
class RfbClient(
    private val socketPath: File,
    private val listener: Listener,
) {
    interface Listener {
        /** Новый кадр готов (или поменялся размер) — экран надо перерисовать. */
        fun onFrame()
        fun onDisconnected(error: String?)
    }

    @Volatile var bitmap: Bitmap? = null
        private set
    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set
    /** Блокировка кадра: держит её и тот, кто пишет пиксели, и тот, кто рисует. */
    val frameLock = Any()

    private var socket: LocalSocket? = null
    private var out: DataOutputStream? = null
    private val sender = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    /** До конца рукопожатия ввод не шлём — он перемешался бы с ним. */
    @Volatile private var ready = false
    private var pixels = IntArray(0)

    fun start() {
        Thread({ run() }, "rfb").start()
    }

    fun close() {
        closed = true
        runCatching { socket?.shutdownInput() }
        runCatching { socket?.close() }
        sender.shutdown()
    }

    // ---- ввод ----

    /** Кнопки: бит 0 — левая, 1 — средняя, 2 — правая, 3/4 — колесо вверх/вниз. */
    fun pointer(buttons: Int, x: Int, y: Int) = send {
        it.writeByte(5)
        it.writeByte(buttons)
        it.writeShort(x.coerceIn(0, (width - 1).coerceAtLeast(0)))
        it.writeShort(y.coerceIn(0, (height - 1).coerceAtLeast(0)))
    }

    /**
     * Нажатие по скан-коду (расширенное событие QEMU). keysym передаём 0:
     * тогда QEMU не пытается «синхронизировать» Caps Lock по букве.
     */
    fun key(scancode: Int, down: Boolean) = send {
        it.writeByte(255)
        it.writeByte(0)
        it.writeShort(if (down) 1 else 0)
        it.writeInt(0)
        it.writeInt(scancode)
    }

    private fun send(block: (DataOutputStream) -> Unit) {
        if (closed || !ready) return
        runCatching {
            sender.execute {
                val o = out ?: return@execute
                runCatching {
                    synchronized(o) {
                        block(o)
                        o.flush()
                    }
                }
            }
        }
    }

    // ---- приём ----

    private fun run() {
        var error: String? = null
        try {
            val input = connect()
            handshake(input)
            ready = true
            loop(input)
        } catch (e: Exception) {
            if (!closed) error = e.message ?: e.toString()
        } finally {
            runCatching { socket?.close() }
            if (!closed) listener.onDisconnected(error)
        }
    }

    /** QEMU создаёт сокет не сразу — ждём его до двух минут. */
    private fun connect(): DataInputStream {
        val deadline = SystemClock.uptimeMillis() + 120_000
        while (true) {
            if (closed) throw IOException("закрыто")
            if (socketPath.exists()) {
                try {
                    val s = LocalSocket()
                    s.connect(LocalSocketAddress(socketPath.path, LocalSocketAddress.Namespace.FILESYSTEM))
                    socket = s
                    out = DataOutputStream(s.outputStream.buffered(4096))
                    return DataInputStream(BufferedInputStream(s.inputStream, 1 shl 18))
                } catch (_: IOException) {
                }
            }
            if (SystemClock.uptimeMillis() > deadline) throw IOException("QEMU не открыл экран")
            Thread.sleep(250)
        }
    }

    private fun handshake(input: DataInputStream) {
        val version = ByteArray(12)
        input.readFully(version)
        val o = out!!
        o.write("RFB 003.008\n".toByteArray())
        o.flush()
        val count = input.readUnsignedByte()
        if (count == 0) throw IOException("VNC отказал: " + readString(input))
        val types = ByteArray(count).also { input.readFully(it) }
        if (types.none { it.toInt() == 1 }) throw IOException("VNC требует пароль")
        o.writeByte(1)
        o.flush()
        if (input.readInt() != 0) throw IOException("VNC отказал: " + readString(input))

        o.writeByte(1)  // shared
        o.flush()
        resize(input.readUnsignedShort(), input.readUnsignedShort())
        input.skipBytes(16)  // формат сервера — свой зададим ниже
        input.skipBytes(input.readInt())  // имя

        synchronized(o) {
            // SetPixelFormat: 32 бита, little-endian, 0x00RRGGBB — сразу ARGB для Bitmap.
            o.writeByte(0); o.write(ByteArray(3))
            o.writeByte(32); o.writeByte(24); o.writeByte(0); o.writeByte(1)
            o.writeShort(255); o.writeShort(255); o.writeShort(255)
            o.writeByte(16); o.writeByte(8); o.writeByte(0)
            o.write(ByteArray(3))

            val encodings = intArrayOf(
                1,     // CopyRect
                0,     // Raw
                -223,  // DesktopSize — Windows сменила разрешение
                -258,  // QEMU Extended Key Event — клавиши по скан-кодам
            )
            o.writeByte(2); o.writeByte(0)
            o.writeShort(encodings.size)
            encodings.forEach { o.writeInt(it) }
            requestUpdate(o, incremental = false)
            o.flush()
        }
    }

    private fun loop(input: DataInputStream) {
        var row = ByteArray(0)
        var lastRequest = 0L
        while (!closed) {
            when (val type = input.readUnsignedByte()) {
                0 -> {
                    input.skipBytes(1)
                    val rects = input.readUnsignedShort()
                    var full = false
                    repeat(rects) {
                        val x = input.readUnsignedShort()
                        val y = input.readUnsignedShort()
                        val w = input.readUnsignedShort()
                        val h = input.readUnsignedShort()
                        when (val enc = input.readInt()) {
                            0 -> {
                                if (row.size < w * 4) row = ByteArray(w * 4)
                                synchronized(frameLock) {
                                    for (line in 0 until h) {
                                        input.readFully(row, 0, w * 4)
                                        var p = (y + line) * width + x
                                        var i = 0
                                        while (i < w * 4) {
                                            pixels[p++] = (0xFF shl 24) or
                                                ((row[i + 2].toInt() and 0xFF) shl 16) or
                                                ((row[i + 1].toInt() and 0xFF) shl 8) or
                                                (row[i].toInt() and 0xFF)
                                            i += 4
                                        }
                                    }
                                    bitmap?.setPixels(pixels, y * width + x, width, x, y, w, h)
                                }
                            }
                            1 -> {
                                val sx = input.readUnsignedShort()
                                val sy = input.readUnsignedShort()
                                synchronized(frameLock) { copyRect(sx, sy, x, y, w, h) }
                            }
                            -223 -> {
                                resize(w, h)
                                full = true
                            }
                            -258 -> Unit  // QEMU подтвердил поддержку скан-кодов
                            else -> throw IOException("неизвестная кодировка $enc")
                        }
                    }
                    listener.onFrame()
                    // Не чаще ~60 кадров в секунду: остальное время — эмуляции.
                    val wait = 16 - (SystemClock.uptimeMillis() - lastRequest)
                    if (wait > 0) Thread.sleep(wait)
                    lastRequest = SystemClock.uptimeMillis()
                    val o = out ?: return
                    synchronized(o) {
                        requestUpdate(o, incremental = !full)
                        o.flush()
                    }
                }
                1 -> {  // палитра — нам не нужна (true colour)
                    input.skipBytes(3)
                    input.skipBytes(input.readUnsignedShort() * 6)
                }
                2 -> Unit  // звонок
                3 -> {  // буфер обмена сервера
                    input.skipBytes(3)
                    input.skipBytes(input.readInt() and 0x7FFFFFFF)
                }
                else -> throw IOException("неизвестное сообщение VNC $type")
            }
        }
    }

    private fun requestUpdate(o: DataOutputStream, incremental: Boolean) {
        o.writeByte(3)
        o.writeByte(if (incremental) 1 else 0)
        o.writeShort(0); o.writeShort(0)
        o.writeShort(width); o.writeShort(height)
    }

    private fun resize(w: Int, h: Int) {
        synchronized(frameLock) {
            width = w
            height = h
            pixels = IntArray(w * h)
            bitmap = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                .also { it.eraseColor(0xFF000000.toInt()) }
        }
    }

    private fun copyRect(sx: Int, sy: Int, dx: Int, dy: Int, w: Int, h: Int) {
        val tmp = IntArray(w * h)
        for (line in 0 until h) System.arraycopy(pixels, (sy + line) * width + sx, tmp, line * w, w)
        for (line in 0 until h) System.arraycopy(tmp, line * w, pixels, (dy + line) * width + dx, w)
        bitmap?.setPixels(pixels, dy * width + dx, width, dx, dy, w, h)
    }

    private fun readString(input: DataInputStream): String {
        val b = ByteArray(input.readInt().coerceIn(0, 4096))
        input.readFully(b)
        return String(b)
    }
}
