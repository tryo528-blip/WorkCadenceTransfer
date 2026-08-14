# WorkCadence에서 분리할 경계

## 새 프로젝트가 이어받는 결정

- 별도 Android application과 iPhone application·Share Extension
- 메모만, 직접 촬영·명시적으로 공유한 사진만 1~5장, 또는 메모+사진 1~5장. 빈 제출 금지
- 외부 업무데이터 서버 없이 등록한 사내 Windows receiver로만 foreground 전송
- 암호화 mobile pending, 단말별 등록, 중복방지, staging→ready, 정확한 READY ACK 뒤 폰 사본 삭제
- Windows가 만든 `recordId`의 `ready/<recordId>` 암호화 정본 보관. client ID·파일명은 경로에
  사용하지 않음

## 복사하지 않는 기존 코드

- `WorkCadence` 개인 Galaxy 앱의 Wi-Fi 근태, 통화 분류, 구인공고, WorkManager queue
- Windows 근태·메일·구인·Drive·종료 UI와 IPC
- 기존 Galaxy pairing secret과 wire event schema
- 과거 Kakao Channel bot, skill HTTP server, Quick Tunnel, Downloads CSV exporter
- 과거 할일·루틴·업무달력 코드와 데이터 계약

새 프로젝트는 기존 런타임 package나 Git submodule을 참조하지 않습니다. 필요 계약은 이
프로젝트의 schema와 개인정보 없는 고정 test vector로 독립 구현합니다.
