# 목표 프로젝트 구조

```text
WorkCadenceTransfer/
├─ AGENTS.md
├─ SECURITY.md
├─ apps/
│  ├─ android/          # 별도 Android Studio project
│  ├─ ios/              # 별도 Xcode project + Share Extension
│  └─ windows-receiver/ # foreground receiver와 암호화 정본 viewer
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

현재는 문서와 schema 준비 단계이므로 `apps/` 소스 디렉터리를 만들지 않습니다.
