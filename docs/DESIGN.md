# keymanc 설계 문서 (v0.2)

> 하나의 키보드·마우스를 같은 LAN 안의 Windows / Android / Linux 기기와 공유하는 앱.
> 서버 없음, 같은 네트워크에서만 동작, 1순위는 Windows ↔ Android.

---

## 0. 요구사항 정리

| # | 요구사항 | 설계 반영 |
|---|---|---|
| R1 | Windows, Android, Linux 지원 | 공통 코어(Rust) + 플랫폼별 입력 어댑터 |
| R2 | 호스트가 리시버를 등록(페어링) | 호스트→리시버 방향의 페어링, 공개키 고정(pinning) |
| R3 | 조건 충족 시 입력을 해당 기기로 전송 | `SwitchTrigger` 추상화: 단축키 / 화면 경계 둘 다 지원 (§4) |
| R4 | 같은 네트워크 내에서만 동작, 서버 없음 | mDNS 탐색 + 직접 TCP, 사설/링크로컬 + 동일 서브넷 검사 (§6.4) |
| R5 | 1순위 Windows ↔ Android | 로드맵 P1/P2가 Windows↔Android (§10) |
| R6 | 호스트: Windows, Linux, Android 모두 가능 | 플랫폼별 Capture 어댑터 (§7) |
| R7 | 한 기기가 호스트·리시버 모두로 등록 가능 | 역할 등록은 독립, **실행 시 활성 역할은 하나** (§3) |
| R8 | 둘 다 등록된 경우 시작 시 선택 | 시작 시 역할 선택 화면 + "기본값으로 기억" (§3.2) |
| R9 | 앱이 백그라운드에 있어도 동작 | Windows 트레이, Android Foreground Service, Linux 사용자 서비스 (§8) |

### 비목표 (당분간)
- 인터넷/원격(다른 네트워크) 연결, 중계 서버, 클라우드 계정
- 화면 공유/스트리밍
- 파일 전송 (클립보드 텍스트 공유는 P4 후보)
- 잠금화면·UAC 보안 데스크톱 등 OS가 막는 영역에서의 입력

---

## 1. 용어

| 용어 | 의미 |
|---|---|
| **Host(호스트)** | 물리 키보드·마우스가 연결된 기기. 입력을 캡처해서 보냄 |
| **Receiver(리시버)** | 입력을 받아 가상 입력으로 주입(inject)하는 기기 |
| **Pairing(등록)** | 호스트가 리시버를 신뢰 목록에 추가하는 1회성 절차 |
| **Session** | 호스트↔리시버 간 암호화된 연결 1개 |
| **Focus** | 현재 입력이 향하는 대상 (로컬 = 호스트 자신, 또는 리시버 중 하나) |
| **Capture** | 호스트에서 물리 입력을 가로채고 로컬로 전달되지 않게 막는 것 |
| **Inject** | 리시버에서 OS에 가상 입력 이벤트를 넣는 것 |

---

## 2. 전체 구조

```
┌──────────────────────── 한 기기 (역할: Host 또는 Receiver) ────────────────────────┐
│                                                                                   │
│  ┌─────────── UI ───────────┐     ┌──────────────── keymanc-core (Rust) ─────────┐ │
│  │ Windows/Linux: 트레이 +   │     │  engine   : 역할 상태머신, Focus 전환, 레이아웃 │ │
│  │   설정창 (Tauri)          │◀──▶│  net      : 탐색(mDNS), 페어링, Noise 세션     │ │
│  │ Android: Kotlin 앱        │ FFI │  proto    : 메시지 정의/직렬화                 │ │
│  └───────────────────────────┘     │  keymap   : HID ↔ 플랫폼 키코드 변환            │ │
│                                    │  store    : 기기 ID, 키쌍, 페어링 목록 저장      │ │
│                                    └────────────┬───────────────────┬─────────────┘ │
│                                                 │ trait InputCapture│ trait InputInjector
│                                    ┌────────────▼───────────────────▼─────────────┐ │
│                                    │        플랫폼 어댑터 (§7)                     │ │
│                                    │  Windows: LL Hook + Raw Input / SendInput      │ │
│                                    │  Linux  : evdev(grab) / uinput                 │ │
│                                    │  Android: Shizuku(UHID·evdev) / 블루투스 HID    │ │
│                                    └────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────────────────────────┘
```

### 2.1 기술 스택 (확정)

| 영역 | 선택 | 이유 |
|---|---|---|
| 공통 코어 | **Rust** (tokio) | 3개 플랫폼 공용, 저지연, 메모리 안전. Android는 FFI로 사용 |
| Android 바인딩 | **UniFFI** → Kotlin | 보일러플레이트 없이 Kotlin API 생성 |
| Android 앱 | **Kotlin** (P0는 기본 View, P1부터 Jetpack Compose) | 서비스/접근성/IME/Shizuku 등 Android 전용 API가 많음 |
| Desktop UI | **Tauri 2** (Windows/Linux 공용) | 트레이 + 설정창, 코어와 같은 Rust 프로세스 |
| 암호화 | **Noise_KK** (`snow`) + 페어링은 **SPAKE2** | TLS/CA 없이 서버리스 상호인증 |
| 직렬화 | `serde` + `postcard` | 작고 빠른 바이너리, 스키마 버전 관리 용이 |
| 탐색 | mDNS/DNS-SD (`mdns-sd`) + UDP 브로드캐스트 폴백 | 서버 없이 LAN 탐색 |

> 대안: 전부 Kotlin Multiplatform, 혹은 C++ 코어. Windows/Linux 저수준 입력 API 접근성과
> Android FFI 성숙도를 고려해 Rust로 결정 (D1).

### 2.2 저장소 구조 (제안)

```
keymanc/
├─ core/                    # Rust workspace
│  ├─ proto/                # 메시지 타입, 코덱, 버전
│  ├─ net/                  # discovery, pairing, transport(Noise)
│  ├─ engine/               # 역할/세션/포커스 상태머신, 레이아웃, 트리거
│  ├─ keymap/               # USB HID ↔ Win scancode / Linux evdev / Android keycode
│  ├─ store/                # 설정·키·페어링 영속화
│  └─ ffi/                  # UniFFI (Android용)
├─ platform/
│  ├─ windows/              # capture/inject 구현 + 자동시작
│  └─ linux/                # evdev/uinput 구현 + systemd user unit
├─ desktop/                 # Tauri 앱 (Windows/Linux)
├─ android/                 # Gradle 프로젝트
│  ├─ app/                  # UI, ForegroundService
│  ├─ inject-privileged/    # Shizuku UserService (주입 + evdev 캡처)
│  └─ inject-accessibility/ # AccessibilityService + IME (폴백)
└─ docs/
```

---

## 3. 역할 모델

### 3.1 등록과 활성은 분리

- **등록(registration)** 은 방향성이 있는 신뢰 관계다.
  - 호스트 H가 리시버 R을 등록하면 → H에는 `PairedReceiver(R)`, R에는 `TrustedHost(H)` 레코드가 생긴다.
- 기기가 **호스트로 등록됨** = `PairedReceiver`가 1개 이상.
  기기가 **리시버로 등록됨** = `TrustedHost`가 1개 이상.
- 한 기기는 둘 다 가질 수 있다. 예) 노트북은 데스크톱의 리시버이면서, 태블릿의 호스트.
- **실행 중 활성 역할은 하나만** (Host 모드 XOR Receiver 모드).
  - 이유: 동시 활성 시 A→B→A 같은 입력 루프, 포커스 소유권 충돌, 캡처/주입 동시 수행 문제.
  - 역할 전환은 트레이/알림 메뉴에서 즉시 가능 (재시작 불필요).

### 3.2 시작 시 역할 결정

```
앱 시작
 ├─ 등록 없음            → 온보딩 (이 기기를 호스트로 / 리시버로 설정)
 ├─ 호스트 등록만 있음    → Host 모드로 시작
 ├─ 리시버 등록만 있음    → Receiver 모드로 시작
 └─ 둘 다 있음
      ├─ "기본 역할 기억" 설정됨 → 그 역할로 시작 (트레이에서 변경 가능)
      └─ 아니면              → 역할 선택 화면 표시
                                 (자동시작/부팅 시에는 선택 대기 중 알림을 띄우고,
                                  N초 후 마지막 사용 역할로 진행 — 설정 가능)
```

### 3.3 다중 관계 규칙

- 호스트는 여러 리시버를 등록할 수 있다 (레이아웃/단축키로 구분).
- 리시버는 여러 호스트를 신뢰할 수 있지만, **동시에 입력을 받는 세션은 1개**.
  두 번째 호스트가 연결을 시도하면 `Busy`로 거절 (설정으로 "나중 연결 우선" 선택 가능).

---

## 4. 전환 트리거 (단축키 vs 화면 경계)

두 방식은 장단점이 뚜렷해서 **둘 다 지원하되 구현 순서를 나누는 것**을 제안한다.

| | 단축키 | 화면 경계 |
|---|---|---|
| 구현 난이도 | 낮음 | 중~높음 (커서 위치 파악, 레이아웃 UI 필요) |
| Android 호스트 | 가능 | 어려움 (전역 커서 위치를 얻기 힘듦, §7.3) |
| Wayland 호스트 | 가능 | 포털(InputCapture) 지원 환경에서만 |
| 사용성 | 명시적, 오작동 없음 | 자연스러움, 대신 모서리 오작동 가능 |
| 멀티 리시버 | 기기별 단축키 or 순환 | 방향별 배치 |

**결정 제안 (D2):** 코어에는 `SwitchTrigger` 인터페이스를 두고
- **MVP(P1): 단축키** — 모든 호스트 플랫폼에서 동작, 가장 안정적
- **P2: 화면 경계** — Windows/Linux(X11) 호스트부터
- 단축키는 경계 모드에서도 항상 **탈출 수단**으로 유지

### 4.1 단축키 모드

- 기본값 예시: `Ctrl+Alt+→/←` = 다음/이전 기기 순환, `Ctrl+Alt+1..9` = 특정 기기, `Ctrl+Alt+0` = 로컬 복귀
- **비상 복귀**: `Ctrl+Alt+Shift+Esc` (변경 불가, 항상 로컬 복귀 + 캡처 해제)
- 단축키를 구성하는 키의 down/up은 리시버로 보내지 않고 삼킨다(swallow).
  전환 직전 이미 눌려 있던 수식키는 전환 시 리시버에 보내지 않는다 (§5.4 stuck key 방지).

### 4.2 화면 경계 모드

- 호스트 설정창에서 리시버 화면을 호스트 화면 주변(상/하/좌/우)에 배치하는 **레이아웃 편집기** 제공.
- **호스트 → 리시버**: 호스트 커서가 해당 경계에 닿고 그 방향으로 계속 밀면 전환.
  진입 좌표는 경계상 비율로 리시버에 전달 (`Enter{edge, pos_ratio}`).
- **리시버 → 호스트**: 리시버가 자기 커서를 소유(**receiver-authoritative cursor**)하고,
  반대쪽 경계에 닿으면 `EdgeHit` 을 호스트에 보내 복귀.
  → 호스트가 리시버 커서를 추측할 필요가 없어 가속/해상도 차이 문제를 피함.
- 오작동 방지 옵션: 모서리 코너 N px 제외, 경계 체류시간(dwell) ms, "두 번 밀기", 전체화면 앱 실행 중 비활성화.

---

## 5. 입력 모델

### 5.1 키보드: 물리 키 위치(HID Usage) 기반

- 전송 단위는 **문자가 아니라 물리 키** (USB HID Keyboard Usage Page 0x07 코드).
  리시버의 자판 배열/IME가 최종 문자를 결정 → 한/영 전환도 리시버 쪽 IME 상태를 따름.
- 변환 테이블(`core/keymap`):
  - Windows: 스캔코드(Set 1, E0 확장 포함) ↔ HID
  - Linux: evdev `KEY_*` ↔ HID
  - Android: Linux keycode 경유 (`Generic.kl`) → `KeyEvent.KEYCODE_*`
- **한국어 키 주의**: 한/영(HID `LANG1` 0x90, Win `VK_HANGUL`, Linux `KEY_HANGEUL`),
  한자(`LANG2` 0x91). Android는 기본 키 레이아웃에 한/영 매핑이 기기마다 달라서
  리시버 설정에 "한/영 키 → `Shift+Space` / `KEYCODE_LANGUAGE_SWITCH` 로 변환" 옵션을 둔다.
- 오토리피트: 호스트 OS가 만든 반복 keydown을 `repeat=true` 로 그대로 전달, 리시버는 그대로 주입.

### 5.2 마우스

- 이동은 **상대 이동량(dx, dy)** 으로 전송. 호스트는 가속 적용 전 raw 값을 캡처(Windows Raw Input,
  evdev REL_X/Y)하고, 리시버에서 속도 배율(설정) 적용 후 자기 커서에 반영.
- 버튼: Left/Right/Middle/Back/Forward, 휠: 수직·수평, 고해상도 휠(120 단위) 지원.
- 이동 이벤트는 1ms 단위로 합산(coalesce)해서 전송해 패킷 폭주를 막는다.

### 5.3 Android 리시버에서의 마우스 매핑

| 입력 | 특권 A1: UHID (기본) | 특권 A2: Inject | 접근성 모드 (폴백) |
|---|---|---|---|
| 이동 | 실제 HID 마우스 → **시스템 포인터** | `SOURCE_MOUSE` 주입 + 오버레이 커서 | 오버레이 커서 |
| 왼쪽 클릭 | 실제 클릭 | 마우스 클릭 이벤트 | 탭 제스처 |
| 드래그 | 실제 드래그 | 마우스 드래그 | 놓을 때 경로 재생 (P0) → 이어지는 제스처 (P1 검토) |
| 오른쪽 클릭 | 실제 보조 버튼 | 보조 버튼 | 뒤로가기 |
| 휠 | 실제 휠 | `AXIS_VSCROLL/HSCROLL` | 스와이프 제스처 |
| 키보드 | 실제 HID 키보드 (Android 물리 키보드 배열·키 반복 적용) | `KeyEvent` 주입 | keymanc IME (한글 조합은 자체 오토마타 필요) |

> 주입된 `MotionEvent` 는 시스템 포인터를 움직이지 않는다(포인터는 InputReader가 실제 장치에서만 그린다).
> 그래서 특권 모드의 기본을 `/dev/uhid` 가상 HID 장치로 바꿨다 (scrcpy의 UHID 모드와 같은 방식).

### 5.4 Stuck key / 안전장치

- 포커스를 떠날 때 호스트는 `ReleaseAll` 전송, 리시버는 자신이 누른 키/버튼을 모두 up 처리.
- 리시버는 **하트비트 타임아웃(기본 1.5s)** 또는 연결 끊김 시 자동 `ReleaseAll`.
- 호스트는 세션이 끊기면 즉시 캡처 해제 → 로컬 포커스 복귀.
- 리시버에서 주입한 키 상태를 추적하는 테이블을 유지(무엇을 눌렀는지 알아야 해제 가능).

---

## 6. 네트워크

### 6.1 탐색 (Discovery)

- mDNS/DNS-SD 서비스 `_keymanc._tcp.local`
  - TXT: `id`(기기 UUID), `name`, `os`, `roles`(host/receiver 대기 여부), `pv`(프로토콜 버전), `fp`(공개키 지문 앞 8바이트)
- 폴백: UDP 브로드캐스트 `255.255.255.255:<port>` 비콘 (mDNS 막힌 공유기 대비)
- 최후 수단: IP 직접 입력
- Android: `WifiManager.MulticastLock` 필요, 탐색 중에만 획득해 배터리 소모 최소화.
- 등록된 기기는 마지막 IP를 캐시 → 탐색 전에 먼저 직접 연결 시도.

### 6.2 전송

- **TCP 1개**, `TCP_NODELAY`, 기본 포트 `45877` (설정 가능).
- 프레임: `u16 길이 | Noise 암호문(postcard 직렬화 메시지)`.
- LAN에서 TCP 지연은 충분히 낮음(<1ms). 추후 필요하면 마우스 이동만 UDP로 분리 검토.
- 연결 방향: **호스트가 리시버에 접속** (리시버가 리스닝). 리시버가 등록된 호스트에만 응답.

### 6.3 보안

키 입력 = 비밀번호가 흐르는 채널이므로 **암호화·상호인증은 필수**.

1. 각 기기는 최초 실행 시 X25519 장기 키쌍 + UUID 생성 → OS 보안 저장소에 보관
   (Windows DPAPI, Android Keystore로 래핑, Linux Secret Service/파일 0600).
2. **페어링**: 리시버에서 "등록 대기" → 6자리 코드 표시(2분 유효).
   호스트에서 리시버 선택 → 코드 입력 → **SPAKE2**로 공유키 생성 → 그 위에서 서로의 장기 공개키 교환·확인 → 저장.
   - Android 호스트용 대안: 리시버 화면의 QR(주소+공개키+코드) 스캔.
3. **세션**: `Noise_KK_25519_ChaChaPoly_BLAKE2s` — 양쪽이 서로의 공개키를 이미 알고 있으므로 1-RTT 상호인증.
   모르는 키는 핸드셰이크 단계에서 거절.
4. 재생 방지·순서 보장은 Noise 논스 + TCP로 해결.
5. 페어링 해제 시 양쪽 레코드 삭제, 상대 기기에는 다음 연결 시 `Unpaired` 통지.

### 6.4 "같은 네트워크에서만" 강제

- 수신·발신 모두 상대 IP가 **사설/링크로컬 대역**(10/8, 172.16/12, 192.168/16, 169.254/16, fc00::/7, fe80::/10)이고
  **로컬 인터페이스 중 하나와 같은 서브넷**일 때만 허용.
- 리스너는 공인 IP 인터페이스에 바인딩하지 않음.
- NAT 트래버설, 릴레이, UPnP 포트 개방 없음.
- 옵션 "엄격 모드": IP TTL / Hop Limit = 1 → 라우터를 넘어가는 패킷 자체가 불가.

### 6.5 프로토콜 메시지 (초안)

```rust
enum Msg {
    // 세션
    Hello { proto_ver: u16, device_id: Uuid, name: String, os: Os, app_ver: String },
    Caps  { screens: Vec<Screen>, inject: InjectCaps, kbd_layout: String },  // 리시버 → 호스트
    Heartbeat { t: u64 },
    Bye { reason: ByeReason },
    Busy,

    // 포커스
    Enter { edge: Option<Edge>, pos_ratio: f32, modifiers: u32 },  // 호스트 → 리시버
    Leave,                                                          // 호스트 → 리시버
    EdgeHit { edge: Edge, pos_ratio: f32 },                         // 리시버 → 호스트 (경계 복귀)

    // 입력 (호스트 → 리시버)
    Key { usage: u16, down: bool, repeat: bool },
    MouseMove { dx: i16, dy: i16 },
    MouseButton { button: Button, down: bool },
    Wheel { v: i16, h: i16 },          // 120 = 1 notch
    ReleaseAll,

    // 확장 (P4)
    Clipboard { mime: String, data: Vec<u8> },
}
```

- `proto_ver` 가 다르면 `Hello` 단계에서 호환성 판정, 불가 시 UI에 업데이트 안내.

---

## 7. 플랫폼별 설계

### 7.1 Windows (호스트/리시버)

**Capture (호스트)**
- 키보드: `WH_KEYBOARD_LL` 훅. 원격 포커스 중엔 훅에서 `1` 반환 → 로컬 전달 차단.
- 마우스: `WH_MOUSE_LL` 로 차단 + **Raw Input(`WM_INPUT`)** 으로 가속 전 상대값 획득.
  원격 포커스 중에는 커서를 화면 중앙에 두고 `ClipCursor` 로 고정.
- 경계 감지: 로컬 포커스일 때 LL 훅의 커서 좌표 + 모니터 구성(`EnumDisplayMonitors`)으로 판정.
- 훅 콜백은 전용 스레드 + 메시지 루프에서 즉시 반환 (LL 훅 타임아웃 약 300ms 초과 시 OS가 훅을 제거하므로
  네트워크 전송은 채널로 넘기고 콜백에서 절대 블로킹하지 않음).

**Inject (리시버)**
- `SendInput` (키보드는 `KEYEVENTF_SCANCODE`, 마우스는 상대 이동 / 휠 `WHEEL`·`HWHEEL`).

**제약**
- UIPI: 관리자 권한 창에는 비관리자 프로세스가 훅/주입 불가 → "관리자 권한으로 실행" 옵션.
- 보안 데스크톱(UAC 프롬프트, Ctrl+Alt+Del, 잠금화면)은 캡처/주입 불가. `Ctrl+Alt+Del` 은 전달 불가.
- 세션 0 서비스로는 사용자 데스크톱 훅 불가 → **사용자 세션 프로세스**로 실행, 자동시작은 작업 스케줄러(로그온 시).
- 일부 구형 Intel 그래픽 드라이버는 `Ctrl+Alt+방향키`를 화면 회전에 쓴다. 훅이 먼저 삼키지만, 충돌 시 단축키 변경 옵션 필요.

### 7.2 Android — 리시버 (P1 핵심)

일반 앱은 다른 앱에 입력을 주입할 권한(`INJECT_EVENTS`)이 없으므로 두 가지 백엔드를 둔다.

**A. 특권 모드 (권장, 품질 최고)** — Shizuku
- Shizuku(무선 디버깅으로 기기 단독 활성화 가능, PC 불필요)를 통해 **shell 권한의 UserService** 실행.
- **A1. UHID (기본)**: shell 사용자가 `/dev/uhid` 로 가상 키보드·마우스를 만든다.
  Android 입장에서는 실제 USB/BT 장치와 같으므로 시스템 포인터, 키 반복, 물리 키보드 배열, 한/영 전환이 그대로 동작.
  프로토콜이 HID usage 기반이라 변환 없이 리포트로 전달된다.
- **A2. Inject (비교용)**: `InputManager(Global).injectInputEvent` 로 `KeyEvent`/`MotionEvent` 주입.
  시스템 포인터가 움직이지 않아 오버레이 커서가 필요.
- 앱 ↔ 특권 프로세스는 AIDL 없이 직접 작성한 Binder 트랜잭션(단방향 호출)으로 통신.
- 단점: 재부팅 후 Shizuku 재시작 필요(무선 디버깅 켜야 함). 앱에서 상태 감지 후 안내.

**B. 접근성 모드 (폴백, 설정 쉬움)**
- `AccessibilityService`: 오버레이 커서 + `dispatchGesture` 로 탭/드래그/스와이프, 글로벌 액션(뒤로/홈/최근앱).
- `InputMethodService`(keymanc 키보드): 사용자가 IME로 선택해야 키 입력 가능. 한글은 자체 조합 오토마타 필요.
- 한계: 호버 없음, 게임/일부 앱 제스처 차이, IME 전환 불편.

**공통**
- Foreground Service (`foregroundServiceType="connectedDevice"`, Android 14+) + 상시 알림(현재 상태/역할 전환 버튼).
- 수신 중에는 `WIFI_MODE_FULL_LOW_LATENCY` Wi-Fi 락을 잡아 절전으로 인한 지연 급증을 막는다.
- 배터리 최적화 예외 요청, 부팅 시 자동 시작(`BOOT_COMPLETED`, 설정 시).
- 화면 회전/해상도 변경 시 `Caps` 재전송.

### 7.3 Android — 호스트 (D8)

Android에 연결된 BT/USB **실제 키보드·마우스**를 다른 기기와 공유한다. 대상 기기에는 **블루투스 HID로 전달**한다.

```
실제 키보드·마우스 ──evdev(항상 grab)──▶ [특권 프로세스: HostRouter]
                                             ├─ 로컬 차례 ─▶ UHID 패스스루 장치 ─▶ 이 폰
                                             └─ 원격 차례 ─▶ (Binder) 앱 ─▶ BluetoothHidDevice ─▶ Windows 등
```

- **캡처 (Shizuku 필요)**: 특권 프로세스가 `/dev/input/event*` 중 키보드(문자키+스페이스 보유)와
  마우스(REL_X/Y + BTN_LEFT)만 골라 `EVIOCGRAB` 한다. 전원·볼륨 버튼(gpio-keys)과 keymanc 자신의 UHID 장치는 제외.
  - `Os.ioctlInt(fd, EVIOCGRAB)` 는 포인터(널 아님)를 인자로 넘기므로 grab으로 동작한다. 단 **풀기(ungrab)는 불가**하고 fd를 닫아야 풀린다.
  - 그래서 **항상 grab + 로컬은 UHID 패스스루**로 설계했다. 전환 시점에 눌려 있던 키의 뗌이
    누른 쪽으로 정확히 가므로(Windows 라우터와 같은 규칙) 어느 쪽에도 키가 눌린 채 남지 않는다.
  - 새로 연결된 장치는 2초마다 다시 스캔해서 잡는다. 특권 프로세스가 죽으면 커널이 grab을 풀어 장치는 정상으로 돌아온다.
- **캡처 대체 (Shizuku 없음) — 접근성 서비스**: Shizuku가 없으면 자동으로 이 방식을 쓴다.
  - 키보드: 접근성 키 필터링(`onKeyEvent`)이 모든 앱보다 먼저 키를 받는다. 로컬 차례면 소비하지 않고 통과시키므로 패스스루 장치가 필요 없다.
  - 마우스: 대상 차례일 때만 투명한 전체 화면 접근성 오버레이가 입력 포커스를 받고 **포인터 캡처**(`requestPointerCapture`)를 건다.
    폰 포인터가 숨겨지고 움직이지 않으며 상대 이동값을 그대로 받는다 (Android 8+).
    접근성 모션 가로채기(`setMotionEventSources`, Android 14+)만으로는 폰 포인터도 같이 움직여서(실기기 확인) 캡처 실패 시의 대체로만 쓴다.
- **전달 (Shizuku 불필요) — BLE HID(HOGP)가 기본**: 앱이 GATT 서버로 HID 서비스(0x1812)를 열고 광고한다.
  디스크립터는 UHID와 같은 것에 Report ID(1=키보드, 2=마우스)만 붙였다.
  - **클래식 HID(`BluetoothHidDevice`)는 기본에서 제외**: 폰이 HID 장치 역할로 L2CAP PSM 0x11/0x13을 점유해,
    **폰에 연결된 블루투스 클래식 키보드·마우스가 끊긴다** (P0-5 실기기에서 확인). 선택 사항으로만 남긴다.
  - BLE는 GATT를 쓰므로 폰의 블루투스 키보드·마우스와 충돌하지 않고, **여러 대상이 동시에 연결**된다.
    대상 조작 중 `Ctrl+Alt+→` 를 다시 누르면 다음 대상으로 **즉시 전환**(떠나는 대상의 눌린 키는 먼저 뗀다).
  - 대상은 키보드 입력 리포트 알림을 구독한 순간부터 "준비됨". 모든 HID 특성은 암호화 필수라 첫 접근 때 본딩된다.
  - 대상은 OS의 블루투스 설정에서 페어링만 하면 되고 **keymanc 앱이 필요 없다** (Windows·Mac·iPad·다른 Android).
  - 암호화는 블루투스 페어링이 담당한다 → 이 경로에는 Noise 불필요.
  - 마우스 이동은 8ms 단위로 합쳐서 보낸다 (블루투스 대역폭).
- **제약**
  - (클래식 모드만) 대상 전환은 재연결이 필요해 1~3초, 대상 1개.
  - 대상의 커서 위치를 알 수 없으므로 **단축키 전환만** 지원한다.
  - 일부 제조사는 HID Device 프로파일을 꺼 두었다 → 시작 시 감지해서 안내.
  - 키보드 페이지(0x07)에 없는 키(미디어 키 등)는 P0에서 로컬 패스스루되지 않는다.
- 대안 입력(Shizuku 없이): 화면을 가상 터치패드/키보드로 쓰는 "리모컨 모드" — 부가 기능으로 보류.

### 7.4 Linux (P3)

- Capture: evdev 읽기 + `EVIOCGRAB` (사용자를 `input` 그룹에 추가 또는 udev 규칙 설치).
- Inject: `/dev/uinput` 가상 키보드·마우스 (udev 규칙). X11/Wayland 모두 동작.
- 경계 감지: X11 → `XQueryPointer`; Wayland → `xdg-desktop-portal` **InputCapture**(GNOME 45+, KDE Plasma 6.1+) 지원 시 사용, 미지원이면 단축키만.
- 자동시작: systemd user unit + 트레이(StatusNotifierItem).

---

## 8. 백그라운드 동작

| 플랫폼 | 방식 |
|---|---|
| Windows | 창 닫으면 트레이로 최소화. 로그온 시 자동시작(작업 스케줄러). 훅 전용 스레드 상주 |
| Android | Foreground Service가 코어 실행. UI(Activity)는 없어도 동작. 알림에서 역할 전환/일시정지 |
| Linux | systemd user service + 트레이 |

- UI 프로세스와 엔진을 분리하지 않고(Desktop은 동일 프로세스), Android만 Service가 엔진 소유.
- "일시 정지" 토글: 캡처/주입을 멈추되 페어링·연결은 유지.

---

## 9. 코어 상태 머신

### 9.1 Host

```
          start(host)
Idle ──────────────▶ Listening(탐색 + 등록 리시버에 연결 유지)
                        │
                        │ trigger(target=R)  [R 세션 Ready]
                        ▼
                  Remote(R) ── 캡처 ON, 입력 → R
                   │   │  ▲
    trigger(local) │   │  │ trigger(target=R2)
    / EdgeHit      │   └──┘ (ReleaseAll → R, Enter → R2)
    / R 연결끊김    ▼
                  Local ── 캡처 OFF (경계 모드면 경계 감시만)
```

- 세션별 상태: `Disconnected → Connecting → Handshaking → Ready → (Active)`; 끊기면 지수 백오프 재연결(최대 5s).

### 9.2 Receiver

```
Idle ─▶ Advertising(mDNS 광고, 리스닝)
          │ 등록된 호스트 연결 + Noise OK
          ▼
        Connected ──Enter──▶ Active(주입 ON) ──Leave/타임아웃──▶ Connected (ReleaseAll)
```

---

## 10. 로드맵

| 단계 | 범위 | 완료 기준 |
|---|---|---|
| **P0 — PoC** | ① Windows LL훅 캡처+차단 ② Android Shizuku 마우스/키 주입 ③ Android 접근성 주입 ④ Android evdev grab 가능성 ⑤ Android 호스트 → 블루투스 HID | 각각 단독 데모, 지연 측정 |
| **P1 — MVP** | Windows 호스트 → Android 리시버, 단축키 전환, mDNS, 페어링(SPAKE2), Noise 세션, 트레이·Foreground Service | 한 대의 PC로 Android 폰 조작, 한/영 포함 타이핑 |
| **P2** | Android 호스트 → 블루투스 대상(Windows 등), Windows ↔ Windows, 화면 경계 모드 + 레이아웃 편집기 | 경계로 자연스럽게 이동/복귀 |
| **P3** | Linux 호스트/리시버 (X11, Wayland 포털) | 3개 OS 상호 조합 동작 |
| **P4** | 클립보드 텍스트 공유, 멀티 리시버 레이아웃 고도화, 접근성 모드 한글 IME | — |

---

> P0 이후 Android 제품 범위·일정은 [ANDROID_PRODUCT.md](ANDROID_PRODUCT.md), 결정 경위는 [HISTORY.md](HISTORY.md).

## 11. 결정 사항 (P0 시작 시 확정)

| ID | 질문 | 결정 |
|---|---|---|
| D1 | 코어 언어 | Rust 코어 + Kotlin(Android) + Tauri(Desktop UI) |
| D2 | 전환 트리거 | 둘 다 지원. P1은 단축키, P2에 화면 경계 추가 |
| D3 | Android 리시버 주입 방식 | Shizuku 특권 모드(UHID) 기본 + 접근성 폴백 |
| D4 | 한 기기의 동시 호스트+리시버 활성 | 불허 (실행 시 하나 선택, 트레이에서 즉시 전환) |
| D5 | 리시버의 다중 호스트 동시 연결 | 1개만 활성, 나머지 `Busy` |
| D6 | "같은 네트워크" 판정 수준 | 사설대역 + 동일 서브넷 기본, TTL=1 엄격모드는 옵션 |
| D7 | 기본 단축키 | `Ctrl+Alt+←/→` 순환, `Ctrl+Alt+숫자` 지정, `Ctrl+Alt+Shift+Esc` 비상복귀 |
| D8 | Android의 역할별 방식 | **호스트**: 실제 키보드·마우스 캡처(Shizuku, 없으면 접근성) → **BLE HID**로 전달 (대상은 앱 불필요, 여러 대상 즉시 전환). **리시버**: Wi-Fi + Shizuku UHID |

## 12. 리스크

- **Android 주입 권한**: 특권 모드는 Shizuku 설정 허들이 있고, 접근성 모드는 품질이 떨어짐 → P0에서 두 방식 모두 검증.
- **Android 호스트 evdev grab**: 제조사/버전별 shell 권한 차이 가능 → PoC 결과에 따라 Android 호스트 범위 조정.
- **Play 스토어 정책**: 접근성 API를 보조 기능 목적 외로 쓰면 심사 거절 가능 → 초기엔 GitHub/APK 배포 가정.
- **Windows 관리자 창/보안 데스크톱**: OS 제약으로 불가, 문서화하고 UI에 안내.
- **mDNS 차단 네트워크**(공용 Wi-Fi, AP 격리): 브로드캐스트/직접 IP 폴백, AP 격리 시 원천 불가 안내.
