package dev.keymanc.poc

import android.content.Context

enum class Backend(val label: String) {
    UHID("UHID (Shizuku) — 실제 HID 장치"),
    INJECT("Inject (Shizuku) — InputManager 주입"),
    ACCESSIBILITY("접근성 + IME (권한 앱 불필요)"),
    BT_RELAY("블루투스 중계 (P0-5: 받은 입력을 블루투스 키보드·마우스로 전달)"),
}

/** What the Hangul/English (HID LANG1) key turns into on this device. */
enum class HangulKey(val label: String) {
    SHIFT_SPACE("Shift+Space (삼성 키보드)"),
    CTRL_SPACE("Ctrl+Space (Gboard)"),
    PASSTHROUGH("그대로 전달 (LANG1)"),
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("poc", Context.MODE_PRIVATE)

    var backend: Backend
        get() = runCatching { Backend.valueOf(sp.getString("backend", null)!!) }.getOrDefault(Backend.UHID)
        set(v) = sp.edit().putString("backend", v.name).apply()

    var hangulKey: HangulKey
        get() = runCatching { HangulKey.valueOf(sp.getString("hangul", null)!!) }.getOrDefault(HangulKey.SHIFT_SPACE)
        set(v) = sp.edit().putString("hangul", v.name).apply()

    var mouseSpeed: Float
        get() = sp.getFloat("speed", 1.0f)
        set(v) = sp.edit().putFloat("speed", v).apply()
}
