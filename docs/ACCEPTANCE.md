# 최소 수용시험

## 계약 conformance

- Android·iPhone·Windows가 `digest-v1-memo-only.json`과
  `digest-v1-one-photo-metadata.json`의 canonical bytes·길이·SHA-256을 정확히 재현합니다.
- JSON key 순서, multipart boundary와 filename을 바꿔도 digest가 바뀌지 않습니다.
- 사진 배열 순서, memo byte 또는 photo hash가 한 byte 달라지면 digest가 달라집니다.
- 대문자·다른 version·경로문자 UUID, 존재하지 않는 date/time, UTF-8 8,192 bytes 초과,
  whitespace memo+사진 0장과 중복 photo ID를 세 플랫폼이 같은 오류로 거부합니다.

## 프로젝트·권한 분리

- `WorkCadence`와 application ID, 앱명, 아이콘, 저장소와 키가 다릅니다.
- Transfer APK/IPA에 근태, 위치, Wi-Fi, 전화, 통화기록, 연락처, 메일, 구인 코드가 없습니다.
- Android merged manifest와 DEX, iOS entitlements를 실제 산출물에서 검사합니다.

## 텍스트·5장 foreground 전송

- 메모만, 사진 1장, 사진 5장이 foreground에서 성공합니다.
- 사진만 1~5장과 메모+사진 1~5장이 성공하고, 메모·사진 모두 빈 제출은 거부됩니다.
- 새 메모의 `targetDate`는 서울 오늘 날짜이며 상태·담당자·반복 필드가 없습니다.
- 6장, 장당/전체 용량, 해상도 상한 초과는 네트워크 전 또는 bounded streaming 중 거부합니다.
- 앱이 background로 가면 새 전송을 시작하지 않고 현재 상태를 안전하게 pending으로 보존합니다.
- 전송 중 background 또는 process kill이면 Windows에는 ready 0건 또는 완전한 1건만 있고,
  모바일 encrypted pending은 정확한 ACK 전까지 남습니다.
- foreground 이탈 callback 뒤에는 추가 송신 byte가 0이며 진행 중 request를 abort합니다.
- unknown code, 누락 필드, 비JSON, redirect와 잘못된 content type 응답은 성공으로 취급하지
  않고 pending을 유지합니다.

## 직접 촬영 무갤러리

- Android MediaStore와 iOS PhotoKit에 촬영 전후 새 asset 0건입니다.
- 앱 저장영역·backup·log에서 JPEG·메모 평문 검색 결과 0건입니다.
- EXIF/GPS/XMP, 원본 파일명과 thumbnail이 정본·로그에 없습니다.

## 카카오톡·문자 공유수신 무갤러리

- Android 실제 카카오톡·문자 앱에서 다운로드 없이 `공유 → WorkCadenceTransfer`가 동작합니다.
- iPhone 실제 카카오톡·메시지에서 Share Extension이 동작합니다.
- Android 공유 수신 화면과 iPhone Share Extension은 import·암호화만 하며 네트워크 요청은
  0건입니다. 본 앱 foreground의 명시적 전송만 허용됩니다.
- 성공·실패·취소 뒤 사용자 갤러리에 새 asset 0건입니다.
- provider가 직접 공유를 제공하지 않는 버전은 다운로드로 우회하지 않고 지원 HOLD로 표시합니다.

## READY ACK와 삭제

- PC가 완전수신·검증·원자 게시하기 전에는 ACK와 모바일 삭제가 0건입니다.
- READY ACK 뒤에는 모바일 key, blob, thumbnail, queue payload가 모두 사라집니다.
- 다른 단말·제출 ID·digest의 ACK, 필드 누락 ACK, 일반 HTTP 200 응답으로는 모바일 삭제가
  0건입니다.
- PC 꺼짐, Wi-Fi 단절, 앱 crash, 마지막 chunk 뒤 crash에서는 encrypted pending이 남습니다.
- ready rename 뒤 ACK 유실 시 재전송해도 Windows 정본은 한 건이고 같은 ACK를 받습니다.

## 중복·오작동·자원경계

- 빠른 다중 탭은 한 요청만 시작하고, 같은 batch 반복·동시 제출에서도 정본 1건입니다.
- 전송 중 연결을 끊은 뒤 같은 제출 전체를 재시도해도 partial ready 0건·완전한 정본 1건입니다.
- 같은 ID에 다른 bytes/hash는 conflict로 닫히며 overwrite 0건입니다.
- malformed JPEG, pixel bomb, 경로조작, UNC·junction·reparse, disk full을 fail-closed로 처리합니다.
- client `submissionId`에 경로문자·비정규 UUID를 넣어도 staging/ready 경로에 반영되지 않으며,
  서로 다른 단말의 같은 submission ID는 서로 다른 Windows record에 매핑됩니다.
- 중복 photo ID, metadata/photo part 누락·추가·중복, oversized header·boundary·body를
  staging 게시 전에 거부합니다.

## Windows 암호화 정본

- 정본과 manifest가 제출별 AES-GCM으로 암호화되어 평문 검색 결과 0건입니다.
- 본문 수신 전에 Windows가 `recordId`와 record key를 생성하고, upload 도중·중단·crash의
  `.staging/<uploadId>`에서도 JPEG magic·memo 평문 검색 결과가 0건입니다.
- 사진은 한 장씩 bounded process memory에서 검증·재인코딩되고 encrypted staging만 남습니다.
  encoded·decoded hard cap 초과 blob은 쓰지 않고, 앞서 쓴 encrypted staging은 key-first로
  정리해 잔존 0건·READY 0건으로 닫힙니다.
- 키는 정본 폴더와 backup에 없고 OS 보호 저장소에만 있습니다.
- BitLocker·NTFS ACL 활성 상태와 backup/cloud sync 제외를 실제 PC에서 확인합니다.
- 허가되지 않은 Windows 계정은 ready 파일을 읽거나 바꿀 수 없습니다.
- receiver 실행 계정과 DPAPI 키 범위가 일치하며 v1에 Windows service가 없습니다.

## 외부 통신

- 승인된 사내 RFC1918 PC 외 DNS, HTTP, cloud, analytics, crash reporting 요청이 0건입니다.
- 공인 IP, hostname, loopback, 다른 subnet과 port forwarding을 거부합니다.
- 등록 QR의 인증서 SPKI pin이 다르거나 단말 secret·scope가 없거나 revoke 상태이면 전송과
  staging write가 0건입니다.
- receiver 인증서는 설치별 한 번 생성되며 새 QR을 만들어도 기존 SPKI pin이 바뀌지 않습니다.
  명시적 인증서 교체 뒤에는 기존 단말이 모두 실패하고 재등록 전에는 fallback하지 않습니다.
- 1회 등록값은 10분 뒤 만료되고 최초 성공에 소진됩니다. 재사용과 enrollment ID·secret
  불일치는 credential 발행과 write가 0건이며 같은 `ENROLLMENT_REJECTED`만 반환합니다.
- 등록 성공 뒤 모바일 credential은 Keystore/Keychain 보호 아래에만 있고 Windows에는 secret
  원문 없이 DPAPI 보호 verifier·scope·revoke 상태만 있습니다.
- NBSP·전각공백 memo와 사진 0장, duplicate `memo`·`photos` JSON key를 세 플랫폼이 동일하게
  거부합니다.

## 암호화 container

- 동일 key+nonce 재사용 0건, nonce·tag·AAD 한 byte 변조 시 복호화와 READY가 0건입니다.
- wrapped key만 또는 ciphertext만 남긴 crash를 재시작하면 partial READY가 0건입니다.
- 모바일 pending과 Windows 정본 모두 평문 temp 없이 partial→flush→atomic rename을 지킵니다.
