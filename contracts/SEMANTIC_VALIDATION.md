# Semantic validation v1

JSON Schema 검사는 첫 번째 문턱일 뿐입니다. `format`과 `x-*` keyword를 무시하는 validator도
있으므로 Android·iPhone·Windows의 production code가 아래 규칙을 직접 검사해야 합니다.

## 공통 문자열

- UUID는 lowercase canonical UUID v4 정규식과 일치하고 parse→format 결과가 원문과 같아야
  합니다. 경로문자, 대문자, 다른 UUID version과 nil UUID를 거부합니다.
- UTC 시각은 실제 존재하는 시각이어야 하며 정확히 `YYYY-MM-DDTHH:mm:ss.SSSZ`로 round-trip
  되어야 합니다. offset 표기, leap second와 존재하지 않는 날짜를 거부합니다.
- `targetDate`는 실제 Gregorian 날짜이고 정확히 `YYYY-MM-DD`로 round-trip 되어야 합니다.
- digest와 photo hash는 lowercase 64자리 hex이며 decode 결과가 정확히 32 bytes여야 합니다.
- 256-bit secret과 SPKI hash는 padding 없는 base64url로 decode했을 때 정확히 32 bytes여야
  합니다.

## 메모와 사진

1. memo의 `CRLF`와 단독 `CR`을 `LF`로 바꿉니다.
2. 결과를 Unicode NFC로 정규화합니다.
3. 정규화 결과의 UTF-8 길이가 8,192 bytes 이하여야 합니다.
4. 공백 판정 집합은 `U+0009–000D`, `U+0020`, `U+0085`, `U+00A0`, `U+1680`,
   `U+2000–200A`, `U+2028`, `U+2029`, `U+202F`, `U+205F`, `U+3000`으로 고정합니다.
   memo의 모든 scalar가 이 집합이면 사진이 1장 이상이어야 합니다. 플랫폼 기본 `trim`에
   의존하지 않으며, 이 판정은 memo 내용을 잘라 저장하거나 digest를 바꾸지 않습니다.
5. 사진 배열은 0~5장이고 모든 `photoId`가 서로 달라야 합니다.
6. 각 선언 bytes는 1~5,242,880이고 합계는 26,214,400 이하여야 합니다.
7. MIME은 정확히 `image/jpeg`이며 실제 body의 byte length·SHA-256·JPEG decode·pixel 상한이
   metadata와 모두 맞아야 합니다. Windows 저장용 재인코딩 규칙은
   [사진 JPEG 정규화 정책](../docs/03_PHOTO_NORMALIZATION_POLICY.md)에 따릅니다.

정규화된 memo와 metadata 사진 배열 순서를 canonical digest와 Windows 정본에 사용합니다.
UI 문자열을 다시 trim하거나 사진을 정렬해 digest를 바꾸지 않습니다.

## 인증과 endpoint

- pairing endpoint는 `https://<RFC1918 IPv4>:<port>` origin만 허용합니다.
- 허용 IPv4는 `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`이며 hostname, IPv6,
  loopback, link-local, public IP와 userinfo·query·fragment를 거부합니다.
- path는 비어 있거나 `/`만 허용하고 port는 1024~65535입니다.
- 모바일은 QR의 SPKI SHA-256 pin을 TLS request 전에 적용하며 trust-all fallback을 두지 않습니다.
- 등록 secret은 10분 expiry와 미사용 상태를 먼저 확인하고 최초 성공 transaction에서
  소진합니다.
- 제출은 Authorization으로 인증된 server registry의 device ID와 metadata `deviceId`가
  정확히 같아야 합니다. scope와 revoke 상태는 body가 아니라 registry 값을 사용합니다.
- 인증·scope·body ID 검사는 multipart body를 staging에 쓰기 전에 끝냅니다.

## multipart와 응답

- metadata·등록·ACK·오류를 포함한 모든 wire JSON은 token parse 단계에서 같은 object 안의
  중복 key를 거부합니다. first-wins나 last-wins parser 결과를 사용하지 않습니다.
- request path의 `submissionId`, metadata 값과 canonical digest 입력이 모두 같아야 합니다.
- metadata의 사진 배열과 photo part의 개수·순서·Content-ID는 일대일로 일치해야 합니다.
- client filename은 입력값으로 인정하지 않고 저장·로그하지 않습니다.
- READY ACK와 error는 각 schema를 `additionalProperties=false`로 검사합니다.
- READY ACK의 모든 identity field가 local pending과 같고 pinned TLS가 유효할 때만 purge합니다.
- 알 수 없는 code, schema 밖 필드, 비JSON, redirect와 body 단절은 성공이 아닙니다.

## 필수 부정 fixture

- 경로문자·대문자·v1 UUID와 존재하지 않는 날짜
- 한글 등 multi-byte memo의 UTF-8 8,192-byte 경계와 초과 1 byte
- NBSP·전각공백만 있는 memo + 사진 0장, duplicate `memo`·`photos` JSON key
- 중복 photo ID, 사진 bytes 합계 초과, metadata/part 순서 불일치
- 다른 인증 단말의 body device ID
- public endpoint, 잘못된 pin·secret, 만료·재사용 enrollment
- extra field가 있는 READY ACK와 digest·record ID가 다른 ACK

세 플랫폼의 semantic validator가 같은 fixture에 같은 PASS/오류코드를 내기 전에는 wire 구현을
완료로 판정하지 않습니다.
