# 전송 프로토콜 v1

상태: 구현 전 고정 계약

## 상태 흐름

```text
ENCRYPTED_PENDING
  -> SENDING_ONE_SUBMISSION
  -> WINDOWS_READY
  -> AUTHENTICATED_READY_ACK
  -> PURGE_REQUIRED
  -> MOBILE_PURGED
```

허용 제출은 `메모만`, `JPEG 1~5장만`, `메모 + JPEG 1~5장`입니다. 실패·PC 종료·
foreground 이탈·네트워크 단절·ACK 유실 시 모바일은 같은 `submissionId`와 같은
`contentDigest`의 암호화 pending을 보존합니다.

## 1. PC 등록

등록은 Windows receiver 화면에서 사용자가 직접 시작할 때만 열립니다.

1. Windows는 receiver 설치별 TLS 인증서·private key를 최초 한 번 생성해 DPAPI로 보호하고
   계속 재사용합니다. QR을 만들 때는 256-bit random `enrollmentSecret`만 새로 만들고
   [pairing QR schema](../contracts/pairing-qr-v1.schema.json)의 JSON을 표시합니다.
2. QR의 `endpoint`는 사설 IPv4의 HTTPS origin이고 path·query·fragment·userinfo가 없습니다.
   `spkiSha256`은 receiver 인증서 SPKI의 SHA-256 base64url 값입니다.
3. 등록값은 발급 뒤 10분, 최초 성공 1회만 유효합니다. 새 QR을 만들면 이전 미사용 등록값은
   즉시 폐기합니다.
4. 모바일은 QR에서 읽은 SPKI를 먼저 pin한 TLS 연결에서만
   `POST /v1/enrollments/{enrollmentId}/complete`를 보냅니다.
5. 요청 header는 `Authorization: WCTEnrollment <enrollmentSecret>`이고 body는
   [enrollment request schema](../contracts/enrollment-request-v1.schema.json)를 따릅니다.
6. Windows가 등록 ID·secret·만료·미사용 상태를 검증한 뒤 설치별 `deviceId`와 256-bit random
   `deviceSecret`을 발급합니다. 응답은
   [enrollment result schema](../contracts/enrollment-result-v1.schema.json)를 따릅니다.
7. 모바일은 endpoint·SPKI pin·device ID·secret을 Android Keystore 또는 iOS Keychain
   ThisDeviceOnly 보호 아래 보관합니다. Windows는 secret 원문이 아니라 SHA-256 verifier,
   `transfer_upload` scope, enabled/revoked 상태를 DPAPI 보호 등록부에 저장합니다.

등록 성공은 HTTP 200, `Content-Type: application/json`과 enrollment result schema가 모두 맞을
때만 인정합니다. 잘못된 enrollment ID·secret, 만료와 재사용은 원인을 구분해 노출하지 않고
`ENROLLMENT_REJECTED`, `retryable=false`로 답합니다. malformed body는
`INVALID_SUBMISSION`입니다. 인증서는 자동 회전하지 않으며 사용자가 명시적으로 교체하면 기존
SPKI pin을 폐기하고 모든 단말을 다시 등록해야 합니다.

등록값·device secret·Authorization header는 파일, 화면 재표시, 로그, crash report와
클립보드에 남기지 않습니다. IP·기기 이름·body의 ID만으로 단말을 신뢰하지 않습니다.

## 2. 업로드 인증

제출 요청 header는 다음과 같습니다.

```text
Authorization: WCTDevice <deviceId>.<deviceSecret>
Content-Type: multipart/form-data; boundary=<boundary>
```

Windows는 header로 인증된 단말 등록부의 `deviceId`, enabled/revoked 상태와
`transfer_upload` scope를 먼저 검증합니다. 그 뒤 metadata의 `deviceId`가 인증 principal과
정확히 같은지 확인합니다. 하나라도 다르면 body를 staging에 쓰기 전에 거부합니다.

## 3. 제출 상한과 의미 검사

- `deviceId`, `submissionId`, `photoId`: lowercase canonical UUID v4
- `createdAt`: UTC millisecond 형식 `YYYY-MM-DDTHH:mm:ss.SSSZ`
- `targetDate`: draft 생성 시 `Asia/Seoul` 오늘 날짜, `YYYY-MM-DD`
- `memo`: CRLF와 CR을 LF로 바꾼 뒤 Unicode NFC, UTF-8 최대 8,192 bytes
- `photos`: 사용자 선택 순서의 JPEG 0~5장, 서로 다른 `photoId`
- 사진 한 장: 재인코딩 뒤 1~5 MiB
- 사진 전체: 최대 25 MiB
- 해상도: 최대 12 MP, 한 변 최대 4,096 px

메모의 정규화 결과가 Unicode whitespace 기준으로 비어 있고 사진도 0장이면 거부합니다.
JSON Schema의 `format`, `maxLength`와 `x-*`만 신뢰하지 않고 Android·iPhone·Windows가 모두
[semantic validation](../contracts/SEMANTIC_VALIDATION.md)을 실행합니다.

## 4. multipart 요청

```text
POST /v1/submissions/{submissionId}
```

- path의 `submissionId`와 metadata의 값은 정확히 같아야 합니다.
- boundary는 정규식 `^[A-Za-z0-9_-]{16,70}$`만 허용합니다.
- 전체 request body hard cap은 26,500,000 bytes입니다.
- part header는 part당 최대 8,192 bytes, part 수는 최대 6개입니다.
- 첫 part는 정확히 하나인 `name="metadata"`, `Content-Type: application/json`이며
  body는 최대 16,384 bytes입니다.
- 이후 part는 metadata `photos` 배열과 같은 순서로 정확히 하나씩 존재하는
  `name="photo"`, `Content-Type: image/jpeg`, `Content-ID: <photoId>`입니다.
- wire의 `Content-ID` 값은 꺾쇠를 실제로 포함한 `<lowercase-canonical-uuid-v4>` 형식이며,
  꺾쇠 안 UUID가 metadata `photoId`와 같아야 합니다.
- 누락·추가·중복 part, 중복 `Content-ID`, 배열 순서 불일치와 trailing part를 거부합니다.
- `filename` parameter가 있더라도 Windows는 해석·저장·로그하지 않습니다.
- 30초 동안 body byte가 들어오지 않거나 요청 시작 10분이 지나면 요청을 중단하고 staging을
  폐기합니다.

Windows는 선언 bytes·MIME·사진 hash·decoded pixel과 전체 digest를 streaming 상한 안에서
다시 검증합니다. 사진은 한 장씩 bounded process memory에서 decode·방향 적용·metadata 제거
재인코딩한 뒤 암호문으로만 staging에 씁니다. 저장용 재인코딩 bytes·hash는 Windows encrypted
manifest에 기록하며 wire `contentDigest`는 인증된 client 제출의 idempotency identity로
유지합니다. foreground를 벗어난 모바일은 socket을 취소하며 그 callback 이후 새 byte를 보내지
않습니다. Windows는 완성되지 않은 staging을 READY로 보이지 않습니다.

## 5. canonical content digest

`contentDigest`는 아래 byte sequence의 SHA-256 lowercase hex입니다. JSON key 순서,
multipart boundary와 filename은 digest에 참여하지 않습니다.

- 모든 정수는 unsigned big-endian입니다.
- `LP(value)`는 `UTF-8 byte length(u32) || UTF-8 bytes`입니다.
- 사진 SHA-256은 hex 문자열이 아니라 decoded raw 32 bytes를 넣습니다.
- 사진 순서는 metadata `photos` 배열의 사용자 선택 순서입니다.

```text
ASCII "WCT1"                         # 4 bytes
0x01                                 # protocol version, 1 byte
LP(deviceId)
LP(submissionId)
LP(createdAt)
LP(targetDate)
LP(normalizedMemo)
photoCount                            # u8
repeat photoCount times:
  LP(photoId)
  LP("image/jpeg")
  declaredPhotoBytes                  # u64
  decodedPhotoSha256                  # 32 bytes
```

`contentDigest` 필드 자체는 canonical bytes에 넣지 않습니다. Windows는 요청을 인증한 뒤 실제
photo bytes로 각 hash와 전체 digest를 다시 계산합니다. 고정값은
[contract test vectors](../contracts/test-vectors/README.md)로 세 플랫폼에서 동일하게 검증합니다.

## 6. Windows staging과 경로

client가 보낸 ID·파일명·경로는 filesystem 경로에 사용하지 않습니다.

1. 인증·scope·metadata 검사가 끝나면 인증된 `(deviceId, submissionId)`의 서버측
   lock/transaction에서 기존 READY의 동일/충돌을 body 수신 전에 판정합니다.
2. 기존 READY가 없는 신규 제출에만 multipart body를 쓰기 전에 random UUID v4 `uploadId`,
   최종 `recordId`와 random record key를 생성합니다.
3. memo와 사진 평문은 bounded process memory에만 머물며, 검증·정규화한 결과를 즉시
   `.staging/<uploadId>`의 AES-GCM 암호문으로 저장합니다. 평문 staging file은 금지합니다.
4. 검증이 모두 끝나면 encrypted staging directory를 `ready/<recordId>`에 exclusive·atomic
   게시합니다. 검증 실패·취소·timeout이면 key를 먼저 폐기하고 staging을 정리합니다.
5. 암호화 idempotency index에 `(deviceId, submissionId, contentDigest, recordId, storedAt)`을
   저장합니다. ready와 index 중 하나만 남은 crash는 startup reconciliation으로 READY 노출 전에
   복구하거나 fail-closed 정리합니다.
6. 두 단말이 같은 `submissionId`를 보내도 인증된 device ID가 다르므로 다른 record가 됩니다.

## 7. READY ACK와 중복방지

Windows가 암호화 정본을 flush·close하고 ready 게시와 idempotency reconciliation 정보를
내구 저장한 뒤에만 [READY ACK schema](../contracts/ready-ack-v1.schema.json)를 반환합니다.

- 같은 `(deviceId, submissionId)`와 같은 digest의 재시도는 새 record를 만들지 않고 최초
  ACK와 모든 schema field·값이 같은 ACK를 반환합니다. 특히 `recordId`와 `storedAt`을 새로
  만들지 않으며 JSON key 순서는 비교하지 않습니다.
- 같은 key에 다른 digest가 오면 `SUBMISSION_CONFLICT`로 영구 거부하고 overwrite하지 않습니다.
- 동일 key의 최초 요청이 아직 진행 중이면 `SUBMISSION_IN_PROGRESS`로 재시도 안내합니다.
- 모바일은 pinned TLS 응답의 `deviceId`, `submissionId`, `contentDigest`, `recordId`, `storedAt`,
  `state`를 pending과 schema에 맞춰 모두 검증합니다.
- HTTP 200, socket close, redirect, 일반 `accepted`, 필드 누락·추가·불일치 응답으로는 모바일
  원본을 삭제하지 않습니다.

정확한 ACK 뒤 모바일은 먼저 `PURGE_REQUIRED`를 내구 저장하고 wrapped data key를 삭제한 다음
ciphertext·thumbnail·queue payload를 반복 정리합니다. 일부 정리가 실패하면 성공으로 숨기지
않고 다음 foreground 시작 때 마칩니다.

## 8. 오류 응답과 전송 중단

서버가 응답할 수 있는 실패는 [error schema](../contracts/error-v1.schema.json)와
[error codes](../contracts/error-codes.md)를 사용합니다. memo·filename·path·secret·provider
원문을 오류에 넣지 않습니다.

- `retryable=true`: 암호화 pending을 유지하고 사용자가 foreground에서 재시도합니다.
- `retryable=false`: 자동 재시도하지 않지만 사용자가 확인·삭제하기 전까지 암호화 원본을
  유지합니다.
- 비JSON, schema 불일치, 알 수 없는 code, redirect, 잘못된 content type과 응답 단절은
  성공이 아니며 안전하게 pending을 유지합니다.
- 사용자가 취소하거나 앱이 background로 가면 전송을 abort하고 pending을 유지합니다.
  서버는 partial staging을 삭제하며 READY/ACK를 만들지 않습니다.

PC 정본은 WorkCadenceTransfer가 임의 자동삭제하지 않습니다. 모바일 사본 삭제와 Windows
업무 정본의 보존 정책은 서로 다른 경계입니다.
