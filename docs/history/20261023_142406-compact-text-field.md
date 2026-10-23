# 입력창 높이 줄이기

## 왜
입력창(프로필·기기 이름, 로그 메모, 고급 설정 숫자)이 높아 보임: Material `OutlinedTextField`는 최소 56dp, 글자 위아래 여백 16dp.

## 어떻게
- `CompactTextField`(Common.kt): `BasicTextField` + `OutlinedTextFieldDefaults.DecorationBox`로 같은 테두리·색·플레이스홀더,
  위아래 여백만 12dp(가로 12dp)로 줄여 48dp(터치 영역 권장 크기는 유지).
- 이름 입력 대화상자, 로그 메모, 고급 설정 숫자 칸에 적용. 모두 한 줄 입력이라 `singleLine` 고정.
