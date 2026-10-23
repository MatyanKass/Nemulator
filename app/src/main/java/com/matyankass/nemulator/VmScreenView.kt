package com.matyankass.nemulator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

enum class PointerMode(val title: String) {
    TOUCHPAD("Тачпад"),
    DIRECT("Касание"),
}

enum class ScaleMode(val title: String) {
    FIT("Вписать"),
    STRETCH("Растянуть"),
}

/**
 * Экран Windows + всё управление пальцами.
 *
 * Тачпад: палец двигает курсор, тап — левый клик, двойной тап с
 * удержанием — перетаскивание, долгое нажатие или тап двумя пальцами —
 * правый клик, два пальца вверх/вниз — прокрутка.
 * Касание: курсор прыгает под палец, тап — клик, ведение — перетаскивание.
 * Свайп из левого нижнего угла открывает меню Nemulator.
 */
@SuppressLint("ViewConstructor")
class VmScreenView(context: Context) : View(context) {
    var pointerMode = PointerMode.TOUCHPAD
    var scaleMode = ScaleMode.FIT
        set(value) { field = value; invalidate() }
    var onCornerSwipe: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        strokeCap = Paint.Cap.ROUND
    }
    private val hintPath = Path()
    private val dst = RectF()

    // Курсор в координатах Windows.
    private var curX = 0f
    private var curY = 0f
    private var buttons = 0

    // Состояние жеста.
    private var corner = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downAt = 0L
    private var moved = false
    private var maxPointers = 1
    private var longFired = false
    private var scrollY = 0f
    private var lastTapUp = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var dragArmed = false

    private val slop = 10 * density
    // Зона чуть выше самого края: нижний край занят жестом «домой» Android.
    private val cornerZone = 88 * density

    private val longPress = Runnable {
        if (!moved && maxPointers == 1) {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (pointerMode == PointerMode.DIRECT) moveTo(downX, downY)
            click(RIGHT)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // ---- рисование ----

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val rfb = VmManager.rfb
        val bmp = rfb?.bitmap
        if (rfb != null && bmp != null) {
            synchronized(rfb.frameLock) {
                layoutFrame(bmp.width, bmp.height)
                canvas.drawBitmap(bmp, null, dst, paint)
            }
        }
        // Подсказка, откуда тянуть меню.
        val h = height.toFloat()
        val r = 22 * density
        hintPath.reset()
        hintPath.moveTo(6 * density, h - r - 6 * density)
        hintPath.quadTo(6 * density, h - 6 * density, r + 6 * density, h - 6 * density)
        canvas.drawPath(hintPath, hintPaint)
    }

    private fun layoutFrame(fw: Int, fh: Int) {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (scaleMode == ScaleMode.STRETCH) {
            dst.set(0f, 0f, vw, vh)
        } else {
            val s = min(vw / fw, vh / fh)
            val w = fw * s
            val h = fh * s
            dst.set((vw - w) / 2, (vh - h) / 2, (vw + w) / 2, (vh + h) / 2)
        }
    }

    private fun guestW() = VmManager.rfb?.width ?: 0
    private fun guestH() = VmManager.rfb?.height ?: 0

    private fun toGuestX(x: Float) = ((x - dst.left) / dst.width() * guestW()).coerceIn(0f, guestW() - 1f)
    private fun toGuestY(y: Float) = ((y - dst.top) / dst.height() * guestH()).coerceIn(0f, guestH() - 1f)

    // ---- отправка ----

    private fun sendPointer() {
        VmManager.rfb?.pointer(buttons, curX.toInt(), curY.toInt())
    }

    private fun moveTo(viewX: Float, viewY: Float) {
        if (dst.width() <= 0f) return
        curX = toGuestX(viewX)
        curY = toGuestY(viewY)
        sendPointer()
    }

    private fun press(b: Int) { buttons = buttons or b; sendPointer() }
    private fun release(b: Int) { buttons = buttons and b.inv(); sendPointer() }
    private fun click(b: Int) { press(b); release(b) }

    private fun wheel(down: Boolean) {
        val b = if (down) WHEEL_DOWN else WHEEL_UP
        click(b)
    }

    // ---- пальцы ----

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) return mouse(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                corner = e.x < cornerZone * 0.75f && e.y > height - cornerZone
                if (corner) return true
                downAt = SystemClock.uptimeMillis()
                moved = false; longFired = false; maxPointers = 1; scrollY = 0f
                when (pointerMode) {
                    PointerMode.DIRECT -> moveTo(e.x, e.y)
                    PointerMode.TOUCHPAD -> {
                        // Двойной тап с удержанием — тащим с зажатой левой кнопкой.
                        dragArmed = downAt - lastTapUp < 300 &&
                            hypot(e.x - lastTapX, e.y - lastTapY) < 40 * density
                        if (dragArmed) press(LEFT)
                    }
                }
                if (!dragArmed) postDelayed(longPress, 500)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (corner) return true
                maxPointers = maxOf(maxPointers, e.pointerCount)
                removeCallbacks(longPress)
                if (buttons and LEFT != 0) release(LEFT)
                scrollY = avgY(e)
            }

            MotionEvent.ACTION_MOVE -> {
                if (corner) {
                    if (downDistanceCorner(e) > 36 * density) {
                        corner = false
                        cornerDone = true
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onCornerSwipe()
                    }
                    return true
                }
                if (cornerDone) return true
                if (e.pointerCount >= 2) {
                    // Прокрутка двумя пальцами: палец вверх — страница вниз, как на телефоне.
                    val y = avgY(e)
                    val step = 28 * density
                    while (y - scrollY > step) { wheel(down = false); scrollY += step; moved = true }
                    while (scrollY - y > step) { wheel(down = true); scrollY -= step; moved = true }
                    return true
                }
                if (!moved && hypot(e.x - downX, e.y - downY) > slop) {
                    moved = true
                    removeCallbacks(longPress)
                    if (pointerMode == PointerMode.DIRECT && !longFired && maxPointers == 1) press(LEFT)
                }
                if (moved && maxPointers == 1) {
                    when (pointerMode) {
                        PointerMode.DIRECT -> moveTo(e.x, e.y)
                        PointerMode.TOUCHPAD -> {
                            val dx = e.x - lastX
                            val dy = e.y - lastY
                            // Чем быстрее палец, тем дальше курсор — как на ноутбуке.
                            val base = if (dst.width() > 0) guestW() / dst.width() else 1f
                            val accel = 1.2f + min(hypot(dx, dy) / (12 * density), 2f)
                            curX = (curX + dx * base * accel).coerceIn(0f, guestW() - 1f)
                            curY = (curY + dy * base * accel).coerceIn(0f, guestH() - 1f)
                            sendPointer()
                        }
                    }
                }
                lastX = e.x; lastY = e.y
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (corner || cornerDone) {
                    corner = false; cornerDone = false
                    return true
                }
                val quick = SystemClock.uptimeMillis() - downAt < 300
                when {
                    maxPointers >= 2 -> if (!moved && quick) click(RIGHT)
                    longFired -> Unit
                    dragArmed -> release(LEFT)
                    !moved -> {
                        if (pointerMode == PointerMode.DIRECT) moveTo(e.x, e.y)
                        click(LEFT)
                        lastTapUp = SystemClock.uptimeMillis()
                        lastTapX = e.x; lastTapY = e.y
                    }
                }
                if (buttons and (LEFT or RIGHT) != 0) {
                    buttons = 0
                    sendPointer()
                }
                dragArmed = false
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                corner = false; cornerDone = false; dragArmed = false
                if (buttons != 0) { buttons = 0; sendPointer() }
            }
        }
        return true
    }

    private var cornerDone = false

    /** От угла тянут вверх или вправо. */
    private fun downDistanceCorner(e: MotionEvent): Float = maxOf(downY - e.y, e.x - downX)

    private fun avgY(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getY(i)
        return s / e.pointerCount
    }

    // ---- внешняя мышь ----

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (!e.isFromSource(InputDevice.SOURCE_MOUSE) && e.getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE) {
            return super.onGenericMotionEvent(e)
        }
        return mouse(e)
    }

    private fun mouse(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (abs(v) > 0.01f) wheel(down = v < 0)
                return true
            }
        }
        var b = 0
        val s = e.buttonState
        if (s and MotionEvent.BUTTON_PRIMARY != 0) b = b or LEFT
        if (s and MotionEvent.BUTTON_TERTIARY != 0) b = b or MIDDLE
        if (s and MotionEvent.BUTTON_SECONDARY != 0) b = b or RIGHT
        buttons = b
        moveTo(e.x, e.y)
        return true
    }

    companion object {
        const val LEFT = 1
        const val MIDDLE = 2
        const val RIGHT = 4
        const val WHEEL_UP = 8
        const val WHEEL_DOWN = 16
    }
}
