package com.matyankass.nemulator

import android.view.KeyEvent

/**
 * Скан-коды клавиш ПК (XT, набор 1). QEMU получает их через расширенное
 * событие клавиатуры VNC — то есть «физическую» клавишу, а какую букву она
 * напечатает, решает раскладка внутри Windows. Поэтому русский работает
 * сам: включил РУС в Windows — те же клавиши печатают кириллицу.
 * Расширенные клавиши (с префиксом E0) кодируются как 0x80 | код.
 */
object Sc {
    const val ESC = 0x01
    const val N1 = 0x02; const val N2 = 0x03; const val N3 = 0x04; const val N4 = 0x05; const val N5 = 0x06
    const val N6 = 0x07; const val N7 = 0x08; const val N8 = 0x09; const val N9 = 0x0A; const val N0 = 0x0B
    const val MINUS = 0x0C; const val EQUAL = 0x0D; const val BACKSPACE = 0x0E; const val TAB = 0x0F
    const val Q = 0x10; const val W = 0x11; const val E = 0x12; const val R = 0x13; const val T = 0x14
    const val Y = 0x15; const val U = 0x16; const val I = 0x17; const val O = 0x18; const val P = 0x19
    const val LBRACKET = 0x1A; const val RBRACKET = 0x1B; const val ENTER = 0x1C; const val LCTRL = 0x1D
    const val A = 0x1E; const val S = 0x1F; const val D = 0x20; const val F = 0x21; const val G = 0x22
    const val H = 0x23; const val J = 0x24; const val K = 0x25; const val L = 0x26
    const val SEMICOLON = 0x27; const val QUOTE = 0x28; const val GRAVE = 0x29; const val LSHIFT = 0x2A
    const val BACKSLASH = 0x2B
    const val Z = 0x2C; const val X = 0x2D; const val C = 0x2E; const val V = 0x2F; const val B = 0x30
    const val N = 0x31; const val M = 0x32
    const val COMMA = 0x33; const val DOT = 0x34; const val SLASH = 0x35; const val RSHIFT = 0x36
    const val KP_MULTIPLY = 0x37; const val LALT = 0x38; const val SPACE = 0x39; const val CAPS = 0x3A
    const val F1 = 0x3B; const val F2 = 0x3C; const val F3 = 0x3D; const val F4 = 0x3E; const val F5 = 0x3F
    const val F6 = 0x40; const val F7 = 0x41; const val F8 = 0x42; const val F9 = 0x43; const val F10 = 0x44
    const val NUMLOCK = 0x45; const val SCROLLLOCK = 0x46
    const val KP7 = 0x47; const val KP8 = 0x48; const val KP9 = 0x49; const val KP_MINUS = 0x4A
    const val KP4 = 0x4B; const val KP5 = 0x4C; const val KP6 = 0x4D; const val KP_PLUS = 0x4E
    const val KP1 = 0x4F; const val KP2 = 0x50; const val KP3 = 0x51; const val KP0 = 0x52; const val KP_DOT = 0x53
    const val F11 = 0x57; const val F12 = 0x58

    const val KP_ENTER = 0x9C; const val RCTRL = 0x9D; const val KP_DIVIDE = 0xB5; const val PRINT = 0xB7
    const val RALT = 0xB8; const val PAUSE = 0xC6; const val HOME = 0xC7; const val UP = 0xC8; const val PGUP = 0xC9
    const val LEFT = 0xCB; const val RIGHT = 0xCD; const val END = 0xCF; const val DOWN = 0xD0
    const val PGDN = 0xD1; const val INSERT = 0xD2; const val DELETE = 0xD3
    const val LWIN = 0xDB; const val RWIN = 0xDC; const val MENU = 0xDD

    /** Клавиша внешней (Bluetooth/USB) клавиатуры → скан-код, или 0. */
    fun fromAndroid(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_ESCAPE -> ESC
        KeyEvent.KEYCODE_1 -> N1; KeyEvent.KEYCODE_2 -> N2; KeyEvent.KEYCODE_3 -> N3
        KeyEvent.KEYCODE_4 -> N4; KeyEvent.KEYCODE_5 -> N5; KeyEvent.KEYCODE_6 -> N6
        KeyEvent.KEYCODE_7 -> N7; KeyEvent.KEYCODE_8 -> N8; KeyEvent.KEYCODE_9 -> N9
        KeyEvent.KEYCODE_0 -> N0
        KeyEvent.KEYCODE_MINUS -> MINUS; KeyEvent.KEYCODE_EQUALS -> EQUAL
        KeyEvent.KEYCODE_DEL -> BACKSPACE; KeyEvent.KEYCODE_TAB -> TAB
        KeyEvent.KEYCODE_Q -> Q; KeyEvent.KEYCODE_W -> W; KeyEvent.KEYCODE_E -> E
        KeyEvent.KEYCODE_R -> R; KeyEvent.KEYCODE_T -> T; KeyEvent.KEYCODE_Y -> Y
        KeyEvent.KEYCODE_U -> U; KeyEvent.KEYCODE_I -> I; KeyEvent.KEYCODE_O -> O
        KeyEvent.KEYCODE_P -> P
        KeyEvent.KEYCODE_LEFT_BRACKET -> LBRACKET; KeyEvent.KEYCODE_RIGHT_BRACKET -> RBRACKET
        KeyEvent.KEYCODE_ENTER -> ENTER; KeyEvent.KEYCODE_CTRL_LEFT -> LCTRL
        KeyEvent.KEYCODE_A -> A; KeyEvent.KEYCODE_S -> S; KeyEvent.KEYCODE_D -> D
        KeyEvent.KEYCODE_F -> F; KeyEvent.KEYCODE_G -> G; KeyEvent.KEYCODE_H -> H
        KeyEvent.KEYCODE_J -> J; KeyEvent.KEYCODE_K -> K; KeyEvent.KEYCODE_L -> L
        KeyEvent.KEYCODE_SEMICOLON -> SEMICOLON; KeyEvent.KEYCODE_APOSTROPHE -> QUOTE
        KeyEvent.KEYCODE_GRAVE -> GRAVE; KeyEvent.KEYCODE_SHIFT_LEFT -> LSHIFT
        KeyEvent.KEYCODE_BACKSLASH -> BACKSLASH
        KeyEvent.KEYCODE_Z -> Z; KeyEvent.KEYCODE_X -> X; KeyEvent.KEYCODE_C -> C
        KeyEvent.KEYCODE_V -> V; KeyEvent.KEYCODE_B -> B; KeyEvent.KEYCODE_N -> N
        KeyEvent.KEYCODE_M -> M
        KeyEvent.KEYCODE_COMMA -> COMMA; KeyEvent.KEYCODE_PERIOD -> DOT; KeyEvent.KEYCODE_SLASH -> SLASH
        KeyEvent.KEYCODE_SHIFT_RIGHT -> RSHIFT; KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> KP_MULTIPLY
        KeyEvent.KEYCODE_ALT_LEFT -> LALT; KeyEvent.KEYCODE_SPACE -> SPACE; KeyEvent.KEYCODE_CAPS_LOCK -> CAPS
        KeyEvent.KEYCODE_F1 -> F1; KeyEvent.KEYCODE_F2 -> F2; KeyEvent.KEYCODE_F3 -> F3
        KeyEvent.KEYCODE_F4 -> F4; KeyEvent.KEYCODE_F5 -> F5; KeyEvent.KEYCODE_F6 -> F6
        KeyEvent.KEYCODE_F7 -> F7; KeyEvent.KEYCODE_F8 -> F8; KeyEvent.KEYCODE_F9 -> F9
        KeyEvent.KEYCODE_F10 -> F10; KeyEvent.KEYCODE_F11 -> F11; KeyEvent.KEYCODE_F12 -> F12
        KeyEvent.KEYCODE_NUM_LOCK -> NUMLOCK; KeyEvent.KEYCODE_SCROLL_LOCK -> SCROLLLOCK
        KeyEvent.KEYCODE_NUMPAD_7 -> KP7; KeyEvent.KEYCODE_NUMPAD_8 -> KP8; KeyEvent.KEYCODE_NUMPAD_9 -> KP9
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> KP_MINUS
        KeyEvent.KEYCODE_NUMPAD_4 -> KP4; KeyEvent.KEYCODE_NUMPAD_5 -> KP5; KeyEvent.KEYCODE_NUMPAD_6 -> KP6
        KeyEvent.KEYCODE_NUMPAD_ADD -> KP_PLUS
        KeyEvent.KEYCODE_NUMPAD_1 -> KP1; KeyEvent.KEYCODE_NUMPAD_2 -> KP2; KeyEvent.KEYCODE_NUMPAD_3 -> KP3
        KeyEvent.KEYCODE_NUMPAD_0 -> KP0; KeyEvent.KEYCODE_NUMPAD_DOT -> KP_DOT
        KeyEvent.KEYCODE_NUMPAD_ENTER -> KP_ENTER; KeyEvent.KEYCODE_CTRL_RIGHT -> RCTRL
        KeyEvent.KEYCODE_NUMPAD_DIVIDE -> KP_DIVIDE; KeyEvent.KEYCODE_SYSRQ -> PRINT
        KeyEvent.KEYCODE_ALT_RIGHT -> RALT; KeyEvent.KEYCODE_BREAK -> PAUSE
        KeyEvent.KEYCODE_MOVE_HOME -> HOME; KeyEvent.KEYCODE_DPAD_UP -> UP; KeyEvent.KEYCODE_PAGE_UP -> PGUP
        KeyEvent.KEYCODE_DPAD_LEFT -> LEFT; KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
        KeyEvent.KEYCODE_MOVE_END -> END; KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
        KeyEvent.KEYCODE_PAGE_DOWN -> PGDN; KeyEvent.KEYCODE_INSERT -> INSERT
        KeyEvent.KEYCODE_FORWARD_DEL -> DELETE
        KeyEvent.KEYCODE_META_LEFT -> LWIN; KeyEvent.KEYCODE_META_RIGHT -> RWIN
        KeyEvent.KEYCODE_MENU -> MENU
        else -> 0
    }
}
