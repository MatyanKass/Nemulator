package com.matyankass.nemulator

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.util.concurrent.Executors

sealed interface VmState {
    data object Idle : VmState
    data class Starting(val machine: Machine, val message: String) : VmState
    data class Running(val machine: Machine) : VmState
    data class Failed(val machine: Machine, val message: String, val log: String) : VmState
}

/**
 * Запущенный ПК — один на всё приложение. Живёт дольше экрана: свернул
 * приложение — Windows продолжает работать (держит VmService).
 */
object VmManager {
    private val _state = MutableStateFlow<VmState>(VmState.Idle)
    val state: StateFlow<VmState> = _state

    @Volatile var rfb: RfbClient? = null
        private set
    @Volatile var audio: AudioBridge? = null
        private set
    /** Экран подписывается сюда, чтобы перерисоваться на новый кадр. */
    @Volatile var onFrame: (() -> Unit)? = null

    private var process: Process? = null
    @Volatile private var stopRequested = false
    private val log = ArrayDeque<String>()
    private val worker = Executors.newSingleThreadExecutor()

    fun runDir(ctx: Context) = File(ctx.filesDir, "run")

    fun start(ctx: Context, machine: Machine, micAllowed: Boolean) {
        if (_state.value is VmState.Running || _state.value is VmState.Starting) return
        val app = ctx.applicationContext
        stopRequested = false
        _state.value = VmState.Starting(machine, "Готовлю ПК…")
        app.startForegroundServiceCompat(Intent(app, VmService::class.java))
        worker.execute {
            try {
                launch(app, machine, micAllowed)
            } catch (e: Exception) {
                fail(app, machine, e.message ?: e.toString())
            }
        }
    }

    private fun launch(ctx: Context, m: Machine, micAllowed: Boolean) {
        if (!QemuBundle.isReady(ctx)) {
            _state.value = VmState.Starting(m, "Распаковываю ядро…")
            QemuBundle.install(ctx) {}
        }
        val bundle = QemuBundle.dir(ctx)
        val run = runDir(ctx)
        run.deleteRecursively()
        run.mkdirs()

        val disk = m.disk(ctx)
        if (!disk.exists()) {
            _state.value = VmState.Starting(m, "Создаю жёсткий диск ${m.diskGb} ГБ…")
            m.dir(ctx).mkdirs()
            val img = exec(bundle, run, listOf("qemu-img", "create", "-f", "qcow2", disk.path, "${m.diskGb}G"))
            val out = img.inputStream.bufferedReader().readText()
            val code = img.waitFor()
            if (code != 0) throw IOException("не создать диск (${exitReason(code)}): $out")
        }

        writeDns(ctx, File(run, "r"))
        File(run, "asound.conf").writeText(
            """
            pcm_type.nemu { lib "${bundle.path}/usr/lib/libasound_module_pcm_nemu.so" }
            pcm.nemu { type nemu out "${run.path}/out.sock" in "${run.path}/in.sock" }
            """.trimIndent() + "\n"
        )

        _state.value = VmState.Starting(m, "Запускаю Windows…")
        synchronized(log) { log.clear() }
        val p = exec(bundle, run, listOf("qemu-system-x86_64") + qemuArgs(ctx, m, run))
        process = p
        Thread({ pumpLog(ctx, p) }, "qemu-log").start()
        Thread({ watch(ctx, m, p) }, "qemu-wait").start()

        val client = RfbClient(File(run, "vnc.sock"), object : RfbClient.Listener {
            override fun onFrame() {
                if (_state.value is VmState.Starting) _state.value = VmState.Running(m)
                onFrame?.invoke()
            }

            override fun onDisconnected(error: String?) {
                if (!stopRequested && p.isAlive) {
                    // Экран отвалился, а ПК жив — убиваем, иначе им не управлять.
                    p.destroy()
                }
            }
        })
        rfb = client
        client.start()
        audio = AudioBridge(run).also { it.start(micAllowed) }
    }

    /**
     * Команда из бандла через загрузчик musl: без proot и без chroot.
     * libnemu_sigsys не даёт фильтру seccomp Android молча убить процесс
     * (см. native/sigsys).
     */
    private fun exec(bundle: File, run: File, cmd: List<String>): Process {
        val b = bundle.path
        val full = listOf(
            "$b/lib/ld-musl-aarch64.so.1",
            "--library-path", "$b/usr/lib:$b/lib",
            "--preload", "$b/usr/lib/libnemu_sigsys.so",
            "$b/usr/bin/${cmd[0]}",
        ) + cmd.drop(1)
        return ProcessBuilder(full)
            .directory(run)
            .redirectErrorStream(true)
            .apply {
                environment().apply {
                    put("QEMU_MODULE_DIR", "$b/usr/lib/qemu")
                    put("ALSA_CONFIG_PATH", "${run.path}/asound.conf")
                    put("TMPDIR", run.path)
                    put("HOME", run.path)
                }
            }
            .start()
    }

    private fun qemuArgs(ctx: Context, m: Machine, run: File): List<String> {
        val a = mutableListOf(
            "-name", m.name,
            "-nodefaults",
            "-machine", "q35,vmport=off",
            "-global", "ICH9-LPC.disable_s3=1",
            // Многопоточная трансляция x86 → ARM и большой кэш переведённого кода.
            "-accel", "tcg,thread=multi,tb-size=${tbSizeMb(ctx)}",
            "-cpu", m.cpu.qemu,
            "-smp", "${m.cpus}",
            "-m", "${m.ramMb}",
            "-rtc", "base=localtime,clock=host",
            "-L", "${QemuBundle.dir(ctx).path}/usr/share/qemu",

            "-drive", "file=${m.disk(ctx).path},if=none,id=hd0,format=qcow2,cache=writeback,aio=threads,discard=unmap,detect-zeroes=unmap",
            "-device", "ide-hd,drive=hd0,bus=ide.0,rotation_rate=1",

            "-device", "VGA,vgamem_mb=64,xres=${m.resolution.w},yres=${m.resolution.h}",
            "-display", "none",
            "-vnc", "unix:${run.path}/vnc.sock,lock-key-sync=off",

            // Интернет: NAT QEMU, у Windows — обычная сетевая карта Intel.
            "-netdev", "user,id=net0",
            "-device", "e1000,netdev=net0",

            // Планшет вместо мыши — курсор ставится точно туда, куда коснулись.
            "-device", "qemu-xhci,id=xhci",
            "-device", "usb-tablet,bus=xhci.0",

            "-audiodev", "alsa,id=snd0,out.dev=nemu,in.dev=nemu,out.try-poll=off,in.try-poll=off",
            "-device", "ich9-intel-hda",
            "-device", "hda-micro,audiodev=snd0",

            "-qmp", "unix:${run.path}/qmp.sock,server=on,wait=off",
            // Сначала диск, потом установочный ISO: пустой диск сам передаст загрузку ISO.
            "-boot", "order=cd,menu=off",
        )
        if (m.isoPath.isNotBlank() && File(m.isoPath).exists()) {
            a += listOf(
                "-drive", "file=${m.isoPath},if=none,id=cd0,media=cdrom,readonly=on",
                "-device", "ide-cd,drive=cd0,bus=ide.1",
            )
        }
        return a
    }

    /** Кэш переведённого кода: больше — быстрее, но это память телефона. */
    private fun tbSizeMb(ctx: Context): Int {
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val gb = mi.totalMem / (1L shl 30)
        return when {
            gb >= 10 -> 1024
            gb >= 7 -> 512
            else -> 256
        }
    }

    /** DNS телефона для интернета в Windows (см. tools/build-bundle.sh про файл «r»). */
    private fun writeDns(ctx: Context, file: File) {
        val servers = mutableListOf<String>()
        runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.getLinkProperties(cm.activeNetwork)?.dnsServers.orEmpty()
                .filterIsInstance<Inet4Address>()
                .mapNotNullTo(servers) { it.hostAddress }
        }
        servers += listOf("8.8.8.8", "1.1.1.1")
        file.writeText(servers.distinct().joinToString("") { "nameserver $it\n" })
    }

    private fun pumpLog(ctx: Context, p: Process) {
        val file = File(runDir(ctx), "qemu.log")
        runCatching {
            file.bufferedWriter().use { w ->
                p.inputStream.bufferedReader().forEachLine { line ->
                    w.write(line); w.newLine(); w.flush()
                    synchronized(log) {
                        log.addLast(line)
                        while (log.size > 80) log.removeFirst()
                    }
                }
            }
        }
    }

    private fun watch(ctx: Context, m: Machine, p: Process) {
        val code = p.waitFor()
        Thread.sleep(300)  // дочитать хвост лога
        teardown()
        if (stopRequested || code == 0) {
            _state.value = VmState.Idle
            ctx.stopService(Intent(ctx, VmService::class.java))
        } else {
            fail(ctx, m, "QEMU завершился: ${exitReason(code)}")
        }
    }

    /** Код выхода по-человечески: >128 — процесс убит сигналом. */
    private fun exitReason(code: Int): String = when (code) {
        128 + 9 -> "убит системой — скорее всего, не хватило памяти телефону"
        128 + 11 -> "сбой памяти (SIGSEGV), код $code"
        128 + 31 -> "Android запретил системный вызов (SIGSYS), код $code"
        else -> if (code > 128) "убит сигналом ${code - 128}" else "код $code"
    }

    private fun fail(ctx: Context, m: Machine, message: String) {
        teardown()
        runCatching { process?.destroy() }
        val tail = synchronized(log) { log.joinToString("\n") }
        _state.value = VmState.Failed(m, message, tail)
        ctx.stopService(Intent(ctx, VmService::class.java))
    }

    private fun teardown() {
        rfb?.close(); rfb = null
        audio?.stop(); audio = null
    }

    fun dismissError() {
        if (_state.value is VmState.Failed) _state.value = VmState.Idle
    }

    // ---- управление питанием через QMP ----

    /** «Выключить» как кнопкой питания: Windows сама корректно завершит работу. */
    fun powerButton(ctx: Context) = qmp(ctx, "system_powerdown")

    fun reset(ctx: Context) = qmp(ctx, "system_reset")

    /** Выдернуть шнур: мгновенно, но несохранённое в Windows пропадёт. */
    fun forceStop(ctx: Context) {
        stopRequested = true
        qmp(ctx, "quit")
        worker.execute {
            Thread.sleep(3000)
            process?.takeIf { it.isAlive }?.destroyForcibly()
        }
    }

    private fun qmp(ctx: Context, command: String) {
        if (command == "system_powerdown") stopRequested = true
        val path = File(runDir(ctx), "qmp.sock")
        worker.execute {
            runCatching {
                LocalSocket().use { s ->
                    s.connect(LocalSocketAddress(path.path, LocalSocketAddress.Namespace.FILESYSTEM))
                    val r = s.inputStream.bufferedReader()
                    val w = s.outputStream.bufferedWriter()
                    r.readLine()  // приветствие
                    w.write("{\"execute\":\"qmp_capabilities\"}\n"); w.flush()
                    r.readLine()
                    w.write("{\"execute\":\"$command\"}\n"); w.flush()
                    r.readLine()
                }
            }
        }
    }

    private fun Context.startForegroundServiceCompat(i: Intent) {
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }
}
