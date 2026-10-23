package com.matyankass.nemulator

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Kind { NORMAL, SHIFT, CTRL, ALT, WIN, CAPS, LANG, FN, HIDE }

/**
 * Клавиша: скан-код + подписи для EN/RU, обычные и с Shift.
 * Подписи только для глаз: Windows сама решает, что напечатать, по своей
 * раскладке, поэтому кнопка РУС/ENG переключает и подписи, и раскладку
 * в Windows (Alt+Shift).
 */
private data class KeyDef(
    val sc: Int,
    val en: String,
    val enShift: String = en.uppercase(),
    val ru: String = en,
    val ruShift: String = if (ru == en) enShift else ru.uppercase(),
    val w: Float = 1f,
    val kind: Kind = Kind.NORMAL,
    val repeat: Boolean = false,
) {
    val isLetter get() = en.length == 1 && en[0].isLetter()
}

private fun k(sc: Int, en: String, enS: String, ru: String, ruS: String) = KeyDef(sc, en, enS, ru, ruS)
private fun letter(sc: Int, en: String, ru: String) = KeyDef(sc, en, ru = ru)
private fun special(sc: Int, label: String, w: Float = 1f, kind: Kind = Kind.NORMAL, repeat: Boolean = false) =
    KeyDef(sc, label, label, label, label, w, kind, repeat)

private val fnRow = listOf(
    special(Sc.ESC, "Esc"),
    special(Sc.F1, "F1"), special(Sc.F2, "F2"), special(Sc.F3, "F3"), special(Sc.F4, "F4"),
    special(Sc.F5, "F5"), special(Sc.F6, "F6"), special(Sc.F7, "F7"), special(Sc.F8, "F8"),
    special(Sc.F9, "F9"), special(Sc.F10, "F10"), special(Sc.F11, "F11"), special(Sc.F12, "F12"),
    special(Sc.PRINT, "PrtSc"), special(Sc.HOME, "Home"), special(Sc.END, "End"),
)

private val rows = listOf(
    listOf(
        k(Sc.GRAVE, "`", "~", "ё", "Ё"),
        k(Sc.N1, "1", "!", "1", "!"), k(Sc.N2, "2", "@", "2", "\""), k(Sc.N3, "3", "#", "3", "№"),
        k(Sc.N4, "4", "$", "4", ";"), k(Sc.N5, "5", "%", "5", "%"), k(Sc.N6, "6", "^", "6", ":"),
        k(Sc.N7, "7", "&", "7", "?"), k(Sc.N8, "8", "*", "8", "*"), k(Sc.N9, "9", "(", "9", "("),
        k(Sc.N0, "0", ")", "0", ")"), k(Sc.MINUS, "-", "_", "-", "_"), k(Sc.EQUAL, "=", "+", "=", "+"),
        special(Sc.BACKSPACE, "⌫", 1.6f, repeat = true),
    ),
    listOf(
        special(Sc.TAB, "Tab", 1.4f),
        letter(Sc.Q, "q", "й"), letter(Sc.W, "w", "ц"), letter(Sc.E, "e", "у"), letter(Sc.R, "r", "к"),
        letter(Sc.T, "t", "е"), letter(Sc.Y, "y", "н"), letter(Sc.U, "u", "г"), letter(Sc.I, "i", "ш"),
        letter(Sc.O, "o", "щ"), letter(Sc.P, "p", "з"),
        k(Sc.LBRACKET, "[", "{", "х", "Х"), k(Sc.RBRACKET, "]", "}", "ъ", "Ъ"),
        k(Sc.BACKSLASH, "\\", "|", "\\", "/"),
    ),
    listOf(
        special(Sc.CAPS, "Caps", 1.7f, Kind.CAPS),
        letter(Sc.A, "a", "ф"), letter(Sc.S, "s", "ы"), letter(Sc.D, "d", "в"), letter(Sc.F, "f", "а"),
        letter(Sc.G, "g", "п"), letter(Sc.H, "h", "р"), letter(Sc.J, "j", "о"), letter(Sc.K, "k", "л"),
        letter(Sc.L, "l", "д"),
        k(Sc.SEMICOLON, ";", ":", "ж", "Ж"), k(Sc.QUOTE, "'", "\"", "э", "Э"),
        special(Sc.ENTER, "Enter", 1.9f),
    ),
    listOf(
        special(Sc.LSHIFT, "⇧ Shift", 2.1f, Kind.SHIFT),
        letter(Sc.Z, "z", "я"), letter(Sc.X, "x", "ч"), letter(Sc.C, "c", "с"), letter(Sc.V, "v", "м"),
        letter(Sc.B, "b", "и"), letter(Sc.N, "n", "т"), letter(Sc.M, "m", "ь"),
        k(Sc.COMMA, ",", "<", "б", "Б"), k(Sc.DOT, ".", ">", "ю", "Ю"), k(Sc.SLASH, "/", "?", ".", ","),
        special(Sc.DELETE, "Del", 1.1f, repeat = true),
        special(Sc.UP, "↑", repeat = true),
        special(0, "⌄", 0.9f, Kind.HIDE),
    ),
    listOf(
        special(Sc.LCTRL, "Ctrl", 1.3f, Kind.CTRL),
        special(Sc.LWIN, "⊞ Win", 1.3f, Kind.WIN),
        special(Sc.LALT, "Alt", 1.2f, Kind.ALT),
        special(0, "РУС", 1.2f, Kind.LANG),
        special(Sc.SPACE, " ", 4.6f),
        special(0, "Fn", 1f, Kind.FN),
        special(Sc.LEFT, "←", repeat = true),
        special(Sc.DOWN, "↓", repeat = true),
        special(Sc.RIGHT, "→", repeat = true),
    ),
)

private enum class ShiftState { OFF, ONCE, LOCK }

@Composable
fun PcKeyboard(send: (scancode: Int, down: Boolean) -> Unit, onHide: () -> Unit) {
    var shift by remember { mutableStateOf(ShiftState.OFF) }
    var lastShiftTap by remember { mutableStateOf(0L) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var win by remember { mutableStateOf(false) }
    var caps by remember { mutableStateOf(false) }
    var ru by remember { mutableStateOf(false) }
    var fn by remember { mutableStateOf(false) }

    fun tap(sc: Int) { send(sc, true); send(sc, false) }

    // Ctrl/Alt/Win/Shift «залипают» до следующей клавиши — комбинации жмутся одним пальцем.
    fun modsDown() {
        if (ctrl) send(Sc.LCTRL, true)
        if (alt) send(Sc.LALT, true)
        if (win) send(Sc.LWIN, true)
        if (shift != ShiftState.OFF) send(Sc.LSHIFT, true)
    }

    fun modsUp() {
        if (shift != ShiftState.OFF) send(Sc.LSHIFT, false)
        if (win) send(Sc.LWIN, false)
        if (alt) send(Sc.LALT, false)
        if (ctrl) send(Sc.LCTRL, false)
        if (shift == ShiftState.ONCE) shift = ShiftState.OFF
        ctrl = false; alt = false; win = false
    }

    fun onPress(def: KeyDef) {
        when (def.kind) {
            Kind.NORMAL -> { modsDown(); send(def.sc, true) }
            else -> Unit
        }
    }

    fun onRepeat(def: KeyDef) = send(def.sc, true)

    fun onRelease(def: KeyDef) {
        when (def.kind) {
            Kind.NORMAL -> { send(def.sc, false); modsUp() }
            Kind.SHIFT -> {
                val now = SystemClock.uptimeMillis()
                shift = when (shift) {
                    ShiftState.OFF -> ShiftState.ONCE
                    ShiftState.ONCE -> if (now - lastShiftTap < 400) ShiftState.LOCK else ShiftState.OFF
                    ShiftState.LOCK -> ShiftState.OFF
                }
                lastShiftTap = now
            }
            Kind.CTRL -> ctrl = !ctrl
            Kind.ALT -> alt = !alt
            Kind.WIN -> {
                // Второй тап по Win без других клавиш — просто открыть «Пуск».
                if (win) { win = false; tap(Sc.LWIN) } else win = true
            }
            Kind.CAPS -> { caps = !caps; tap(Sc.CAPS) }
            Kind.LANG -> {
                ru = !ru
                send(Sc.LALT, true); send(Sc.LSHIFT, true); send(Sc.LSHIFT, false); send(Sc.LALT, false)
            }
            Kind.FN -> fn = !fn
            Kind.HIDE -> onHide()
        }
    }

    fun label(def: KeyDef): String {
        val shifted = shift != ShiftState.OFF
        return when {
            def.kind == Kind.LANG -> if (ru) "РУС" else "ENG"
            def.isLetter -> {
                val up = shifted != caps
                if (ru) (if (up) def.ruShift else def.ru) else (if (up) def.enShift else def.en)
            }
            ru -> if (shifted) def.ruShift else def.ru
            else -> if (shifted) def.enShift else def.en
        }
    }

    fun hint(def: KeyDef): String? {
        if (def.kind != Kind.NORMAL || def.isLetter) return null
        val shifted = if (ru) def.ruShift else def.enShift
        val main = if (ru) def.ru else def.en
        return if (shifted != main && shift == ShiftState.OFF && !(ru && main[0].isLetter())) shifted else null
    }

    fun active(def: KeyDef) = when (def.kind) {
        Kind.SHIFT -> shift != ShiftState.OFF
        Kind.CTRL -> ctrl
        Kind.ALT -> alt
        Kind.WIN -> win
        Kind.CAPS -> caps
        Kind.FN -> fn
        Kind.LANG -> ru
        else -> false
    }

    val screenH = LocalConfiguration.current.screenHeightDp
    val keyH = (screenH * 0.085f).coerceIn(30f, 46f).dp

    Column(
        Modifier
            .fillMaxWidth()
            .background(KbBg)
            .padding(horizontal = 3.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val all = if (fn) listOf(fnRow) + rows else rows
        all.forEach { row ->
            Row(Modifier.fillMaxWidth().height(keyH), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { def ->
                    Key(
                        def = def,
                        label = label(def),
                        hint = hint(def),
                        active = active(def),
                        locked = def.kind == Kind.SHIFT && shift == ShiftState.LOCK,
                        height = keyH,
                        onPress = { onPress(def) },
                        onRepeat = { onRepeat(def) },
                        onRelease = { onRelease(def) },
                    )
                }
            }
        }
    }
}

private val KbBg = Color(0xF0151A22)
private val KeyBg = Color(0xFF262D38)
private val KeyMod = Color(0xFF1D232C)
private val KeyDown = Color(0xFF3B4656)
private val KeyActive = Color(0xFF4C8DFF)

@Composable
private fun RowScope.Key(
    def: KeyDef,
    label: String,
    hint: String?,
    active: Boolean,
    locked: Boolean,
    height: Dp,
    onPress: () -> Unit,
    onRepeat: () -> Unit,
    onRelease: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val press by rememberUpdatedState(onPress)
    val rep by rememberUpdatedState(onRepeat)
    val rel by rememberUpdatedState(onRelease)
    val bg = when {
        active -> KeyActive
        pressed -> KeyDown
        def.kind == Kind.NORMAL && label.length <= 2 -> KeyBg
        else -> KeyMod
    }
    Box(
        Modifier
            .weight(def.w)
            .fillMaxHeight()
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .pointerInput(def) {
                coroutineScope {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        pressed = true
                        press()
                        // Удержание ⌫, стрелок и Del повторяет нажатие, как на ПК.
                        val job = if (def.repeat) launch {
                            delay(400)
                            while (true) { rep(); delay(45) }
                        } else null
                        waitForUpOrCancellation()
                        job?.cancel()
                        pressed = false
                        rel()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (locked) "⇪ Shift" else label,
            color = Color.White,
            fontSize = if (label.length <= 2) (height.value * 0.42f).sp else (height.value * 0.3f).sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
        if (hint != null) {
            Text(
                hint,
                color = Color(0xFF8D99AA),
                fontSize = (height.value * 0.24f).sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 4.dp),
            )
        }
    }
}
