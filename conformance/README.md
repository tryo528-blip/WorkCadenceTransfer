# 계약 conformance harness

`conformance/`는 Android·iPhone·Windows 구현이 같은 v1 계약을 지키는지 확인하기 위한
Python 표준 라이브러리 기반 참조 구현입니다. 운영 앱의 runtime dependency나 전송 서버가
아닙니다.

## 실행

저장소 루트에서 다음을 실행합니다.

```powershell
python -m unittest discover -s tests -v
```

외부 패키지 설치 없이 실행됩니다.

## 포함된 검증

- 현재 6개 JSON Schema의 object, array, const, enum, required, additionalProperties,
  pattern, limit, allOf/anyOf/oneOf와 local `$ref` 구조
- 모든 JSON object의 중복 key 거부
- lowercase canonical UUID v4, 실제 UTC millisecond timestamp와 Gregorian date round-trip
- CRLF·CR → LF 후 Unicode NFC, memo UTF-8 8,192-byte 경계와 고정 whitespace 집합
- 사진 수·장당 bytes·전체 bytes·MIME·중복 photo ID·선택적 body length/hash 검사
- `WCT1` canonical bytes, u32/u64 big-endian, raw SHA-256와 고정 test vector digest

## 구현 경계

고정 vector에는 JPEG binary가 없으므로 body 검사는 선택적입니다. 실제 Windows receiver와
모바일 앱은 이 harness 결과에 더해 pairing·ACK·multipart 상태, bounded JPEG decode,
orientation 적용, EXIF/GPS/XMP 제거, pixel 상한, 암호화·원자 저장을 해당 구현 단계에서
별도 수용시험으로 통과해야 합니다.
