package dev.keymanc.poc

import dev.keymanc.poc.input.HidKeycodes
import dev.keymanc.poc.wire.Msg
import dev.keymanc.poc.wire.Wire
import dev.keymanc.poc.wire.writeMsg
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Sends a scripted sequence to this device's own receiver over loopback, so each backend
 * can be checked without a PC. Same script as `kmc demo`.
 */
object SelfTest {
    fun run() {
        Socket().use { sock ->
            sock.connect(InetSocketAddress("127.0.0.1", Wire.DEFAULT_PORT), 2000)
            sock.tcpNoDelay = true
            val out = BufferedOutputStream(sock.getOutputStream())
            fun send(m: Msg) = out.writeMsg(m)
            fun tap(usage: Int) {
                send(Msg.Key(usage, true, false)); Thread.sleep(15)
                send(Msg.Key(usage, false, false)); Thread.sleep(15)
            }
            fun glide(dx: Int, dy: Int, ms: Int) {
                val steps = ms / 8
                var sx = 0
                var sy = 0
                for (i in 1..steps) {
                    val tx = dx * i / steps
                    val ty = dy * i / steps
                    send(Msg.MouseMove(tx - sx, ty - sy))
                    sx = tx; sy = ty
                    Thread.sleep(8)
                }
            }

            send(Msg.Hello(Wire.VERSION, "self-test"))
            send(Msg.Enter)
            AppLog.i("self-test: mouse square")
            for ((dx, dy) in listOf(250 to 0, 0 to 250, -250 to 0, 0 to -250)) glide(dx, dy, 400)

            AppLog.i("self-test: typing 'keymanc 123' then Hangul toggle + 'gksrmf'")
            for (c in "keymanc 123") {
                when (c) {
                    in 'a'..'z' -> tap(0x04 + (c - 'a'))
                    '0' -> tap(0x27)
                    in '1'..'9' -> tap(0x1E + (c - '1'))
                    ' ' -> tap(HidKeycodes.SPACE)
                }
            }
            tap(HidKeycodes.LANG1_HANGUL)
            for (c in "gksrmf") tap(0x04 + (c - 'a'))
            tap(HidKeycodes.LANG1_HANGUL)

            send(Msg.ReleaseAll)
            send(Msg.Leave)
            sock.shutdownOutput()
            AppLog.i("self-test: done")
        }
    }
}
