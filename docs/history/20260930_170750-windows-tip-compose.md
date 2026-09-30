# Windows '모든 장치 표시' 안내: 캡처 이미지 대신 화면으로 그림

- 왜: 캡처 이미지는 한국어 Windows 화면 그대로라 다른 언어에서 어색하고, 크기도 커서 눈에 걸림.
- 어떻게: `WindowsAddDevice()`(`Pairing.kt`)가 Windows '디바이스 추가' 창 윗부분을 비슷하게 그림.
  - 배경 #2A2A2A, 흰 글자, 제목 18sp / 본문 10sp (원본 비율 유지, 조금 작게), 최대 폭 320dp.
  - 문구는 각 언어의 Windows 표기(`windows_add_device`, `windows_add_device_body`, `windows_show_all`), 6개 언어.
  - 튜토리얼 팁과 페어링 모드 안내에서 같은 컴포저블을 씀. `drawable-nodpi/tutorial_windows_show_all.png` 삭제.
