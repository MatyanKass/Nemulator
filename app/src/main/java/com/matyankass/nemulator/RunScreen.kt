package com.matyankass.nemulator

import android.graphics.Rect
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/** Экран работающего ПК: Windows, меню из угла и клавиатура. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RunScreen(state: VmState, onMinimize: () -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var keyboard by rememberSaveable { mutableStateOf(false) }
    var pointerMode by rememberSaveable { mutableStateOf(PointerMode.TOUCHPAD) }
    var scaleMode by rememberSaveable { mutableStateOf(ScaleMode.FIT) }
    var mic by rememberSaveable { mutableStateOf(true) }
    var hint by rememberSaveable { mutableStateOf(true) }
    var confirmOff by remember { mutableStateOf(false) }

    BackHandler { menu = !menu }
    LaunchedEffect(Unit) {
        delay(7000)
        hint = false
    }

    fun combo(vararg sc: Int) {
        val rfb = VmManager.rfb ?: return
        sc.forEach { rfb.key(it, true) }
        sc.reversed().forEach { rfb.key(it, false) }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    VmScreenView(c).apply {
                        onCornerSwipe = { menu = true; hint = false }
                        VmManager.onFrame = { postInvalidateOnAnimation() }
                        // Жест «назад» с левого края не должен мешать свайпу из угла.
                        if (android.os.Build.VERSION.SDK_INT >= 29) addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                            val d = v.resources.displayMetrics.density
                            v.systemGestureExclusionRects =
                                listOf(Rect(0, v.height - (200 * d).toInt(), (64 * d).toInt(), v.height))
                        }
                    }
                },
                update = { v ->
                    v.pointerMode = pointerMode
                    v.scaleMode = scaleMode
                },
            )
            DisposableEffect(Unit) {
                onDispose { VmManager.onFrame = null }
            }

            if (state is VmState.Starting) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = Accent)
                    Spacer(Modifier.height(16.dp))
                    Text(state.message, color = Color.White, fontSize = 18.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Первый экран может появиться не сразу —\nпроцессор телефона изображает процессор ПК",
                        color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                    )
                }
            }

            AnimatedVisibility(
                hint && state is VmState.Running,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 44.dp, bottom = 40.dp),
                enter = fadeIn(), exit = fadeOut(),
            ) {
                Text(
                    "↗ Свайп из этого угла — меню",
                    color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xCC1B2230))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            if (menu) {
                Box(
                    Modifier.fillMaxSize().background(Color(0x66000000))
                        .clickable(remember { MutableInteractionSource() }, null) { menu = false },
                )
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .widthIn(max = 460.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(CardBg)
                        .clickable(remember { MutableInteractionSource() }, null) {}
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                ) {
                    Text(
                        (state as? VmState.Running)?.machine?.name ?: "Nemulator",
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MenuButton("⌨", if (keyboard) "Скрыть клавиатуру" else "Клавиатура", keyboard) {
                            keyboard = !keyboard; menu = false
                        }
                        MenuButton("🖱", "Режим: ${pointerMode.title}") {
                            pointerMode = if (pointerMode == PointerMode.TOUCHPAD) PointerMode.DIRECT else PointerMode.TOUCHPAD
                        }
                        MenuButton("⊞", "Пуск") { combo(Sc.LWIN); menu = false }
                        MenuButton("⎋", "Esc") { combo(Sc.ESC) }
                        MenuButton("⇆", "Alt+Tab") { combo(Sc.LALT, Sc.TAB) }
                        MenuButton("⚠", "Ctrl+Alt+Del") { combo(Sc.LCTRL, Sc.LALT, Sc.DELETE); menu = false }
                        MenuButton("⛶", "Экран: ${scaleMode.title}") {
                            scaleMode = if (scaleMode == ScaleMode.FIT) ScaleMode.STRETCH else ScaleMode.FIT
                        }
                        MenuButton(if (mic) "🎤" else "🔇", if (mic) "Микрофон вкл" else "Микрофон выкл", mic) {
                            mic = !mic
                            VmManager.audio?.micEnabled = mic
                        }
                        MenuButton("↩", "Свернуть") { menu = false; onMinimize() }
                        MenuButton("↻", "Перезагрузить") { VmManager.reset(ctx); menu = false }
                        MenuButton("⏻", "Выключить", danger = true) { confirmOff = true }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Тачпад: тап — клик, долгое нажатие или тап двумя пальцами — правый клик, " +
                            "два пальца — прокрутка, двойной тап с удержанием — перетащить.",
                        color = Muted, fontSize = 12.sp,
                    )
                }
            }

            if (confirmOff) {
                Box(
                    Modifier.fillMaxSize().background(Color(0x99000000))
                        .clickable(remember { MutableInteractionSource() }, null) { confirmOff = false },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier.padding(24.dp).widthIn(max = 420.dp).clip(RoundedCornerShape(18.dp))
                            .background(CardBg).padding(18.dp),
                    ) {
                        Text("Выключить ПК?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "«Завершить работу» — как кнопка питания: Windows сохранит всё и выключится сама.\n" +
                                "«Выдернуть шнур» — мгновенно, но несохранённое пропадёт.",
                            color = Muted, fontSize = 14.sp,
                        )
                        Spacer(Modifier.height(14.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MenuButton("⏻", "Завершить работу") {
                                VmManager.powerButton(ctx); confirmOff = false; menu = false
                            }
                            MenuButton("✕", "Выдернуть шнур", danger = true) {
                                VmManager.forceStop(ctx); confirmOff = false; menu = false
                            }
                            MenuButton("", "Отмена") { confirmOff = false }
                        }
                    }
                }
            }
        }

        if (keyboard) {
            PcKeyboard(
                send = { sc, down -> VmManager.rfb?.key(sc, down) },
                onHide = { keyboard = false },
            )
        }
    }
}

@Composable
private fun MenuButton(
    icon: String,
    title: String,
    active: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .width(100.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) Accent.copy(alpha = 0.25f) else Color(0xFF232B37))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon.isNotEmpty()) Text(icon, fontSize = 20.sp, color = if (danger) Bad else Color.White)
        Text(
            title,
            color = if (danger) Bad else Color.White,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.size(width = 92.dp, height = 32.dp).padding(top = 2.dp),
        )
    }
}
