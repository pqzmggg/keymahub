package com.keymahub.core

/**
 * Where routed input goes. Usages are HID keyboard usages (page 0x07), buttons are
 * [Buttons] values, the wheel is in 1/120 notches (positive = up / right).
 */
interface InputSink {
    fun enter() {}
    fun leave() {}
    fun key(usage: Int, down: Boolean)
    fun move(dx: Int, dy: Int)
    fun button(button: Int, down: Boolean)
    fun wheel(v: Int, h: Int)
    fun releaseAll()
}

object Buttons {
    const val LEFT = 1
    const val RIGHT = 2
    const val MIDDLE = 3
    const val BACK = 4
    const val FORWARD = 5
    const val WHEEL_NOTCH = 120
}
