package com.matyankass.nemulator

import android.content.Context
import android.system.Os
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Ядро — QEMU из Alpine (musl) — лежит в APK архивом assets/bundle.tar.xz
 * (собирает tools/build-bundle.sh) и при первом запуске распаковывается в
 * files/qemu. Запускается без proot: через загрузчик musl, которому явно
 * передаются папки с библиотеками.
 */
object QemuBundle {
    fun dir(ctx: Context) = File(ctx.filesDir, "qemu")

    private fun assetInfo(ctx: Context): Pair<String, Long> {
        val parts = ctx.assets.open("bundle.version").bufferedReader().readText().trim().split(" ")
        return parts[0] to (parts.getOrNull(1)?.toLongOrNull() ?: 70_000_000L)
    }

    fun isReady(ctx: Context): Boolean {
        val installed = File(dir(ctx), "VERSION")
        return installed.exists() && installed.readText().trim() == assetInfo(ctx).first
    }

    /** Распаковывает ядро; [progress] получает долю 0..1. */
    fun install(ctx: Context, progress: (Float) -> Unit) {
        val total = assetInfo(ctx).second.coerceAtLeast(1)
        val target = dir(ctx)
        val tmp = File(ctx.filesDir, "qemu.new")
        tmp.deleteRecursively()
        tmp.mkdirs()
        var done = 0L
        XZInputStream(BufferedInputStream(ctx.assets.open("bundle.tar.xz"), 1 shl 16)).use { xz ->
            untar(xz, tmp) { bytes ->
                done += bytes
                progress((done.toFloat() / total).coerceAtMost(1f))
            }
        }
        target.deleteRecursively()
        if (!tmp.renameTo(target)) throw IOException("не удалось переименовать ${tmp.path}")
        progress(1f)
    }

    /**
     * Мини-распаковщик ustar: обычные файлы, папки, симлинки и длинные
     * имена GNU. Больше build-bundle.sh ничего не кладёт.
     */
    private fun untar(input: InputStream, dest: File, onBytes: (Long) -> Unit) {
        val header = ByteArray(512)
        val buf = ByteArray(1 shl 16)
        var longName: String? = null
        while (true) {
            if (!readFully(input, header, 512)) return
            if (header.all { it.toInt() == 0 }) return

            val type = header[156].toInt().toChar()
            val size = octal(header, 124, 12)
            val mode = octal(header, 100, 8).toInt()
            var name = longName ?: run {
                val prefix = cString(header, 345, 155)
                val base = cString(header, 0, 100)
                if (prefix.isEmpty()) base else "$prefix/$base"
            }
            longName = null
            name = name.removePrefix("./").trimEnd('/')

            if (type == 'L') {  // следующий заголовок получает это длинное имя
                val bytes = ByteArray(size.toInt())
                readFully(input, bytes, bytes.size)
                skip(input, pad(size))
                longName = String(bytes).trimEnd('\u0000')
                continue
            }

            val out = File(dest, name)
            if (name.isNotEmpty() && !out.canonicalPath.startsWith(dest.canonicalPath)) {
                throw IOException("подозрительный путь в архиве: $name")
            }
            when (type) {
                '5' -> out.mkdirs()
                '2' -> {
                    out.parentFile?.mkdirs()
                    out.delete()
                    Os.symlink(cString(header, 157, 100), out.path)
                }
                '0', '\u0000' -> {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { os ->
                        var left = size
                        while (left > 0) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) throw IOException("архив оборван")
                            os.write(buf, 0, n)
                            left -= n
                            onBytes(n.toLong())
                        }
                    }
                    skip(input, pad(size))
                    Os.chmod(out.path, if (mode and 0b001_000_000 != 0) 0b111_101_101 else 0b110_100_100)
                }
                else -> skip(input, size + pad(size))  // pax-заголовки и прочее — пропускаем
            }
        }
    }

    private fun pad(size: Long) = (512 - size % 512) % 512

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Boolean {
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun skip(input: InputStream, count: Long) {
        var left = count
        val tmp = ByteArray(4096)
        while (left > 0) {
            val n = input.read(tmp, 0, minOf(tmp.size.toLong(), left).toInt())
            if (n < 0) return
            left -= n
        }
    }

    private fun cString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long =
        cString(b, off, len).trim().ifEmpty { "0" }.toLong(8)
}
