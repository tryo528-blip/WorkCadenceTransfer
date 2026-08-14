# WorkCadenceTransfer 작업 규칙

- 작업 경계는 `C:\marco\WorkCadenceTransfer` 하나입니다. 다른 프로젝트·저장소를 열거나
  수정하지 않습니다.
- 시작할 때 `README.md`, `docs/00_DECISIONS.md`, `docs/01_SECURITY_BOUNDARY.md`,
  `docs/02_TRANSFER_PROTOCOL.md`, `docs/ACCEPTANCE.md`와 최신 handoff를 먼저 읽습니다.
- 근태·Wi-Fi·통화·메일·구인·Drive 코드를 복사하거나 runtime dependency·secret을 공유하지
  않습니다.
- 외부 업무데이터 서버, cloud relay, background transfer와 후속 시스템 API·DB 직접 연결을
  추가하지 않습니다.
- 구현·검수·Git 상태를 분리해 보고하고, 사용자 승인 전 commit·push·봉인을 하지 않습니다.
- 기능과 권한을 늘려야 하면 코드 변경 전에 이유·위험·대안을 먼저 보고해 승인받습니다.
