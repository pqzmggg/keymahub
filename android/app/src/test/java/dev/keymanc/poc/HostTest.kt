package dev.keymanc.poc

import dev.keymanc.poc.host.HostRouter
import dev.keymanc.poc.host.HostRouter.Companion.KEY_ESC
import dev.keymanc.poc.host.HostRouter.Companion.KEY_LEFT
import dev.keymanc.poc.host.HostRouter.Companion.KEY_LEFTALT
import dev.keymanc.poc.host.HostRouter.Companion.KEY_LEFTCTRL
import dev.keymanc.poc.host.HostRouter.Companion.KEY_LEFTSHIFT
import dev.keymanc.poc.host.HostRouter.Companion.KEY_RIGHT
import dev.keymanc.poc.host.InputDeviceInfo
import dev.keymanc.poc.input.EvdevKeymap
import dev.keymanc.poc.input.HidDescriptors
import dev.keymanc.poc.sink.InputSink
import dev.keymanc.poc.wire.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostRouterTest {
    private class Recorder(val name: String, val log: MutableList<String>) : InputSink {
        override fun start(): String? = null
        override fun stop() {}
        override fun enter() { log += "$name enter" }
        override fun leave() { log += "$name leave" }
        override fun key(usage: Int, down: Boolean, repeat: Boolean) {
            log += "$name key %02X %s".format(usage, if (down) "down" else "up")
        }
        override fun move(dx: Int, dy: Int) { log += "$name move $dx $dy" }
        override fun button(button: Int, down: Boolean) { log += "$name button $button ${if (down) "down" else "up"}" }
        override fun wheel(v: Int, h: Int) { log += "$name wheel $v $h" }
        override fun releaseAll() { log += "$name releaseAll" }
    }

    private val log = mutableListOf<String>()
    private val router = HostRouter(
        Recorder("local", log), Recorder("remote", log),
        onFocus = { log += "focus remote=$it" },
        onNextTarget = { log += "next target" },
    )

    private fun key(code: Int, down: Boolean) = router.onKey(code, EvdevKeymap.toHid(code), down)

    private fun hotkey(code: Int) {
        key(KEY_LEFTCTRL, true); key(KEY_LEFTALT, true)
        key(code, true); key(code, false)
        key(KEY_LEFTALT, false); key(KEY_LEFTCTRL, false)
    }

    @Test
    fun localPassthroughByDefault() {
        key(30, true) // KEY_A
        key(30, false)
        router.onMove(3, 4)
        router.onButton(Wire.BUTTON_LEFT, true)
        assertEquals(listOf("local key 04 down", "local key 04 up", "local move 3 4", "local button 1 down"), log)
    }

    @Test
    fun switchToRemoteReleasesHotkeyModifiersLocally() {
        router.setRemoteAvailable(true)
        hotkey(KEY_RIGHT)
        assertTrue(router.isRemote)
        assertEquals(
            listOf(
                "local key E0 down", "local key E2 down", // modifiers went down locally...
                "remote enter", "focus remote=true",
                "local key E2 up", "local key E0 up",     // ...so they come up locally; arrow swallowed
            ),
            log,
        )
        log.clear()
        key(30, true)
        router.onMove(-1, 2)
        router.onWheel(-120, 0)
        assertEquals(listOf("remote key 04 down", "remote move -1 2", "remote wheel -120 0"), log)
    }

    @Test
    fun switchBackReleasesRemoteKeysAndSwallowsTheirPhysicalRelease() {
        router.setRemoteAvailable(true)
        hotkey(KEY_RIGHT)
        key(30, true) // KEY_A held on the target
        router.onButton(Wire.BUTTON_LEFT, true)
        log.clear()

        key(KEY_LEFTCTRL, true); key(KEY_LEFTALT, true); key(KEY_LEFT, true)
        assertFalse(router.isRemote)
        for (e in listOf("remote key 04 up", "remote key E0 up", "remote key E2 up", "remote button 1 up", "remote releaseAll", "focus remote=false")) {
            assertTrue("missing $e in $log", e in log)
        }
        log.clear()
        key(KEY_LEFT, false); key(KEY_LEFTALT, false); key(KEY_LEFTCTRL, false); key(30, false)
        router.onButton(Wire.BUTTON_LEFT, false)
        assertEquals(emptyList<String>(), log) // nothing leaks to either side
    }

    @Test
    fun cannotGoRemoteWithoutTargetAndDisconnectReturnsLocal() {
        hotkey(KEY_RIGHT)
        assertFalse(router.isRemote)

        router.setRemoteAvailable(true)
        hotkey(KEY_RIGHT)
        assertTrue(router.isRemote)
        router.setRemoteAvailable(false)
        assertFalse(router.isRemote)
    }

    @Test
    fun rightAgainWhileRemoteSwitchesTargetAfterReleasingHeldKeys() {
        router.setRemoteAvailable(true)
        hotkey(KEY_RIGHT)
        key(30, true) // A held on the first target
        log.clear()
        key(KEY_LEFTCTRL, true); key(KEY_LEFTALT, true); key(KEY_RIGHT, true)
        assertTrue(router.isRemote)
        val next = log.indexOf("next target")
        assertTrue("next target not requested: $log", next >= 0)
        for (e in listOf("remote key 04 up", "remote key E0 up", "remote key E2 up", "remote releaseAll")) {
            val i = log.indexOf(e)
            assertTrue("$e must be sent to the old target before switching: $log", i in 0 until next)
        }
        log.clear()
        key(KEY_RIGHT, false); key(KEY_LEFTALT, false); key(KEY_LEFTCTRL, false); key(30, false)
        assertEquals(emptyList<String>(), log) // old presses don't leak to the new target
        key(30, true)
        assertEquals(listOf("remote key 04 down"), log)
    }

    @Test
    fun emergencyHotkey() {
        router.setRemoteAvailable(true)
        hotkey(KEY_RIGHT)
        key(KEY_LEFTCTRL, true); key(KEY_LEFTALT, true); key(KEY_LEFTSHIFT, true); key(KEY_ESC, true)
        assertFalse(router.isRemote)
    }
}

class HostDevicesTest {
    // Trimmed from a real /proc/bus/input/devices (64-bit kernel).
    private val sample = """
        I: Bus=0005 Vendor=046d Product=b35b Version=0001
        N: Name="Logitech K380"
        H: Handlers=sysrq kbd event5
        B: EV=12001f
        B: KEY=3f000303ff 0 0 483ffff17aff32d bfd4444600000000 1 130ff38b17c007 ffff7bfad9415fff ffbeffdfffefffff fffffffffffffffe
        B: REL=1040

        I: Bus=0005 Vendor=046d Product=b019 Version=0001
        N: Name="MX Master 3"
        H: Handlers=event6
        B: EV=17
        B: KEY=ffff0000 0 0 0 0
        B: REL=1943

        I: Bus=0019 Vendor=0001 Product=0001 Version=0100
        N: Name="gpio-keys"
        H: Handlers=event0
        B: EV=3
        B: KEY=10000000000000 0 1c0000000000000 0

        I: Bus=0006 Vendor=1209 Product=4b03 Version=0000
        N: Name="keymanc Passthrough Keyboard"
        H: Handlers=sysrq kbd event9
        B: EV=12001f
        B: KEY=3f000303ff 0 0 483ffff17aff32d bfd4444600000000 1 130ff38b17c007 ffff7bfad9415fff ffbeffdfffefffff fffffffffffffffe
    """.trimIndent()

    @Test
    fun classifiesDevices() {
        val devs = InputDeviceInfo.parse(sample, 64).associateBy { it.name }
        assertTrue(devs.getValue("Logitech K380").isKeyboard)
        assertFalse(devs.getValue("Logitech K380").isMouse)
        assertTrue(devs.getValue("MX Master 3").isMouse)
        assertFalse(devs.getValue("MX Master 3").isKeyboard)
        assertFalse(devs.getValue("gpio-keys").isKeyboard) // power/volume only
        assertFalse(devs.getValue("gpio-keys").isMouse)
        assertTrue(devs.getValue("keymanc Passthrough Keyboard").isOurs)
        assertEquals("event5", devs.getValue("Logitech K380").node)
    }

    @Test
    fun comboDescriptorHasReportIds() {
        val d = HidDescriptors.COMBO
        assertEquals(HidDescriptors.KEYBOARD.size + HidDescriptors.MOUSE.size + 4, d.size)
        assertEquals(0x85.toByte(), d[6]); assertEquals(1.toByte(), d[7])
        val m = HidDescriptors.KEYBOARD.size + 2
        assertEquals(0x85.toByte(), d[m + 6]); assertEquals(2.toByte(), d[m + 7])
    }

    @Test
    fun evdevTableMatchesRustKeymap() {
        assertEquals(130, EvdevKeymap.SIZE)
        assertEquals(0x04, EvdevKeymap.toHid(30))  // KEY_A
        assertEquals(0x90, EvdevKeymap.toHid(122)) // KEY_HANGEUL
        assertEquals(0x4F, EvdevKeymap.toHid(106)) // KEY_RIGHT
    }
}
