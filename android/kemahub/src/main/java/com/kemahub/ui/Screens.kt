package com.kemahub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kemahub.core.HubStatus
import com.kemahub.core.Target

// ---------------------------------------------------------------- setup

@Composable
fun SetupScreen(
    setup: SetupState,
    onNotifications: () -> Unit,
    onBluetooth: () -> Unit,
    onAccessibility: () -> Unit,
    onAppInfo: () -> Unit,
) {
    var showDisclosure by remember { mutableStateOf(false) }
    Page {
        Text("KemaHub", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(
            "이 폰에 연결한 키보드·마우스로 옆의 PC와 태블릿을 조작합니다. 대상 기기에는 설치할 것이 없습니다.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text("시작하려면 세 가지를 허용해 주세요.", style = MaterialTheme.typography.titleMedium)

        Step(1, "알림", "실행 중임과 지금 조작 중인 기기를 알림으로 보여 줍니다.", setup.notifications, "허용", onClick = onNotifications)
        Step(2, "근처 기기 (블루투스)", "이 폰이 블루투스 키보드·마우스로 대상 기기에 연결됩니다.", setup.bluetooth, "허용", onClick = onBluetooth)
        Step(
            3, "접근성 서비스",
            "연결한 키보드·마우스 입력을 받아 선택한 대상 기기로 전달하는 데 필요합니다.",
            setup.accessibility, "설정", enabled = setup.notifications && setup.bluetooth,
        ) { showDisclosure = true }
    }

    if (showDisclosure) {
        // Prominent disclosure (Google Play): shown before sending the user to the accessibility settings.
        AlertDialog(
            onDismissRequest = { showDisclosure = false },
            title = { Text("접근성 서비스 사용 안내") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("KemaHub는 접근성 서비스를 다음 용도로만 사용합니다.")
                    Text("• 이 폰에 연결된 키보드와 마우스의 입력을 받습니다.")
                    Text("• Ctrl+Alt+1~9로 고른 블루투스 대상 기기(PC·태블릿)로 그 입력을 전달합니다. Ctrl+Alt+0을 누르면 이 폰을 그대로 조작합니다.")
                    Text("• 입력 내용은 저장하지 않으며, 인터넷이나 개발자에게 보내지 않습니다. 선택한 대상 기기로만 전달됩니다.")
                    Text("• 화면 내용은 읽지 않습니다.")
                    Text(
                        "다음 화면에서 '설치된 앱 → KemaHub'를 켜 주세요.",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showDisclosure = false; onAccessibility() }) { Text("동의하고 설정 열기") }
            },
            dismissButton = { TextButton(onClick = { showDisclosure = false }) { Text("취소") } },
        )
    }

    if (setup.notifications && setup.bluetooth && !setup.accessibility) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
            TextButton(onClick = onAppInfo) {
                Text("접근성에서 KemaHub가 켜지지 않나요? (앱 정보 → ⋮ → 제한된 설정 허용)", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Step(
    n: Int,
    title: String,
    body: String,
    done: Boolean,
    action: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Badge(if (done) "✓" else "$n", highlighted = done)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
            if (!done) {
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = onClick, enabled = enabled) { Text(action) }
            }
        }
    }
}

// ---------------------------------------------------------------- home

@Composable
fun HomeScreen(
    status: HubStatus,
    log: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRename: (String, String) -> Unit,
    onSlot: (String, Int) -> Unit,
    onForget: (String) -> Unit,
) {
    var showGuide by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    Page {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("KemaHub", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showLog = true }) { Text("진단") }
        }

        StatusCard(status, onStart, onStop)

        status.problem?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("단축키", style = MaterialTheme.typography.titleMedium)
                Text("Ctrl + Alt + 1 ~ 9   번호의 대상 기기 조작")
                Text("Ctrl + Alt + 0         이 폰 조작")
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("대상 기기", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showGuide = true }) { Text("대상 추가 방법") }
        }
        if (status.targets.isEmpty()) {
            Text(
                if (status.running) "아직 연결된 기기가 없습니다. '대상 추가 방법'을 참고해 PC에서 이 폰을 블루투스 장치로 추가하세요."
                else "시작한 뒤 PC나 태블릿의 블루투스 설정에서 이 폰을 추가하세요.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        for (t in status.targets) {
            TargetRow(t, connected = t.address in status.ready, active = status.slot != 0 && t.slot == status.slot, onRename, onSlot, onForget)
        }
    }

    if (showGuide) AddTargetGuide { showGuide = false }
    if (showLog) {
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text("진단 로그") },
            text = {
                SelectionContainer(Modifier.height(400.dp).verticalScroll(rememberScrollState())) {
                    Text(log.ifEmpty { "(비어 있음)" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            },
            confirmButton = { TextButton(onClick = { showLog = false }) { Text("닫기") } },
        )
    }
}

@Composable
private fun StatusCard(status: HubStatus, onStart: () -> Unit, onStop: () -> Unit) {
    val focus = when {
        !status.running -> "꺼짐"
        status.slot == 0 -> "이 폰 조작 중"
        else -> "${status.slot}  ${status.targets.firstOrNull { it.slot == status.slot }?.name.orEmpty()} 조작 중"
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (status.running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(focus, style = MaterialTheme.typography.titleLarge)
                if (status.running) Text("연결된 대상 ${status.ready.size}개", style = MaterialTheme.typography.bodyMedium)
            }
            if (status.running) OutlinedButton(onClick = onStop) { Text("중지") } else Button(onClick = onStart) { Text("시작") }
        }
    }
}

@Composable
private fun TargetRow(
    t: Target,
    connected: Boolean,
    active: Boolean,
    onRename: (String, String) -> Unit,
    onSlot: (String, Int) -> Unit,
    onForget: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var reslotting by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Badge(if (t.slot == 0) "–" else "${t.slot}", highlighted = active)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        active -> "조작 중"
                        connected -> "연결됨 · Ctrl+Alt+${t.slot}"
                        else -> "연결 안 됨"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "메뉴") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("이름 변경") }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text("번호 변경") }, onClick = { menu = false; reslotting = true })
                    DropdownMenuItem(text = { Text("목록에서 삭제") }, onClick = { menu = false; onForget(t.address) })
                }
            }
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(t.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("이름 변경") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { renaming = false; onRename(t.address, name) }) { Text("저장") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("취소") } },
        )
    }
    if (reslotting) {
        AlertDialog(
            onDismissRequest = { reslotting = false },
            title = { Text("번호 변경 (Ctrl+Alt+번호)") },
            text = {
                Column {
                    for (row in listOf(1..3, 4..6, 7..9)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (n in row) {
                                val pick = { reslotting = false; onSlot(t.address, n) }
                                val wide = Modifier.widthIn(min = 64.dp)
                                if (n == t.slot) {
                                    FilledTonalButton(onClick = pick, modifier = wide) { Text("$n") }
                                } else {
                                    OutlinedButton(onClick = pick, modifier = wide) { Text("$n") }
                                }
                            }
                        }
                    }
                    Text("이미 쓰고 있는 번호를 고르면 두 기기의 번호가 서로 바뀝니다.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { reslotting = false }) { Text("닫기") } },
        )
    }
}

@Composable
private fun AddTargetGuide(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("대상 기기 추가") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("KemaHub를 시작해 둔 상태에서, 대상 기기의 블루투스 설정에서 이 폰을 추가합니다. 처음 연결된 순서대로 1번부터 번호가 붙습니다.")
                Text("Windows", fontWeight = FontWeight.SemiBold)
                Text("설정 → 블루투스 및 장치 → 장치 추가 → 블루투스 → 이 폰 이름 선택")
                Text("목록에 없으면: 설정 → 블루투스 및 장치 → 장치 → 'Bluetooth 장치 검색'을 '고급'으로 바꾼 뒤 다시 시도", style = MaterialTheme.typography.bodySmall)
                Text("Mac", fontWeight = FontWeight.SemiBold)
                Text("시스템 설정 → Bluetooth → 목록에서 이 폰 이름 옆 '연결'")
                Text("iPad / iPhone / Android", fontWeight = FontWeight.SemiBold)
                Text("설정 → Bluetooth → 이 폰 이름 선택")
                Text("폰에 연결 확인 창이 뜨면 허용하세요. 연결하는 동안 이 폰의 블루투스 설정 화면은 닫아 두세요.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("확인") } },
    )
}

// ---------------------------------------------------------------- building blocks

@Composable
private fun Page(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
        }
    }
}

@Composable
private fun Badge(text: String, highlighted: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { Text(text, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
    }
}
