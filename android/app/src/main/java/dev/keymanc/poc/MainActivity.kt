package dev.keymanc.poc

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.bluetooth.BluetoothAdapter
import dev.keymanc.poc.a11y.KeymancAccessibilityService
import dev.keymanc.poc.bt.BtHid
import dev.keymanc.poc.host.HostService
import dev.keymanc.poc.ime.KeymancIme
import dev.keymanc.poc.priv.PrivClient
import dev.keymanc.poc.wire.Wire
import rikka.shizuku.Shizuku

/** P0 test console. Plain views on purpose: no UI dependencies for the PoC. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var testField: EditText
    private val logListener: () -> Unit = { runOnUiThread { log.text = AppLog.snapshot() } }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        Shizuku.addRequestPermissionResultListener(permissionListener)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        AppLog.listen(logListener)
        logListener()
        refresh()
    }

    override fun onPause() {
        AppLog.unlisten(logListener)
        super.onPause()
    }

    private fun refresh() = runOnUiThread {
        val ime = getSystemService(InputMethodManager::class.java)!!
            .enabledInputMethodList.any { it.packageName == packageName }
        status.text = buildString {
            appendLine("IP: ${Net.addresses().joinToString().ifEmpty { "(없음)" }}  port ${Wire.DEFAULT_PORT}")
            appendLine("리시버: ${if (ReceiverService.running) "실행 중" else "중지"} / 호스트: ${if (HostService.running) "실행 중" else "중지"}")
            appendLine("블루투스 HID: ${BtHid.state()} (권한 ${yes(BtHid.hasPermission(this@MainActivity))})")
            appendLine("Shizuku: ${runCatching { PrivClient.shizukuState() }.getOrDefault("?")}")
            appendLine("오버레이 권한: ${yes(Settings.canDrawOverlays(this@MainActivity))}")
            appendLine("접근성 서비스: ${yes(KeymancAccessibilityService.instance != null)}")
            append("keymanc IME: 사용 설정 ${yes(ime)}, 현재 선택 ${yes(KeymancIme.instance != null)}")
        }
    }

    private fun yes(b: Boolean) = if (b) "O" else "X"

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        fun header(text: String) = root.addView(TextView(this).apply {
            this.text = text
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 24, 0, 8)
        })
        fun buttons(vararg items: Pair<String, () -> Unit>) = root.addView(LinearLayout(this).apply {
            for ((label, action) in items) {
                addView(Button(this@MainActivity).apply {
                    text = label
                    isAllCaps = false
                    setOnClickListener { action() }
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
        })
        fun <T> radios(values: List<T>, label: (T) -> String, selected: T, onPick: (T) -> Unit) =
            root.addView(RadioGroup(this).apply {
                values.forEach { v ->
                    addView(RadioButton(this@MainActivity).apply {
                        id = View.generateViewId()
                        text = label(v)
                        isChecked = v == selected
                        setOnClickListener { onPick(v); AppLog.i("설정 변경: ${label(v)} (리시버 재시작 후 적용)") }
                    })
                }
            })

        root.addView(TextView(this).apply {
            text = "keymanc P0 — Android 리시버 / 호스트 프로브"
            textSize = 20f
        })
        status = TextView(this).apply { setPadding(0, 16, 0, 0) }
        root.addView(status)

        header("주입 방식 (P0-2 / P0-3)")
        radios(Backend.entries, { it.label }, prefs.backend) { prefs.backend = it }
        header("한/영 키 변환")
        radios(HangulKey.entries, { it.label }, prefs.hangulKey) { prefs.hangulKey = it }
        header("마우스 속도")
        radios(listOf(0.5f, 1f, 1.5f, 2f, 3f), { "${it}x" }, prefs.mouseSpeed) { prefs.mouseSpeed = it }

        header("리시버")
        buttons(
            "시작" to { ReceiverService.start(this); postRefresh() },
            "중지" to { ReceiverService.stop(this); postRefresh() },
            "자체 테스트" to ::runSelfTest,
        )
        header("권한 / 설정")
        buttons(
            "Shizuku 권한" to { PrivClient.requestPermission(); postRefresh() },
            "오버레이 권한" to {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            },
        )
        buttons(
            "접근성 설정" to { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            "키보드 설정" to { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
            "키보드 선택" to { getSystemService(InputMethodManager::class.java)!!.showInputMethodPicker() },
        )
        header("P0-4 호스트 프로브 (Shizuku 필요)")
        buttons("evdev 열기/grab 테스트 (10초)" to ::runEvdevProbe)

        header("P0-5 Android 호스트 → 블루투스 (Shizuku 필요)")
        buttons(
            "블루투스 권한" to ::requestBluetoothPermission,
            "페어링 허용 (2분)" to {
                startActivity(
                    Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                        .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
                )
            },
        )
        buttons(
            "호스트 시작" to { HostService.start(this); postRefresh() },
            "호스트 중지" to { HostService.stop(this); postRefresh() },
        )

        header("입력 테스트 칸")
        testField = EditText(this).apply { hint = "여기에 원격 입력이 들어오는지 확인" }
        root.addView(testField, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        header("로그")
        log = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        root.addView(log)
        return ScrollView(this).apply { addView(root) }
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE), 2,
            )
        } else {
            AppLog.i("Android 11 이하: 블루투스 권한은 설치 시 허용됨")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        refresh()
    }

    private fun postRefresh() = status.postDelayed({ refresh() }, 500)

    private fun runSelfTest() {
        if (!ReceiverService.running) {
            AppLog.i("리시버를 먼저 시작하세요")
            return
        }
        testField.requestFocus()
        Thread {
            Thread.sleep(500)
            runCatching { SelfTest.run() }.onFailure { AppLog.i("self-test failed: $it") }
        }.start()
    }

    private fun runEvdevProbe() {
        AppLog.i("evdev probe: connecting to privileged service…")
        Thread {
            if (!PrivClient.connect()) {
                AppLog.i("evdev probe: Shizuku ${PrivClient.shizukuState()}")
                return@Thread
            }
            AppLog.i("evdev probe: 10초 동안 이 기기에 연결된 키보드/마우스를 사용해 보세요")
            val report = PrivClient.evdevProbe(10) ?: "no answer"
            report.lines().filter { it.isNotBlank() }.forEach { AppLog.i(it) }
        }.start()
    }
}
