package app.keymahub.ble

import android.os.Process
import android.os.SystemClock
import app.keymahub.core.InputSink
import app.keymahub.hid.ConsumerKeys
import app.keymahub.hid.ConsumerReport
import app.keymahub.hid.HidDescriptors
import app.keymahub.hid.KeyboardReport
import app.keymahub.hid.MouseReport
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Turns routed input into HID reports for the selected BLE target. Everything, including
 * target switches, runs on one thread, so the releases queued for the old target are sent
 * before the switch. Motion is coalesced to at most one report per [MOTION_MS]; the first
 * motion after a pause goes out at once.
 */
class HidSender : InputSink {
    private val keyboard = KeyboardReport()
    private val mouse = MouseReport()
    private val media = ConsumerReport()
    private val exec = Executors.newSingleThreadScheduledExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) // input path: ahead of background work
            r.run()
        }, "hid-sender")
    }
    private var accX = 0
    private var accY = 0
    private var motionScheduled = false
    /** When motion last went out, uptime millis. */
    private var lastMotion = 0L

    /** Subsequent input goes to [address] (null: nowhere). */
    fun select(address: String?) = post {
        flushMotion()
        BleHid.select(address)
    }

    fun shutdown() {
        releaseAll()
        exec.shutdown()
    }

    override fun key(usage: Int, down: Boolean) = post {
        flushMotion()
        if (usage and ConsumerKeys.FLAG != 0) {
            media.update(usage and 0xFFFF, down)?.let { BleHid.send(HidDescriptors.REPORT_ID_CONSUMER, it) }
        } else {
            keyboard.update(usage, down)?.let { BleHid.send(HidDescriptors.REPORT_ID_KEYBOARD, it) }
        }
    }

    override fun move(dx: Int, dy: Int) = post {
        accX += dx
        accY += dy
        if (!motionScheduled) {
            val wait = lastMotion + MOTION_MS - SystemClock.uptimeMillis()
            if (wait <= 0) {
                flushMotion()
            } else {
                motionScheduled = true
                exec.schedule(::flushMotion, wait, TimeUnit.MILLISECONDS)
            }
        }
    }

    override fun button(button: Int, down: Boolean) = post {
        flushMotion()
        mouse.setButton(button, down)?.let { BleHid.send(HidDescriptors.REPORT_ID_MOUSE, it) }
    }

    override fun wheel(v: Int, h: Int) = post {
        flushMotion()
        mouse.wheel(v, h)?.let { BleHid.send(HidDescriptors.REPORT_ID_MOUSE, it) }
    }

    override fun releaseAll() = post {
        accX = 0
        accY = 0
        BleHid.send(HidDescriptors.REPORT_ID_KEYBOARD, keyboard.clear())
        BleHid.send(HidDescriptors.REPORT_ID_MOUSE, mouse.clear())
        BleHid.send(HidDescriptors.REPORT_ID_CONSUMER, media.clear())
    }

    private fun post(block: () -> Unit) {
        if (!exec.isShutdown) exec.execute(block)
    }

    /** Runs on [exec]. */
    private fun flushMotion() {
        motionScheduled = false
        if (accX == 0 && accY == 0) return
        lastMotion = SystemClock.uptimeMillis()
        val reports = mouse.move(accX, accY)
        accX = 0
        accY = 0
        for (r in reports) BleHid.send(HidDescriptors.REPORT_ID_MOUSE, r)
    }

    private companion object {
        const val MOTION_MS = 8L
    }
}
