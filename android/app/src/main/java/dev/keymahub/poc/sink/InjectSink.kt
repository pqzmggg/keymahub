package dev.keymahub.poc.sink

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import dev.keymahub.poc.AppLog
import dev.keymahub.poc.input.Cursor
import dev.keymahub.poc.input.HidKeycodes
import dev.keymahub.poc.priv.PrivClient
import dev.keymahub.poc.ui.CursorOverlay
import dev.keymahub.poc.wire.Wire

/**
 * P0-2b: InputManager.injectInputEvent through the Shizuku process. Keys become real
 * KeyEvents; the mouse becomes SOURCE_MOUSE MotionEvents at the receiver-owned cursor,
 * which we draw ourselves (injected events do not move the system pointer).
 */
class InjectSink(private val context: Context) : InputSink {
    private val cursor = Cursor(1, 1)
    private val overlay = CursorOverlay(context, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    private var modifiers = 0
    private val keyDown = HashMap<Int, Pair<Long, Int>>() // keycode -> (downTime, repeatCount)
    private var buttonState = 0
    private var downTime = 0L

    override fun start(): String? {
        if (!Settings.canDrawOverlays(context)) return "'다른 앱 위에 표시' 권한이 필요합니다 (커서 표시용)"
        if (!PrivClient.connect()) return "Shizuku: ${PrivClient.shizukuState()}"
        AppLog.i("privileged: ${PrivClient.info()}")
        return null
    }

    override fun stop() {
        releaseAll()
        overlay.hide()
    }

    override fun enter() {
        val size = displaySize(context)
        cursor.resize(size.first, size.second)
        overlay.show()
        overlay.moveTo(cursor.x, cursor.y)
    }

    override fun leave() {
        overlay.hide()
    }

    override fun key(usage: Int, down: Boolean, repeat: Boolean) {
        if (HidKeycodes.isModifier(usage)) {
            val bit = 1 shl (usage - 0xE0)
            modifiers = if (down) modifiers or bit else modifiers and bit.inv()
        }
        val code = HidKeycodes.toKeycode(usage) ?: return AppLog.i("no Android keycode for usage 0x%02X".format(usage))
        injectKey(code, down)
    }

    private fun injectKey(code: Int, down: Boolean) {
        val now = SystemClock.uptimeMillis()
        val (keyDownTime, count) = when {
            down -> keyDown[code]?.let { it.first to it.second + 1 } ?: (now to 0)
            else -> keyDown.remove(code) ?: return
        }
        if (down) keyDown[code] = keyDownTime to count
        val ev = KeyEvent(
            keyDownTime, now,
            if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            code, if (down) count else 0,
            HidKeycodes.metaState(modifiers),
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
            InputDevice.SOURCE_KEYBOARD,
        )
        PrivClient.inject(ev)
    }

    override fun move(dx: Int, dy: Int) {
        cursor.move(dx, dy)
        overlay.moveTo(cursor.x, cursor.y)
        motion(if (buttonState == 0) MotionEvent.ACTION_HOVER_MOVE else MotionEvent.ACTION_MOVE)
    }

    override fun button(button: Int, down: Boolean) {
        // Real mice get BACK/FORWARD synthesized as keys by InputReader; injected ones do not.
        when (button) {
            Wire.BUTTON_BACK -> return injectKey(KeyEvent.KEYCODE_BACK, down)
            Wire.BUTTON_FORWARD -> return injectKey(KeyEvent.KEYCODE_FORWARD, down)
        }
        val bit = when (button) {
            Wire.BUTTON_LEFT -> MotionEvent.BUTTON_PRIMARY
            Wire.BUTTON_RIGHT -> MotionEvent.BUTTON_SECONDARY
            else -> MotionEvent.BUTTON_TERTIARY
        }
        if (down) {
            if (buttonState and bit != 0) return
            val first = buttonState == 0
            buttonState = buttonState or bit
            if (first) {
                downTime = SystemClock.uptimeMillis()
                motion(MotionEvent.ACTION_DOWN)
            }
            motion(MotionEvent.ACTION_BUTTON_PRESS, actionButton = bit)
        } else {
            if (buttonState and bit == 0) return
            buttonState = buttonState and bit.inv()
            motion(MotionEvent.ACTION_BUTTON_RELEASE, actionButton = bit)
            if (buttonState == 0) {
                motion(MotionEvent.ACTION_UP)
                motion(MotionEvent.ACTION_HOVER_MOVE)
            }
        }
    }

    override fun wheel(v: Int, h: Int) {
        motion(MotionEvent.ACTION_SCROLL, vscroll = v / 120f, hscroll = h / 120f)
    }

    override fun releaseAll() {
        for (code in keyDown.keys.toList()) injectKey(code, false)
        modifiers = 0
        for (b in listOf(Wire.BUTTON_LEFT, Wire.BUTTON_RIGHT, Wire.BUTTON_MIDDLE)) button(b, false)
    }

    private fun motion(action: Int, actionButton: Int = 0, vscroll: Float = 0f, hscroll: Float = 0f) {
        val now = SystemClock.uptimeMillis()
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coords = MotionEvent.PointerCoords().apply {
            x = cursor.x
            y = cursor.y
            pressure = if (buttonState != 0) 1f else 0f
            size = 1f
            if (vscroll != 0f) setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll)
            if (hscroll != 0f) setAxisValue(MotionEvent.AXIS_HSCROLL, hscroll)
        }
        val ev = MotionEvent.obtain(
            if (buttonState != 0 || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_BUTTON_RELEASE) downTime else now,
            now, action, 1, arrayOf(props), arrayOf(coords),
            HidKeycodes.metaState(modifiers), buttonState, 1f, 1f, 0, 0,
            InputDevice.SOURCE_MOUSE, 0,
        )
        if (actionButton != 0) setActionButton?.invoke(ev, actionButton)
        PrivClient.inject(ev)
        ev.recycle()
    }
}

/** `MotionEvent.setActionButton` is hidden in the public SDK (scrcpy calls it the same way). */
private val setActionButton: java.lang.reflect.Method? by lazy {
    runCatching { MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType) }.getOrNull()
}

/** Real display size in pixels, including system bars. */
fun displaySize(context: Context): Pair<Int, Int> {
    val wm = context.getSystemService(WindowManager::class.java)!!
    return if (android.os.Build.VERSION.SDK_INT >= 30) {
        val b = wm.maximumWindowMetrics.bounds
        b.width() to b.height()
    } else {
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        m.widthPixels to m.heightPixels
    }
}
