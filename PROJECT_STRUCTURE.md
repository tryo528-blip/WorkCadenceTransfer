# 목표 프로젝트 구조

```text
WorkCadenceTransfer/
├─ AGENTS.md
├─ SECURITY.md
├─ apps/
│  ├─ android/          # 별도 Android Studio project
│  ├─ ios/              # 별도 Xcode project + Share Extension
│  └─ windows-receiver/ # .NET foreground receiver와 암호화 정본 viewer
├─ contracts/
│  ├─ submission-v1.schema.json
│  ├─ ready-ack-v1.schema.json
│  ├─ pairing-qr-v1.schema.json
│  ├─ enrollment-request-v1.schema.json
│  ├─ enrollment-result-v1.schema.json
│  ├─ error-v1.schema.json
│  ├─ SEMANTIC_VALIDATION.md
│  ├─ error-codes.md
│  └─ test-vectors/
├─ conformance/
│  ├─ __init__.py
│  ├─ digest.py          # canonical digest v1 reference implementation
│  ├─ schema.py          # duplicate-key loader와 contract schema subset validator
│  ├─ semantic.py        # 공통 semantic validation reference implementation
│  └─ README.md
├─ tests/
│  └─ test_contract_conformance.py
├─ docs/
│  ├─ 00_DECISIONS.md
│  ├─ 01_SECURITY_BOUNDARY.md
│  ├─ 02_TRANSFER_PROTOCOL.md
│  ├─ ACCEPTANCE.md
│  ├─ MIGRATION_SOURCE_BOUNDARY.md
│  ├─ NEXT_SESSION_HANDOFF_2026-08-14.md
│  └─ PROJECT_SPLIT_DECISION.html
└─ README.md
```

## 구현 순서

1. 계약 schema, canonical digest와 test vector 고정
2. Windows foreground receiver·평문 temp 없는 encrypted
   `.staging/<uploadId>`→`ready/<recordId>`·idempotency 구현
3. Android 텍스트 메모 v1
4. Android 직접촬영 1~5장과 Sharesheet 암호화 가져오기
5. 실제 Galaxy·카카오톡·문자·Windows 조합 수용시험
6. 동일 계약의 iPhone 앱·암호화 가져오기 전용 Share Extension
7. 기능 완료 후 Figma 디자인과 실화면 승인
8. 각 프로젝트별 별도 build·서명·manifest·보안 packet 발행

현재 `apps/windows-receiver/`에는 .NET 8 foreground receiver의 최소 vertical slice가
있습니다. TLS 인증서·등록정보는 Windows DPAPI로 보호하고, 메모 제출은 암호화 정본으로
원자 저장합니다. 사진 정규화와 viewer, Android·iPhone 앱은 아직 구현하지 않았습니다.
`conformance/`는 계속 표준 라이브러리만 사용하는 참조 검증 코드이며 운영 runtime이
아닙니다.
