# Android app

WorkCadenceTransfer Android v1의 foreground 전송 앱입니다. UI는 Figma의
[앱 UI 설계 v1](https://www.figma.com/design/GeG2CW7KCN8W01SvqWkXbD)에서 확정한
`파란 신뢰 + 흰 카드` 스타일을 Compose로 옮깁니다.

## 현재 구현 범위

- 메모 작성, JPEG 선택/import, 사진 1~5장 preview
- Android Sharesheet `SEND`/`SEND_MULTIPLE` 수신
- 앱 전용 `noBackupFilesDir`의 AES-GCM encrypted pending 저장
- 제출 metadata와 사진 blob을 분리하고 `WCTENC1` AAD를 붙이는 Android Keystore AES-256-GCM 저장
- WCT1 canonical digest와 multipart JPEG 제출 client
- shared test vector를 확인하는 WCT1 digest 및 semantic validator
- QR JSON을 붙여넣어 PC enrollment를 완료하는 경로
- TLS SPKI pin과 RFC1918 endpoint 검사
- READY ACK 뒤 pending key를 먼저 폐기하고 encrypted blob을 삭제하는 경로

카메라 직접 촬영과 QR 카메라 스캔은 아직 권한·실기기 수용시험 경계에 있습니다. 현재는
사진 선택/공유 import와 QR JSON 붙여넣기로 실전 연결 전 자동검증을 진행합니다. 메모만
제출하는 경로도 contract v1의 `photos: []` 규칙으로 보존합니다.

## 빌드

Windows PowerShell에서 Android SDK와 Android Studio JBR를 먼저 지정합니다.

```powershell
$env:ANDROID_HOME = "C:\Users\sswce\AppData\Local\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-9.5.0-bin\bvnork1r7n8i6kp5cnkibsc9q\gradle-9.5.0\bin\gradle.bat" `
  -p apps\android :app:testDebugUnitTest :app:assembleDebug
```

실제 Galaxy·카카오톡·문자·Windows receiver 조합시험은 이 자동검증 뒤 별도 수용시험으로
진행합니다.
