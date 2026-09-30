# 접근성 권한: KeymaHub 항목으로 바로 이동

- 왜: 설정의 접근성 첫 화면으로만 이동해서, 어디서 KeymaHub를 켜야 하는지 찾기 어려움 (갤럭시는 '설치된 앱' 안쪽에 있음).
- 어떻게: `android.settings.ACCESSIBILITY_DETAILS_SETTINGS` + `EXTRA_COMPONENT_NAME`(KeymaHub 접근성 서비스)로 KeymaHub의 켜기/끄기 화면을 바로 엶.
  - 이 화면을 열 수 없는 기기: 접근성 목록을 열되 `:settings:fragment_args_key`로 KeymaHub 항목까지 스크롤·강조 (지원하는 설정 앱에서만).
