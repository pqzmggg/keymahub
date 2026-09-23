package com.kemahub.ble

import com.kemahub.core.InputSink
import com.kemahub.hid.HidDescriptors
import com.kemahub.hid.KeyboardReport
import com.kemahub.hid.MouseReport
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Turns routed input into HID reports for the selected BLE target. Everything, including
 * target switches, runs on one thread, so the releases queued for the old target are sent
 * before the switch. Motion is coalesced to one report per [MOTION_MS].
 */
class HidSender : InputSink {
    private val keyboard = KeyboardReport()
    private val mouse = MouseReport()
    private val exec = Executors.newSingleThreadScheduledExecutor { Thread(it, "hid-sender") }
    private var accX = 0
    private var accY = 0
    private var motionScheduled = false

    /** Subsequent input goes to [address] (null: nowhere). */
    fun select(address: String?) = run {
        flushMotion()
        BleHid.select(address)
    }

    fun shutdown() {
        releaseAll()
        exec.shutdown()
    }

    override fun key(usage: Int, down: Boolean) = run {
        flushMotion()
        keyboard.update(usage, down)?.let { BleHid.send(HidDescriptors.REPORT_ID_KEYBOARD, it) }
    }

    override fun move(dx: Int, dy: Int) = run {
        accX += dx
        accY += dy
        if (!motionScheduled) {
            motionScheduled = true
            exec.schedule(::flushMotion, MOTION_MS, TimeUnit.MILLISECONDS)
        }
    }

    override fun button(button: Int, down: Boolean) = run {
        flushMotion()
        mouse.setButton(button, down)?.let { BleHid.send(HidDescriptors.REPORT_ID_MOUSE, it) }
    }

    override fun wheel(v: Int, h: Int) = run {
        flushMotion()
        mouse.wheel(v, h)?.let { BleHid.send(HidDescriptors.REPORT_ID_MOUSE, it) }
    }

    override fun releaseAll() = run {
        accX = 0
        accY = 0
        BleHid.send(HidDescriptors.REPORT_ID_KEYBOARD, keyboard.clear())
        BleHid.send(HidDescriptors.REPORT_ID_MOUSE, mouse.clear())
    }

    private fun run(block: () -> Unit) {
        if (!exec.isShutdown) exec.execute(block)
    }

    /** Runs on [exec]. */
    private fun flushMotion() {
        motionScheduled = false
        if (accX == 0 && accY == 0) return
        val reports = mouse.move(accX, accY)
        accX = 0
        accY = 0
        for (r in reports) BleHid.send(HidDescriptors.REPORT_ID_MOUSE, r)
    }

    private companion object {
        const val MOTION_MS = 8L
    }
}
