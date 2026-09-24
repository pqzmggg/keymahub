package dev.keymahub.poc.sink

import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.WindowManager
import dev.keymahub.poc.AppLog
import dev.keymahub.poc.a11y.KeymahubAccessibilityService
import dev.keymahub.poc.ime.KeymahubIme
import dev.keymahub.poc.input.Cursor
import dev.keymahub.poc.input.HidKeycodes
import dev.keymahub.poc.ui.CursorOverlay
import dev.keymahub.poc.wire.Wire
import kotlin.math.abs
import kotlin.math.hypot

/**
 * P0-3: no privileged helper. The mouse is emulated with touch gestures that are
 * replayed when the button is released (tap / long-press / drag); keys go through the
 * keymahub IME when it is the active keyboard.
 */
class AccessibilitySink : InputSink {
    private var service: KeymahubAccessibilityService? = null
    private var overlay: CursorOverlay? = null
    private val cursor = Cursor(1, 1)
    private var modifiers = 0
    private val keyDownTime = HashMap<Int, Long>()
    private var warnedNoIme = false

    // Left-button gesture being recorded.
    private var pressAt = 0L
    private var path: Path? = null
    private var startX = 0f
    private var startY = 0f
    private var travelled = 0f
    private var points = 0

    override fun start(): String? {
        val s = KeymahubAccessibilityService.instance
            ?: return "접근성 서비스 'keymahub'를 켜 주세요 (설정 → 접근성)"
        service = s
        overlay = CursorOverlay(s, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        if (KeymahubIme.instance == null) AppLog.i("keymahub IME is not the active keyboard: only Esc/Win keys will work")
        return null
    }

    override fun stop() {
        releaseAll()
        overlay?.hide()
    }

    override fun enter() {
        val s = service ?: return
        val (w, h) = displaySize(s)
        cursor.resize(w, h)
        overlay?.show()
        overlay?.moveTo(cursor.x, cursor.y)
    }

    override fun leave() {
        overlay?.hide()
    }

    override fun move(dx: Int, dy: Int) {
        val (px, py) = cursor.x to cursor.y
        cursor.move(dx, dy)
        overlay?.moveTo(cursor.x, cursor.y)
        path?.let {
            travelled += hypot(cursor.x - px, cursor.y - py)
            // Keep the replayed path short; gestures have a stroke-length budget.
            if (points < MAX_POINTS) {
                it.lineTo(cursor.x, cursor.y)
                points++
            }
        }
    }

    override fun button(button: Int, down: Boolean) {
        val s = service ?: return
        when (button) {
            Wire.BUTTON_LEFT -> if (down) beginPress() else endPress(s)
            Wire.BUTTON_RIGHT, Wire.BUTTON_BACK -> if (down) s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Wire.BUTTON_MIDDLE -> if (down) s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        }
    }

    private fun beginPress() {
        pressAt = SystemClock.uptimeMillis()
        startX = cursor.x
        startY = cursor.y
        travelled = 0f
        points = 0
        path = Path().apply { moveTo(cursor.x, cursor.y) }
    }

    private fun endPress(s: KeymahubAccessibilityService) {
        val p = path ?: return
        path = null
        val held = SystemClock.uptimeMillis() - pressAt
        if (travelled < TAP_SLOP) {
            // Tap or long-press in place.
            s.gesture(Path().apply { moveTo(startX, startY) }, if (held < LONG_PRESS_MS) 50 else held)
        } else {
            s.gesture(p, held.coerceIn(100, 3000))
        }
    }

    override fun wheel(v: Int, h: Int) {
        val s = service ?: return
        // Wheel "up" (v > 0) shows earlier content: the finger moves down.
        val dy = SWIPE_PER_NOTCH * v / 120f
        val dx = -SWIPE_PER_NOTCH * h / 120f
        if (abs(dx) < 1 && abs(dy) < 1) return
        val x2 = (cursor.x + dx).coerceIn(0f, cursor.width - 1f)
        val y2 = (cursor.y + dy).coerceIn(0f, cursor.height - 1f)
        s.gesture(Path().apply { moveTo(cursor.x, cursor.y); lineTo(x2, y2) }, 120)
    }

    override fun key(usage: Int, down: Boolean, repeat: Boolean) {
        if (HidKeycodes.isModifier(usage)) {
            val bit = 1 shl (usage - 0xE0)
            modifiers = if (down) modifiers or bit else modifiers and bit.inv()
        }
        val code = HidKeycodes.toKeycode(usage) ?: return
        val ime = KeymahubIme.instance
        if (ime == null) {
            fallbackKey(code, down)
            return
        }
        val now = SystemClock.uptimeMillis()
        val downAt = if (down) keyDownTime.getOrPut(code) { now } else keyDownTime.remove(code) ?: now
        ime.send(
            KeyEvent(
                downAt, now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code,
                if (repeat) 1 else 0, HidKeycodes.metaState(modifiers),
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD, InputDevice.SOURCE_KEYBOARD,
            )
        )
    }

    private fun fallbackKey(code: Int, down: Boolean) {
        val s = service ?: return
        if (!down) return
        when (code) {
            KeyEvent.KEYCODE_ESCAPE -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            else -> if (!warnedNoIme) {
                warnedNoIme = true
                AppLog.i("key dropped: select 'keymahub' as the keyboard to type")
            }
        }
    }

    override fun releaseAll() {
        val ime = KeymahubIme.instance
        val now = SystemClock.uptimeMillis()
        for ((code, downAt) in keyDownTime) {
            ime?.send(KeyEvent(downAt, now, KeyEvent.ACTION_UP, code, 0, 0))
        }
        keyDownTime.clear()
        modifiers = 0
        path = null
    }

    private companion object {
        const val TAP_SLOP = 12f
        const val LONG_PRESS_MS = 450L
        const val SWIPE_PER_NOTCH = 180f
        const val MAX_POINTS = 120
    }
}
