# 보안·저장 경계

## 승인 권한

### Android

- 허용: `INTERNET`
- 사진 단계에서 허용: `CAMERA`
- Android 17·target SDK 37 이상에서 사설망 접근 권한이 실제로 요구되면
  `ACCESS_LOCAL_NETWORK`만 추가 승인합니다. 그 전 SDK에는 선제 선언하지 않습니다.
- 금지: 위치, Wi-Fi 관측, 전화상태, 통화기록, 연락처, SMS 읽기, 전체 사진첩 읽기,
  알림, 부팅수신, foreground service, background location
- WorkManager와 background transfer dependency를 포함하지 않습니다.

### iPhone

- 허용: Camera, Local Network, Share Extension의 image input
- App Group은 Share Extension과 본 앱 사이의 암호화 pending 전달에만 사용합니다.
- Share Extension은 전달받은 bytes를 공유 container에 평문으로 내려놓지 않고 즉시
  streaming 암호화합니다. `UserDefaults`에는 내용·원본 파일명·키를 저장하지 않습니다.
- Share Extension은 네트워크를 사용하지 않습니다. 전송은 본 앱 foreground에서만 합니다.
- 금지: Photo Library 전체권한, 위치, 연락처, Background Modes, push, analytics

### Windows

- 사용자가 승인한 로컬 `C:` NTFS 하위 폴더만 사용합니다.
- UNC, device path, drive root, reparse point와 junction을 거부합니다.
- listener는 승인된 RFC1918 interface에만 foreground로 열고 public bind·port forwarding을
  사용하지 않습니다.
- Windows가 만든 수신기 인증서의 SPKI 지문과 1회 등록값을 QR로 전달합니다. 모바일은 그
  인증서를 정확히 pin하며 trust-all, 인증서 경고 무시와 평문 HTTP를 금지합니다.

## 데이터 최소화

- 제출 metadata: `submissionId`, `createdAt`, 필수 `targetDate`, 메모, 사진 0~5장의
  `photoId`, byte length, MIME, SHA-256만 사용합니다.
- client 원본 파일명·경로, EXIF, GPS, XMP, thumbnail, provider message ID를 정본이나
  로그에 저장하지 않습니다.
- 사진은 방향을 적용해 JPEG로 다시 인코딩합니다.
- 감사로그에는 제출 ID, 단말 ID, 파일 개수·총 bytes, 결과코드와 시각만 기록합니다.

## 모바일 보관

- 제출별 random AES-256-GCM data key를 사용합니다.
- 제출 manifest·memo와 각 사진 blob마다 서로 다른 random 96-bit nonce, 128-bit tag를
  사용하며 같은 key+nonce 조합을 절대 재사용하지 않습니다.
- AAD에는 `WCTENC1`, 저장영역(`mobile-pending`), device ID, submission ID, blob 종류,
  photo ID 또는 `none`, content digest를 길이-prefix로 넣습니다. 하나라도 다르면 복호화를
  거부합니다.
- 모바일 AAD byte 순서는 `ASCII WCTENC1 || LP(mobile-pending) || LP(deviceId) ||
  LP(submissionId) || LP(blobKind) || LP(photoId 또는 none) || digest raw 32 bytes`입니다.
  `LP`는 전송 프로토콜과 같은 u32 big-endian UTF-8 길이-prefix입니다.
- Android Keystore 또는 iOS Keychain의 기기 종속 키로 data key를 보호합니다.
- ciphertext와 작은 index는 app-private/no-backup 영역에 둡니다.
- 평문 temp, gallery insert, cloud backup, OS thumbnail 생성을 금지합니다.
- ciphertext는 `.partial`에 쓰고 flush·close 뒤 atomic rename한 다음에만 index에 노출합니다.
  wrapped key·ciphertext 중 하나만 남은 crash 상태는 다음 foreground 시작에서 fail-closed로
  정리하거나 복구합니다.
- Windows `READY_ACK` 전에는 원본을 보존하고, ACK 뒤 data key를 먼저 지운 다음 파일·index를
  반복 정리합니다.

## Windows 정본

- 인증·scope·metadata 검사와 기존 READY/충돌 dedupe 판정이 끝난 신규 제출에만, body를 쓰기
  전에 Windows가 `uploadId`, 최종 `recordId`와 제출별 random AES-256-GCM key를 생성합니다.
  두 ID는 client가 정하거나 볼 수 없습니다.
- memo는 8 KiB bounded memory에서, 사진은 한 장씩 encoded·decoded hard cap이 있는 process
  memory에서 hash·JPEG decode·pixel·metadata를 검사하고 방향 적용·metadata 제거 재인코딩을
  마친 뒤 즉시 `.staging/<uploadId>`의 암호문으로 씁니다. JPEG·memo 평문을 filesystem temp,
  swap용 자체파일, preview와 thumbnail에 쓰지 않습니다.
- manifest·memo와 각 정규화된 저장 사진마다 서로 다른
  random 96-bit nonce·128-bit tag를 사용합니다. AAD에는 `WCTENC1`, `windows-ready`,
  `recordId`, device ID, submission ID, blob 종류와 content digest를 넣습니다.
- Windows AAD byte 순서는 `ASCII WCTENC1 || LP(windows-ready) || LP(recordId) ||
  LP(deviceId) || LP(submissionId) || LP(blobKind) || LP(photoId 또는 none) ||
  digest raw 32 bytes`입니다.
- 각 암호 blob과 wrapped key index를 encrypted partial→flush·close→atomic rename 순서로 내구
  저장하고, startup reconciliation에서 둘 중 하나만 남은 staging을 READY로 노출하지 않습니다.
- 검증 실패·취소·timeout이면 wrapped key를 먼저 폐기하고 encrypted staging을 반복 정리합니다.
  crash 뒤 남은 staging도 UI·backup에 노출하기 전에 같은 순서로 정리합니다.
- 최종 경로는 Windows가 만든 `ready/<recordId>`이며 client가 보낸 ID·파일명·경로를 경로에
  넣지 않습니다.
- Windows 정본 키는 receiver를 실행하는 동일 Windows 사용자 범위의 DPAPI로 감쌉니다.
  v1은 Windows service를 만들지 않으며 다른 실행 계정과 키 저장소를 공유하지 않습니다.
- BitLocker는 디스크 도난을, NTFS ACL은 로컬 계정 접근을 제한합니다.
- 평문 미리보기·temp·thumbnail·backup·cloud sync를 만들지 않습니다.
- PC 정본은 앱이 임의 자동삭제하지 않으며 승인된 문서 보존·폐기 규정에 따릅니다.

### 쉬운 뜻

- BitLocker: PC나 SSD가 분실돼도 Windows 잠금 없이 C: 내용을 읽기 어렵게 하는 디스크 암호화
- NTFS ACL: 같은 PC의 다른 Windows 계정이 수신 폴더를 열거나 바꾸지 못하게 하는 권한
- DPAPI: 정본 암호키를 receiver를 실행한 Windows 계정에 묶어 보호하는 기능

이 세 항목은 사용자가 기술적으로 판정하는 질문이 아닙니다. 설치·수용 단계에서 개발자가 실제
설정과 다른 계정 접근 차단을 검사해 PASS/FAIL 증거로 제시합니다.

## 인증과 폐기

- 단말마다 서로 다른 32-byte 등록 비밀값과 `transfer_upload` scope를 사용합니다.
- receiver TLS private key와 device credential registry는 receiver를 실행하는 Windows 계정의
  DPAPI 범위로 보호합니다. Windows는 device secret 원문 대신 SHA-256 verifier만 보관하고
  constant-time으로 비교합니다.
- 분실·교체 단말은 해당 단말만 revoke합니다.
- 기존 WorkCadence Galaxy pairing secret을 승계하거나 공유하지 않습니다.
- secret, memo, 사진 hash와 provider 오류원문을 로그·화면·클립보드에 노출하지 않습니다.
- 공유한 원본이 카카오톡·문자 provider, 송신자 휴대폰 또는 OS 임시영역에 남는지는 이 앱이
  통제할 수 없습니다. 앱이 보장하는 범위는 새 갤러리 asset을 만들지 않고, 앱이 만든 사본을
  암호화하며, 정확한 READY ACK 뒤 그 사본을 삭제하는 데까지입니다.
