package com.matyankass.nemulator

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

val Accent = Color(0xFF4C8DFF)
val Bad = Color(0xFFFF6B6B)
val Bg = Color(0xFF0E1116)
val CardBg = Color(0xFF171C24)
val Muted = Color(0xFF8D99AA)

private sealed interface Screen {
    data object Home : Screen
    data class Edit(val machine: Machine, val isNew: Boolean) : Screen
}

class MainActivity : ComponentActivity() {
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = CardBg)) {
                App()
            }
        }
    }

    /** Внешняя клавиатура: пока работает ПК, все клавиши уходят в Windows. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val rfb = VmManager.rfb
        if (running && rfb != null) {
            val sc = Sc.fromAndroid(event.keyCode)
            if (sc != 0) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> rfb.key(sc, true)  // повтор при удержании тоже шлём
                    KeyEvent.ACTION_UP -> rfb.key(sc, false)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun setRunning(on: Boolean) {
        if (running == on) return
        running = on
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    @Composable
    private fun App() {
        val ctx = LocalContext.current
        val vm by VmManager.state.collectAsState()
        var screen by remember { mutableStateOf<Screen>(Screen.Home) }
        var minimized by remember { mutableStateOf(false) }
        var bundleProgress by remember { mutableStateOf<Float?>(null) }
        var bundleError by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(Unit) {
            if (!QemuBundle.isReady(ctx)) {
                bundleProgress = 0f
                try {
                    withContext(Dispatchers.IO) { QemuBundle.install(ctx) { bundleProgress = it } }
                } catch (e: Exception) {
                    bundleError = e.message ?: e.toString()
                }
                bundleProgress = null
            }
        }

        val showVm = (vm is VmState.Running || vm is VmState.Starting) && !minimized
        LaunchedEffect(showVm) { setRunning(showVm) }
        LaunchedEffect(vm) { if (vm is VmState.Starting) minimized = false }

        Box(Modifier.fillMaxSize().background(Bg)) {
            when {
                bundleProgress != null -> Preparing(bundleProgress!!)
                showVm -> RunScreen(vm, onMinimize = { minimized = true })
                else -> when (val s = screen) {
                    Screen.Home -> Home(
                        vm = vm,
                        bundleError = bundleError,
                        onResume = { minimized = false },
                        onEdit = { m, new -> screen = Screen.Edit(m, new) },
                    )
                    is Screen.Edit -> {
                        BackHandler { screen = Screen.Home }
                        Editor(s.machine, s.isNew, onDone = { screen = Screen.Home })
                    }
                }
            }
        }
    }
}

@Composable
private fun Preparing(progress: Float) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nemulator", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        Text("Подготавливаю эмулятор… ${(progress * 100).roundToInt()}%", color = Color.White, fontSize = 16.sp)
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp))
        Spacer(Modifier.height(10.dp))
        Text("Это только при первом запуске", color = Muted, fontSize = 13.sp)
    }
}

// ---------------- Список ПК ----------------

@Composable
private fun Home(
    vm: VmState,
    bundleError: String?,
    onResume: () -> Unit,
    onEdit: (Machine, Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    var machines by remember { mutableStateOf(Machines.list(ctx)) }
    var toDelete by remember { mutableStateOf<Machine?>(null) }
    var pendingStart by remember { mutableStateOf<Machine?>(null) }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingStart?.let { VmManager.start(ctx, it, granted) }
        pendingStart = null
    }

    fun start(m: Machine) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            VmManager.start(ctx, m, true)
        } else {
            pendingStart = m
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(18.dp),
    ) {
        Text("Nemulator", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Windows 10 на телефоне", color = Muted, fontSize = 15.sp)
        Spacer(Modifier.height(18.dp))

        if (bundleError != null) {
            Note("Ядро эмулятора не распаковалось: $bundleError\nПроверь, хватает ли места, и перезапусти приложение.", Bad)
            Spacer(Modifier.height(12.dp))
        }

        when (vm) {
            is VmState.Running, is VmState.Starting -> {
                val m = if (vm is VmState.Running) vm.machine else (vm as VmState.Starting).machine
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Accent)
                        .clickable(onClick = onResume).padding(16.dp),
                ) {
                    Column {
                        Text("«${m.name}» работает", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("Нажми, чтобы вернуться в Windows", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            is VmState.Failed -> {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF2A1A1D)).padding(16.dp),
                ) {
                    Text("ПК «${vm.machine.name}» не запустился", color = Bad, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(vm.message, color = Color.White, fontSize = 14.sp)
                    if (vm.log.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            vm.log.lines().takeLast(8).joinToString("\n"),
                            color = Muted, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    SmallButton("Понятно") { VmManager.dismissError() }
                }
                Spacer(Modifier.height(14.dp))
            }
            VmState.Idle -> Unit
        }

        if (machines.isEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg).padding(18.dp)) {
                Text("Как начать", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(10.dp))
                Step("1", "Скачай ISO-образ Windows 10 (с сайта Microsoft или облегчённую сборку вроде Tiny10 — она заметно быстрее).")
                Step("2", "Нажми «Новый ПК», выбери этот ISO, память и ядра.")
                Step("3", "Запусти и установи Windows как на обычный компьютер. Установка займёт 1–2 часа — телефон изображает процессор ПК.")
                Step("4", "Меню — свайп из левого нижнего угла. Там клавиатура, режим мыши и выключение.")
            }
            Spacer(Modifier.height(14.dp))
        }

        machines.forEach { m ->
            MachineCard(
                m = m,
                busy = vm !is VmState.Idle && vm !is VmState.Failed,
                onStart = { start(m) },
                onEdit = { onEdit(m, false) },
                onDelete = { toDelete = m },
            )
            Spacer(Modifier.height(12.dp))
        }

        PrimaryButton("+  Новый ПК", Modifier.fillMaxWidth()) { onEdit(defaultMachine(ctx), true) }
    }

    toDelete?.let { m ->
        Dialog(
            title = "Удалить «${m.name}»?",
            text = "Жёсткий диск этого ПК со всеми файлами Windows будет стёрт. Сам ISO-образ останется.",
            confirm = "Удалить",
            onConfirm = {
                Machines.delete(ctx, m)
                machines = Machines.list(ctx)
                toDelete = null
            },
            onDismiss = { toDelete = null },
        )
    }
}

@Composable
private fun MachineCard(m: Machine, busy: Boolean, onStart: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val used = remember(m) { Machines.diskUsedBytes(ctx, m) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg).padding(16.dp)) {
        Text(m.name, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "${m.cpus} ${cores(m.cpus)} · ${gb(m.ramMb)} ОЗУ · диск ${m.diskGb} ГБ (занято ${"%.1f".format(used / 1e9)} ГБ)",
            color = Muted, fontSize = 13.sp,
        )
        if (m.isoPath.isNotBlank()) {
            Text(
                "Диск в приводе: ${File(m.isoPath).name}",
                color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("▶  Запустить", enabled = !busy, onClick = onStart)
            SmallButton("Настроить", enabled = !busy, onClick = onEdit)
            SmallButton("Удалить", enabled = !busy, danger = true, onClick = onDelete)
        }
    }
}

private fun cores(n: Int) = when {
    n % 10 == 1 && n % 100 != 11 -> "ядро"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "ядра"
    else -> "ядер"
}

private fun gb(mb: Int) = if (mb % 1024 == 0) "${mb / 1024} ГБ" else "%.1f ГБ".format(mb / 1024f)

private fun totalRamMb(ctx: Context): Int {
    val mi = ActivityManager.MemoryInfo()
    (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
    return (mi.totalMem / (1024 * 1024)).toInt()
}

/** Сколько памяти можно отдать Windows, не уронив сам телефон. */
private fun maxRamMb(ctx: Context) = ((totalRamMb(ctx) * 0.6f).toInt() / 512 * 512).coerceIn(1536, 8192)

private fun defaultMachine(ctx: Context): Machine {
    val maxRam = maxRamMb(ctx)
    val cpus = Runtime.getRuntime().availableProcessors()
    val n = Machines.list(ctx).size
    return Machine(
        name = if (n == 0) "Windows 10" else "Windows 10 (${n + 1})",
        ramMb = minOf(4096, maxRam),
        cpus = cpus.coerceIn(1, 4),
    )
}

// ---------------- Настройки ПК ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Editor(initial: Machine, isNew: Boolean, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial.name) }
    var iso by remember { mutableStateOf(initial.isoPath) }
    var ram by remember { mutableFloatStateOf(initial.ramMb.toFloat()) }
    var cpus by remember { mutableFloatStateOf(initial.cpus.toFloat()) }
    var disk by remember { mutableIntStateOf(initial.diskGb) }
    var res by remember { mutableStateOf(initial.resolution) }
    var cpu by remember { mutableStateOf(initial.cpu) }
    var copying by remember { mutableStateOf<Float?>(null) }
    var isoError by remember { mutableStateOf<String?>(null) }

    val maxRam = remember { maxRamMb(ctx) }
    val maxCpus = remember { Runtime.getRuntime().availableProcessors() }

    val pickIso = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        isoError = null
        val direct = uriToPath(ctx, uri)?.takeIf { File(it).canRead() }
        if (direct != null) {
            iso = direct
        } else {
            // Файл не достать по пути (облако, SD-карта) — копируем к себе.
            scope.launch {
                copying = 0f
                try {
                    iso = withContext(Dispatchers.IO) { copyIso(ctx, uri) { copying = it } }
                } catch (e: Exception) {
                    isoError = "Не удалось скопировать: ${e.message}"
                }
                copying = null
            }
        }
    }
    val storage = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pickIso.launch(arrayOf("*/*"))
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(18.dp),
    ) {
        Text(if (isNew) "Новый ПК" else "Настройки ПК", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            label = { Text("Название") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
        )

        Section("Установочный образ Windows (ISO)") {
            if (copying != null) {
                Text("Копирую образ… ${(copying!! * 100).roundToInt()}%", color = Color.White)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { copying!! }, modifier = Modifier.fillMaxWidth())
            } else {
                Text(
                    if (iso.isBlank()) "Не выбран — без него можно только загрузить уже установленную Windows"
                    else File(iso).name,
                    color = if (iso.isBlank()) Muted else Color.White, fontSize = 14.sp,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton(if (iso.isBlank()) "Выбрать ISO" else "Другой ISO") {
                        val perms = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        if (perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) {
                            pickIso.launch(arrayOf("*/*"))
                        } else {
                            storage.launch(perms)
                        }
                    }
                    if (iso.isNotBlank()) SmallButton("Вынуть диск") { iso = "" }
                }
                isoError?.let { Text(it, color = Bad, fontSize = 13.sp) }
                Text(
                    "Когда Windows установится, диск можно вынуть — так ПК грузится чуть быстрее.",
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        Section("Оперативная память: ${gb(ram.roundToInt())}") {
            Slider(
                value = ram,
                onValueChange = { ram = (it / 512).roundToInt() * 512f },
                valueRange = 1024f..maxRam.toFloat(),
                colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
            )
            Text("Windows 10 нужно минимум 2 ГБ, удобно — 3–4 ГБ. Остальное остаётся телефону.", color = Muted, fontSize = 12.sp)
        }

        Section("Ядра процессора: ${cpus.roundToInt()}") {
            Slider(
                value = cpus,
                onValueChange = { cpus = it.roundToInt().toFloat() },
                valueRange = 1f..maxCpus.toFloat(),
                steps = (maxCpus - 2).coerceAtLeast(0),
                colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
            )
            Text("Больше ядер — быстрее, но телефон сильнее греется. Оптимально 4.", color = Muted, fontSize = 12.sp)
        }

        if (isNew) {
            Section("Жёсткий диск") {
                Chips(listOf(32, 48, 64, 96, 128), disk, { "$it ГБ" }) { disk = it }
                Text("Место на телефоне занимается постепенно, по мере заполнения диска.", color = Muted, fontSize = 12.sp)
            }
        }

        Section("Разрешение экрана") {
            Chips(Resolution.presets, res, { it.toString() }) { res = it }
            Text("Меньше разрешение — быстрее работает. Windows можно переключить и внутри.", color = Muted, fontSize = 12.sp)
        }

        Section("Процессор") {
            CpuModel.entries.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .then(if (cpu == c) Modifier.border(1.5.dp, Accent, RoundedCornerShape(12.dp)) else Modifier)
                        .clickable { cpu = c }.padding(12.dp),
                ) {
                    Column {
                        Text(c.title, color = Color.White, fontWeight = FontWeight.Medium)
                        Text(c.hint, color = Muted, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(if (isNew) "Создать" else "Сохранить", enabled = copying == null && name.isNotBlank()) {
                Machines.save(
                    ctx,
                    initial.copy(
                        name = name.trim(),
                        isoPath = iso,
                        ramMb = ram.roundToInt(),
                        cpus = cpus.roundToInt(),
                        diskGb = disk,
                        resolution = res,
                        cpu = cpu,
                    ),
                )
                onDone()
            }
            SmallButton("Отмена", onClick = onDone)
        }
    }
}

/** Путь к файлу по ссылке из системного выбора файлов — если он на памяти телефона. */
private fun uriToPath(ctx: Context, uri: Uri): String? = runCatching {
    if (DocumentsContract.isDocumentUri(ctx, uri)) {
        val id = DocumentsContract.getDocumentId(uri)
        when (uri.authority) {
            "com.android.externalstorage.documents" -> {
                val (vol, rel) = id.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                if (vol.equals("primary", true)) "${Environment.getExternalStorageDirectory().path}/$rel"
                else "/storage/$vol/$rel"
            }
            "com.android.providers.downloads.documents" -> when {
                id.startsWith("raw:") -> id.removePrefix("raw:")
                else -> displayName(ctx, uri)?.let {
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), it).path
                }
            }
            else -> null
        }
    } else if (uri.scheme == "file") uri.path else null
}.getOrNull()

private fun displayName(ctx: Context, uri: Uri): String? =
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

private fun copyIso(ctx: Context, uri: Uri, progress: (Float) -> Unit): String {
    val name = displayName(ctx, uri) ?: "windows.iso"
    val size = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getLong(0) else 0L
    } ?: 0L
    val dir = File(ctx.filesDir, "iso").apply { mkdirs() }
    val out = File(dir, name)
    val tmp = File(dir, "$name.part")
    ctx.contentResolver.openInputStream(uri)!!.use { input ->
        tmp.outputStream().use { os ->
            val buf = ByteArray(1 shl 20)
            var done = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                os.write(buf, 0, n)
                done += n
                if (size > 0) progress((done.toFloat() / size).coerceAtMost(1f))
            }
        }
    }
    tmp.renameTo(out)
    return out.path
}

// ---------------- Мелкие элементы ----------------

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg).padding(14.dp)) {
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Chips(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            val on = item == selected
            Text(
                label(item),
                color = if (on) Color.White else Muted,
                fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (on) Accent else Color(0xFF232B37))
                    .clickable { onSelect(item) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Step(n: String, text: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text(n, color = Accent, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 22.dp))
        Text(text, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun Note(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBg).padding(12.dp),
    )
}

@Composable
private fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(if (enabled) Accent else Accent.copy(alpha = 0.35f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
private fun SmallButton(text: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF232B37))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Text(
            text,
            color = when {
                !enabled -> Muted.copy(alpha = 0.5f)
                danger -> Bad
                else -> Color.White
            },
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun Dialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onConfirm) { Text(confirm, color = Bad) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Отмена") } },
        containerColor = CardBg,
    )
}
