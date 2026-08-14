# 고정 test vectors

현재 고정된 값:

1. `digest-v1-memo-only.json`: Unicode 메모, canonical bytes, SHA-256, 최초 ACK와 모든
   schema field·값이 같은 중복 ACK
2. `digest-v1-one-photo-metadata.json`: CRLF→LF 정규화, 사진 ID·MIME·u64 bytes·raw SHA-256의
   canonical 순서

Android·iPhone·Windows는 fixture를 재직렬화해 `canonicalHex`, byte length와
`contentDigest`가 모두 같아야 합니다. JSON을 그대로 hash하거나 platform 기본 endian을 쓰면
실패해야 합니다.

중복 READY ACK는 최초 ACK와 모든 schema field·값이 같아야 하며 `recordId`와 `storedAt`을
새로 만들지 않습니다. JSON object key 순서와 raw response byte 순서는 비교하지 않습니다.

사진 수용시험은 개인정보 없는 프로그램 생성 JPEG로 아래 wire fixture를 추가합니다.

- 실제 JPEG 1장과 5장의 multipart fixture
- 같은 제출에 대한 ACK 유실·재시도
- 한 byte가 다른 `SUBMISSION_CONFLICT`
- EXIF·GPS 제거 전후와 malformed/pixel-limit 부정 fixture

현재 Windows receiver의 사진 정규화 구현은 정책 문서와 실제 multipart smoke test로
검증했습니다. Android·iPhone 연동 전에는 위 binary fixture를 고정해 플랫폼 간 수용시험의
차단 기준으로 사용합니다. 텍스트 메모 단계는 계속 `digest-v1-memo-only.json`을 차단
기준으로 사용합니다.
