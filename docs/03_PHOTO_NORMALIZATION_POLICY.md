# 사진 JPEG 정규화 정책 v1

상태: Windows receiver 구현 기준 고정

이 정책의 목적은 원본 사진을 보관하는 것이 아니라, 수신한 JPEG를 검증한 뒤 개인정보성
메타데이터를 제거한 저장용 JPEG를 만드는 것입니다. `contentDigest`는 wire metadata의
identity이므로 저장용 재인코딩 결과가 달라도 같은 제출 재시도 판정은 바뀌지 않습니다.

## 입력 검사

- part의 `Content-Type`과 metadata `mime`은 정확히 `image/jpeg`이어야 합니다.
- JPEG SOI와 유효한 frame header를 먼저 확인합니다. JPEG가 아니거나 헤더가 잘린 경우
  `INVALID_MEDIA`로 거부합니다.
- metadata `bytes`와 실제 part byte 수가 같아야 하며, metadata `sha256`은 실제 wire part의
  SHA-256과 같아야 합니다. 다르면 `CONTENT_DIGEST_MISMATCH`입니다.
- wire photo는 장당 최대 5 MiB, 전체 최대 25 MiB입니다. filename은 읽거나 저장하거나
  로그하지 않습니다.

## 픽셀과 방향

- JPEG header에서 전체 decode 전에 width·height를 확인합니다.
- width와 height는 각각 최대 4,096px이고, width × height는 최대 12MP입니다. 초과 시
  자동 축소하지 않고 `RESOURCE_LIMIT_EXCEEDED`로 거부합니다.
- EXIF Orientation이 없으면 1로 봅니다. 1~8 이외의 값이나 깨진 Orientation 값은
  `INVALID_MEDIA`로 거부합니다.
- Orientation 2~8의 좌우반전·회전은 픽셀에 적용한 뒤 저장합니다. 저장 결과의 방향은
  항상 정상 방향(Orientation 1)입니다.

## 저장용 JPEG

- 디코드한 이미지를 8-bit RGB로 새 bitmap에 그린 뒤 JPEG quality 90으로 재인코딩합니다.
- EXIF, GPS, XMP, IPTC, comment, embedded thumbnail과 원본 filename은 저장하지 않습니다.
- 저장용 JPEG도 최대 5 MiB여야 하며 초과 시 `RESOURCE_LIMIT_EXCEEDED`입니다.
- 저장용 bytes와 SHA-256, 실제 width·height·적용한 Orientation은 encrypted manifest에
  기록합니다. 원본 wire bytes는 정본으로 보관하지 않습니다.
- 사진은 한 장씩 bounded process memory에서 decode·재인코딩하고, 평문 photo temp file은
  만들지 않습니다. 최종 bytes만 record key로 암호화해 `ready/<recordId>`에 저장합니다.

## digest와 재시도

`contentDigest`에는 기존 contract대로 metadata의 `photoId`, `mime`, wire `bytes`, wire
`sha256`이 들어갑니다. 저장용 재인코딩 bytes/hash는 digest를 다시 만들 때 사용하지
않습니다. 따라서 같은 wire 제출은 같은 digest·같은 READY를 받고, 저장용 이미지의 변환은
encrypted manifest의 storage metadata로 확인합니다.

## 오류와 수용시험

- malformed JPEG, frame header 부재, 잘못된 EXIF Orientation: `INVALID_MEDIA`
- 장당/전체 wire bytes, width·height·pixel 수, 저장용 bytes 초과:
  `RESOURCE_LIMIT_EXCEEDED`
- metadata와 실제 part의 bytes/hash 불일치: `CONTENT_DIGEST_MISMATCH`
- Content-ID, part 순서·개수·MIME 불일치: `INVALID_SUBMISSION`

실제 baseline/progressive JPEG, EXIF 1~8, GPS/XMP 포함 JPEG, malformed JPEG, pixel-limit
JPEG, 저장 결과 5 MiB 초과 fixture를 Android·iPhone·Windows 수용시험에 추가합니다.
