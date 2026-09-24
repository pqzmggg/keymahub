# KeymaHub

같은 네트워크(LAN)에 있는 Windows / Android / Linux 기기끼리 하나의 키보드·마우스를 공유하는 앱.

- 서버 없음, 같은 네트워크 안에서만 동작
- 호스트(입력 보내는 쪽)가 리시버(입력 받는 쪽)를 등록
- 단축키 또는 화면 경계로 입력 대상 전환
- 1순위 지원: Windows ↔ Android

## 문서
- 설계: [docs/DESIGN.md](docs/DESIGN.md)
- 진행 기록 (결정·발견 로그): [docs/HISTORY.md](docs/HISTORY.md)
- P0 기술 검증 가이드·결과: [docs/P0.md](docs/P0.md)
- Android 제품 기획: [docs/ANDROID_PRODUCT.md](docs/ANDROID_PRODUCT.md)

## 현재 단계: KeymaHub (Android 블루투스 허브) v0.1 개발

| 경로 | 내용 |
|---|---|
| `core/proto` | 와이어 프로토콜 (P0: 평문 v0) |
| `core/keymap` | USB HID ↔ Windows / Linux 키 코드 변환표 |
| `poc/win-capture` | Windows 전역 입력 캡처·차단·전송 |
| `poc/kmc` | 테스트 도구: `dump` / `demo` / `ping` |
| `android/app` | P0 테스트 콘솔 (리시버 UHID·Inject·접근성, BLE 호스트, evdev 프로브) |
| `android/keymahub` | **KeymaHub** 제품 앱: 폰의 키보드·마우스를 BLE로 PC·태블릿에 (기본 `Shift+Alt+1` 이 폰, `Shift+Alt+2~9, 0` 기기; 보조키 변경·프로필·6개 언어) |

```sh
cargo test --workspace                          # Rust 테스트
cargo build --release -p win-capture -p kmc     # Windows 도구
cd android && ./gradlew testDebugUnitTest assembleDebug   # P0 앱 + KeymaHub
```

## KeymaHub 릴리스

GitHub에서 `v1.2.3` 형식 태그로 릴리스를 publish하면 `.github/workflows/release.yml`이
업로드 키로 서명한 `KeymaHub-1.2.3.apk`(직접 설치용)와 `KeymaHub-1.2.3.aab`(Play 업로드용)를 그 릴리스에 첨부한다.
**Pre-release**로 체크하면(태그 `v1.2.3` 또는 `v1.2.3-beta.1`) 대신 디버그 빌드 `KeymaHub-dev-1.2.3.apk`를 첨부한다:
앱 이름 **KeymaHub(dev)**, 앱 ID `com.keymahub.app.dev` 라 정식 앱과 나란히 설치되고, 같은 업로드 키로 서명해 다음 pre-release로 덮어 설치된다.
versionName은 태그, versionCode는 `major*10000 + minor*100 + patch`.

업로드 키는 저장소에 넣지 않고 Actions secrets로 둔다 (한 번만):

```sh
keytool -genkeypair -v -keystore keymahub-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 keymahub-upload.jks   # → KEYMAHUB_KEYSTORE_BASE64 (Ubuntu: sudo apt install openjdk-17-jdk-headless)
```

| Secret | 값 |
|---|---|
| `KEYMAHUB_KEYSTORE_BASE64` | 위 base64 출력 |
| `KEYMAHUB_KEYSTORE_PASSWORD` | 키스토어 비밀번호 |
| `KEYMAHUB_KEY_ALIAS` | `upload` |
| `KEYMAHUB_KEY_PASSWORD` | 키 비밀번호 (선택: JDK 9+ keytool 기본 PKCS12 키스토어는 키스토어 비밀번호와 같음 — 비워 두면 그 값을 씀) |

키스토어 파일과 비밀번호는 따로 안전하게 보관한다 (잃어버리면 Play 업로드 키 재설정 절차가 필요).
예전 이름(`KEMAHUB_*`)으로 등록한 secret도 그대로 읽는다. secrets가 없으면 워크플로는 디버그 서명본을 올리지 않고 실패한다.

