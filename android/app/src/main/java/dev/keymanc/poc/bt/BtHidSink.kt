package dev.keymanc.poc.bt

import android.content.Context
import dev.keymanc.poc.AppLog
import dev.keymanc.poc.input.HidDescriptors
import dev.keymanc.poc.input.KeyboardReport
import dev.keymanc.poc.input.MouseReport
import dev.keymanc.poc.sink.InputSink
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Sends input to the Bluetooth target as HID reports. Everything runs on one thread so
 * report order is preserved; motion is coalesced to at most one report per [MOTION_MS]
 * because the Bluetooth link cannot keep up with raw 1 kHz mouse rates.
 */
class BtHidSink(private val context: Context, private val transport: HidTransport) : InputSink {
    private val keyboard = KeyboardReport()
    private val mouse = MouseReport()
    private val exec = Executors.newSingleThreadScheduledExecutor { Thread(it, "bt-hid") }
    private var accX = 0
    private var accY = 0
    private var motionScheduled = false
    @Volatile private var warnedOffline = false

    override fun start(): String? = transport.start(context)

    /**
     * Moves to the next connected target, after everything queued before it (in particular
     * the key releases for the previous target) has been sent.
     */
    fun switchTarget() {
        if (exec.isShutdown) return
        exec.execute {
            flushMotion()
            val name = transport.nextTarget()
            AppLog.i(if (name != null) "target → $name" else "no other target connected")
        }
    }

    override fun stop() {
        releaseAll()
        exec.shutdown()
    }

    override fun key(usage: Int, down: Boolean, repeat: Boolean) {
        // A HID keyboard repeats on the target's side.
        if (repeat) return
        exec.execute {
            flushMotion()
            keyboard.update(usage, down)?.let { send(HidDescriptors.REPORT_ID_KEYBOARD, it) }
        }
    }

    override fun move(dx: Int, dy: Int) {
        exec.execute {
            accX += dx
            accY += dy
            if (!motionScheduled) {
                motionScheduled = true
                exec.schedule(::flushMotion, MOTION_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    override fun button(button: Int, down: Boolean) {
        exec.execute {
            flushMotion()
            mouse.setButton(button, down)?.let { send(HidDescriptors.REPORT_ID_MOUSE, it) }
        }
    }

    override fun wheel(v: Int, h: Int) {
        exec.execute {
            flushMotion()
            mouse.wheel(v, h)?.let { send(HidDescriptors.REPORT_ID_MOUSE, it) }
        }
    }

    override fun releaseAll() {
        if (exec.isShutdown) return
        exec.execute {
            accX = 0
            accY = 0
            send(HidDescriptors.REPORT_ID_KEYBOARD, keyboard.clear())
            send(HidDescriptors.REPORT_ID_MOUSE, mouse.clear())
        }
    }

    /** Runs on [exec]. */
    private fun flushMotion() {
        motionScheduled = false
        if (accX == 0 && accY == 0) return
        val reports = mouse.move(accX, accY)
        accX = 0
        accY = 0
        for (r in reports) send(HidDescriptors.REPORT_ID_MOUSE, r)
    }

    private fun send(id: Int, report: ByteArray) {
        if (transport.send(id, report)) {
            warnedOffline = false
        } else if (!warnedOffline) {
            warnedOffline = true
            AppLog.i("BT HID: no connected target, input dropped")
        }
    }

    private companion object {
        const val MOTION_MS = 8L
    }
}
