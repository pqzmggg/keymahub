package dev.keymahub.poc.sink

import dev.keymahub.poc.AppLog
import dev.keymahub.poc.input.KeyboardReport
import dev.keymahub.poc.input.MouseReport
import dev.keymahub.poc.priv.PrivClient
import dev.keymahub.poc.priv.PrivProtocol

/**
 * P0-2a: virtual USB-like keyboard + mouse through /dev/uhid (via Shizuku).
 * Android draws its own pointer and applies its own key repeat, acceleration and
 * physical keyboard layout.
 */
class UhidSink : InputSink {
    private val keyboard = KeyboardReport()
    private val mouse = MouseReport()

    override fun start(): String? {
        if (!PrivClient.connect()) return "Shizuku: ${PrivClient.shizukuState()}"
        AppLog.i("privileged: ${PrivClient.info()}")
        PrivClient.uhidCreate()?.let { return "UHID create failed: $it" }
        AppLog.i("UHID keyboard + mouse created")
        return null
    }

    override fun stop() {
        releaseAll()
        PrivClient.uhidDestroy()
    }

    override fun key(usage: Int, down: Boolean, repeat: Boolean) {
        // A held HID key repeats on its own; forwarding host repeats would double them.
        if (repeat) return
        keyboard.update(usage, down)?.let { PrivClient.uhidInput(PrivProtocol.DEVICE_KEYBOARD, it) }
    }

    override fun move(dx: Int, dy: Int) {
        for (r in mouse.move(dx, dy)) PrivClient.uhidInput(PrivProtocol.DEVICE_MOUSE, r)
    }

    override fun button(button: Int, down: Boolean) {
        mouse.setButton(button, down)?.let { PrivClient.uhidInput(PrivProtocol.DEVICE_MOUSE, it) }
    }

    override fun wheel(v: Int, h: Int) {
        mouse.wheel(v, h)?.let { PrivClient.uhidInput(PrivProtocol.DEVICE_MOUSE, it) }
    }

    override fun releaseAll() {
        PrivClient.uhidInput(PrivProtocol.DEVICE_KEYBOARD, keyboard.clear())
        PrivClient.uhidInput(PrivProtocol.DEVICE_MOUSE, mouse.clear())
    }
}
