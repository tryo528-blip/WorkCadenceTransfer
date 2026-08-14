# WorkCadenceTransfer

휴대폰에서 작성한 메모와 사용자가 선택한 사진을 사내 Windows PC로 안전하게 전달하는
독립 프로젝트입니다.

이 프로젝트는 `C:\marco\WorkCadence`의 하위 모듈이 아닙니다. 근태, Wi-Fi 감지, 통화,
메일, 구인공고 코드를 참조하거나 포함하지 않습니다.

## 확정 범위

- Android 앱을 먼저 구현하고 같은 wire contract로 iPhone 앱을 구현합니다.
- 메모는 할일 관리 화면이 아닌 자유로운 텍스트 한 칸이며, 생성 시 서울 오늘 날짜를
  `targetDate`로 붙입니다. 상태·담당자·반복 규칙은 만들지 않습니다.
- 메모만, 사진만 1~5장, 또는 메모+사진 1~5장을 한 제출 건으로 전송하며 빈 제출은
  거부합니다.
- 직접 촬영은 갤러리에 저장하지 않습니다.
- 카카오톡·문자에서 받은 사진은 다운로드 대신 `공유 → WorkCadenceTransfer`로 가져옵니다.
- Android 공유 수신 화면과 iPhone Share Extension은 선택 자료를 즉시 암호화해 가져오기만
  하며, 실제 PC 전송은 본 앱을 연 뒤 사용자가 누른 경우에만 시작합니다.
- 전송은 사용자가 화면을 열어 둔 foreground에서만 실행합니다.
- Windows가 완전수신·해시검증·원자 저장을 끝내고 인증된 `READY_ACK`를 반환한 뒤에만
  모바일 암호화 원본을 삭제합니다.
- Windows가 만든 `recordId`의 `ready/<recordId>`에 암호화 업무 정본 한 건만 보관하며
  client submission ID·파일명은 경로에 쓰지 않습니다. 정본은 앱이 자동삭제하지 않습니다.
- PC 등록 QR로 TLS 인증서 지문과 단말별 등록키를 나눠 받아, 등록된 PC와 단말 사이에서만
  전송합니다.
- 외부 업무데이터 서버, cloud relay, 광고·분석·crash SDK를 사용하지 않습니다.

## 비범위

- 근태, Wi-Fi 감지, 통화상태·통화기록, 연락처, 메일, 구인공고
- Kakao Channel bot·skill server와 Downloads CSV 중계
- 백그라운드 자동전송, 부팅 자동실행, push notification
- 끊긴 지점부터 이어 보내기, PC 자동검색, 여러 PC 동시전송
- OCR, 자동 문서분류, 얼굴·생활환경 판별
- 후속 업무시스템 API·DB 직접 연결

## 문서

- [확정 결정](docs/00_DECISIONS.md)
- [보안·저장 경계](docs/01_SECURITY_BOUNDARY.md)
- [전송 프로토콜](docs/02_TRANSFER_PROTOCOL.md)
- [수용시험](docs/ACCEPTANCE.md)
- [의미 검증 계약](contracts/SEMANTIC_VALIDATION.md)
- [계약 conformance harness](conformance/README.md)
- [기존 프로젝트와의 이관 경계](docs/MIGRATION_SOURCE_BOUNDARY.md)
- [다음 세션 핸드오프](docs/NEXT_SESSION_HANDOFF_2026-08-14.md)
- [프로젝트 구조](PROJECT_STRUCTURE.md)
- [보안 보고 경계](SECURITY.md)
- [HTML 설명서](docs/PROJECT_SPLIT_DECISION.html)

상태: `CONTRACT-BASELINE-IMPLEMENTED · DOTNET-RECEIVER-VERTICAL-SLICE`. Python conformance
harness와 테스트가 있고, .NET 8 Windows foreground receiver의 TLS·1회 등록·단말 scope·
메모 제출·암호화 READY·idempotency 골격이 구현되어 Release 빌드까지 확인되었습니다.
아직 signing 설정·배포 산출물·receiver UI·사진 JPEG 정규화·실기기 수용시험은 남아 있습니다.
