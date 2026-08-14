# Windows receiver

현재 구현은 Windows foreground에서만 실행하는 .NET 8 최소 수신기입니다. 서비스 등록,
자동 시작, UI와 외부 서버 연결은 포함하지 않습니다.

## 빌드

```powershell
dotnet build apps\windows-receiver\WorkCadenceTransfer.Receiver.csproj --configuration Release
```

## 등록과 실행

먼저 사내 RFC1918 IPv4 주소와 포트를 지정해 1회성 등록 QR JSON을 콘솔에 출력합니다.

```powershell
dotnet run --project apps\windows-receiver\WorkCadenceTransfer.Receiver.csproj --no-launch-profile -- `
  --listen 192.168.0.10:8443 --root C:\WorkCadenceTransferData --create-enrollment
```

출력된 JSON은 모바일 등록 화면에 전달할 페이로드입니다. 실제 등록이 끝난 뒤 같은 명령에서
`--create-enrollment`만 빼고 foreground 수신기를 실행합니다.

```powershell
dotnet run --project apps\windows-receiver\WorkCadenceTransfer.Receiver.csproj --no-launch-profile -- `
  --listen 192.168.0.10:8443 --root C:\WorkCadenceTransferData
```

수신기는 현재 메모 전용 제출을 대상으로 합니다. TLS 인증서와 등록·단말 검증 정보는 현재
Windows 사용자 DPAPI로 보호하고, 제출 정본은 평문 임시파일 없이 AES-GCM으로 암호화한 뒤
`.staging\<uploadId>`에서 `ready\<recordId>`로 원자 이동합니다. 같은 단말의 같은 제출을
재시도하면 같은 READY 응답을 반환하며, 다른 digest는 충돌로 거부합니다.

사진 제출은 계약과 메타데이터 검증을 먼저 수행하지만, JPEG 정규화·픽셀/EXIF 검증 구현 전까지
`INVALID_MEDIA`로 거부합니다. 사진 수신을 열 때는 해당 구현과 실제 기기 수용시험을 별도로
완료해야 합니다.
