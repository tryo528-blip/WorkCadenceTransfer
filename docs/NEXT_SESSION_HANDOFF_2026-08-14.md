# WorkCadenceTransfer 다음 세션 핸드오프

기준일: 2026-08-14

상태: `PRE-IMPLEMENTATION · CONTRACT_BASELINE_SEALED`

Git: 독립 로컬 저장소 `main`, 첫 commit `df08bb14caab8f4d76af02883ba2c8d5d0dec543`, remote 없음

작업 경로: `C:\marco\WorkCadenceTransfer`

## 세션 시작 문장

> `C:\marco\WorkCadenceTransfer`만 작업한다. `C:\marco\WorkCadence`와 다른 업무
> 프로젝트는 열거나 수정하지 않는다. AGENTS, README와 docs/00~02, ACCEPTANCE, contracts를 먼저
> 읽고 구현 범위를 벗어나지 않는다.

## 확정 범위

- 별도 Android·iPhone·Windows receiver 제품
- 메모만, 정규화된 JPEG 사진만 1~5장, 또는 메모+사진 1~5장. 빈 제출 금지
- 직접 촬영은 갤러리 미저장
- 메시지 앱의 공유수신은 즉시 암호화 import만 수행
- 전송은 본 앱 foreground의 명시적 버튼에서만 수행
- PC의 encrypted READY와 정확히 일치하는 인증 ACK 뒤 모바일 사본 삭제
- Windows 암호화 정본은 자동삭제하지 않고 별도 보존 규정에 따름
- Windows가 만든 `recordId`만 최종 `ready/<recordId>` 경로에 사용. client submission ID·
  파일명·경로는 filesystem 경로에 사용하지 않음
- 외부 업무데이터 서버·cloud·후속 시스템 직접 연결 0건

## 현재 만들어진 것

- 범위·보안·프로토콜·수용시험 문서
- submission·등록 QR·등록 결과·READY ACK·오류 JSON schema와 semantic validation 계약
- memo-only와 photo metadata canonical digest 고정 test vector
- HTML 프로젝트 분리 설명서
- 문서·계약 baseline 24개 파일의 첫 local commit

앱 소스, signing 설정, 실제 인증서·키, APK·IPA·EXE는 아직 없습니다. 문서가 구현 완료를
뜻하지 않습니다.

## 다음 구현 순서

1. schema·semantic validator·canonical digest conformance test harness 작성
2. Windows foreground receiver의 TLS pin 등록·단말별 scope·encrypted
   `.staging/<uploadId>`→`ready/<recordId>` 구현. upload 중에도 평문 temp 0건
3. Android 메모 전용 foreground 제출
4. Android 직접촬영·Sharesheet 암호화 import·사진 5장 제출
5. ACK 유실·다중 탭·disk full·crash·잘못된 ACK 수용시험
6. Android 실기기 승인 뒤 같은 contract의 iPhone 앱·Share Extension 구현
7. 기능 완료 뒤 Figma 디자인, 실화면 승인, 별도 저장소 봉인

## 만들지 않을 것

- 기존 WorkCadence 소스 의존·submodule·secret 재사용
- Kakao Channel bot·Quick Tunnel·Downloads CSV
- background worker/service/push
- chunk resume, Bonjour, OCR, cloud relay, 후속 시스템 자동연결

## 시작 전 확인

- 독립 Git 저장소 `main`의 branch/status와 remote 미설정 상태 확인
- 문서·schema diff와 미추적 파일 확인
- 실제 개인정보·사진·키·인증서가 Git에 없는지 확인
- 기능 또는 권한을 늘려야 하면 구현 전에 사용자 승인을 다시 받기
