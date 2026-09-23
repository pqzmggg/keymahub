package dev.keymanc.poc.ime

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import dev.keymanc.poc.AppLog

/**
 * Input method used by the accessibility backend to deliver key events to the focused
 * editor. It has no on-screen keyboard. Korean composition is not handled here (P0 only
 * measures what works without it).
 */
class KeymancIme : InputMethodService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLog.i("keymanc IME active")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun send(event: KeyEvent) {
        main.post { currentInputConnection?.sendKeyEvent(event) }
    }

    companion object {
        @Volatile
        var instance: KeymancIme? = null
            private set
    }
}
