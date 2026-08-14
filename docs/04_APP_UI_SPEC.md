# WorkCadenceTransfer 앱 UI 명세 v1

상태: Figma 설계 완료 · Android Compose debug slice 구현 완료 · 실기기 수용시험 전

## 기준 디자인

- [Figma 앱 UI 설계 v1](https://www.figma.com/design/GeG2CW7KCN8W01SvqWkXbD)
- 시각 기준은 이 저장소 안의 [프로젝트 분리 설명서](PROJECT_SPLIT_DECISION.html)에 있는
  `파란 신뢰 + 흰 카드` 스타일입니다. 다른 업무 프로젝트의 소스·자산·토큰은 가져오지
  않았습니다.

## 화면 흐름

```text
HOME 작성
  ├─ PC 등록 → ENROLL → HOME
  ├─ JPEG 선택/공유 import → PREVIEW
  └─ PC로 보내기 → PROGRESS → READY
                         └─ 실패 → 같은 submissionId·digest 재시도
```

### HOME · 작성

- 오늘 날짜와 PC 등록 상태를 먼저 보여줌
- 메모 한 칸, JPEG 사진 추가, 앱 전용 암호화 보관 안내
- 빈 제출은 버튼을 비활성화하고 semantic validator에서도 거부

### PREVIEW · 사진

- 선택 순서를 유지해 1~5장의 JPEG를 보여줌
- 원본 파일명은 UI·metadata·multipart에 사용하지 않음
- EXIF/GPS/XMP/thumbnail 제거와 저장용 JPEG 정규화는 Windows receiver 경계에서 수행

### ENROLL · PC 등록

- Windows receiver가 표시한 pairing QR JSON을 붙여넣는 임시 경로
- QR 카메라 스캔은 권한·실기기 수용시험 전까지 열지 않음
- 등록 요청은 pinned TLS와 RFC1918 IPv4 endpoint 검사 뒤에만 전송

### PROGRESS · 전송

- foreground 화면을 유지한 동안에만 전송
- pending 저장 → TLS multipart 전송 → READY ACK 검증 순서
- 실패·ACK 유실이면 같은 `submissionId`와 `contentDigest`를 유지하고 원본을 보존

### READY · 완료

- `transfer_ready_ack`, `accepted=true`, `state=READY`와 모든 identity field를 확인한 뒤
  Android Keystore 키를 먼저 폐기하고 pending ciphertext를 삭제
- Windows의 암호화 정본은 이 앱이 삭제하지 않음

## 디자인 토큰

| 역할 | 값 |
| --- | --- |
| 기본 배경 | `#F4F7FB` |
| 본문 잉크 | `#10213D` |
| 보조 문장 | `#5F6F88` |
| 주 파랑 | `#2457D6` |
| 주 파랑 배경 | `#EAF0FF` |
| 성공 | `#087A5B` / `#E5F7F1` |
| 경고 | `#A45E00` / `#FFF3D9` |
| 카드 | `#FFFFFF` |
| 선 | `#DBE3EF` |
| Figma 폰트 | `Noto Sans KR` |
| Android 폰트 | 시스템 sans-serif fallback |

## 현재 Android 구현 경계

구현 파일은 [`apps/android`](../apps/android/README.md) 아래에 있으며, 현재 자동검증은
Compose compile, WCT1 고정 digest vector, semantic validator, debug APK assemble까지
포함합니다. 카메라 직접 촬영, QR 카메라 스캔, 실제 Galaxy·KakaoTalk·문자·Windows TLS
연동, ACK 유실·disk full·crash 수용시험은 실전 테스트 단계에서 별도로 확인합니다.
