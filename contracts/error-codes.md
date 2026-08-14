# 전송 오류코드 v1

## 재시도 가능

| code | 의미 |
| --- | --- |
| `PC_UNAVAILABLE` | 등록 PC에 연결할 수 없음 |
| `TRANSFER_INTERRUPTED` | foreground 전송 중 연결이 끊김 |
| `STORAGE_BUSY` | Windows 저장소를 일시적으로 사용할 수 없음 |
| `READY_ACK_LOST` | 저장 완료 여부를 확인하지 못해 같은 제출로 재시도 필요 |
| `SUBMISSION_IN_PROGRESS` | 같은 단말·제출 ID의 최초 요청이 아직 처리 중임 |

## 영구 거부

| code | 의미 |
| --- | --- |
| `INVALID_SUBMISSION` | schema 또는 UTF-8 byte 상한 불일치 |
| `INVALID_MEDIA` | 허용되지 않거나 손상된 이미지 |
| `RESOURCE_LIMIT_EXCEEDED` | 장수·bytes·pixel 상한 초과 |
| `CONTENT_DIGEST_MISMATCH` | 선언 digest와 실제 content 불일치 |
| `SUBMISSION_CONFLICT` | 같은 단말·제출 ID에 다른 content가 옴 |
| `DEVICE_UNAUTHORIZED` | 미등록·폐기 단말 또는 잘못된 secret |
| `SCOPE_DENIED` | `transfer_upload` 권한 없음 |
| `TLS_PIN_MISMATCH` | 등록한 PC 인증서와 다름 |
| `ENROLLMENT_REJECTED` | 등록 ID·secret 불일치, 만료 또는 이미 사용된 등록값 |

`PC_UNAVAILABLE`, `TRANSFER_INTERRUPTED`, `READY_ACK_LOST`, `TLS_PIN_MISMATCH`는 모바일이
자체 판정하는 로컬 오류라 서버 JSON 응답이 없을 수 있습니다. 서버가 JSON으로 응답하는 code와
`retryable` 값은 `error-v1.schema.json`과 일치해야 합니다.

모바일은 재시도 가능 오류에서 encrypted pending을 유지합니다. 영구 거부에서도 사용자가
명시적으로 삭제하기 전까지 원본을 지우지 않으며, 정확히 일치하는 `READY_ACK`만 자동삭제를
허용합니다.
