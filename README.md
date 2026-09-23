# keymanc

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

## 현재 단계: KemaHub (Android 블루투스 허브) v0.1 개발

| 경로 | 내용 |
|---|---|
| `core/proto` | 와이어 프로토콜 (P0: 평문 v0) |
| `core/keymap` | USB HID ↔ Windows / Linux 키 코드 변환표 |
| `poc/win-capture` | Windows 전역 입력 캡처·차단·전송 |
| `poc/kmc` | 테스트 도구: `dump` / `demo` / `ping` |
| `android/app` | P0 테스트 콘솔 (리시버 UHID·Inject·접근성, BLE 호스트, evdev 프로브) |
| `android/kemahub` | **KemaHub** 제품 앱: 폰의 키보드·마우스를 BLE로 PC·태블릿에 (`Ctrl+Alt+1~9` 대상, `Ctrl+Alt+0` 이 폰) |

```sh
cargo test --workspace                          # Rust 테스트
cargo build --release -p win-capture -p kmc     # Windows 도구
cd android && ./gradlew testDebugUnitTest assembleDebug   # P0 앱 + KemaHub
```
