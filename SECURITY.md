# Security policy

이 저장소에는 실제 사진, 메모, 단말 등록키, TLS private key, signing key, 인증서 bundle,
Windows 암호화 정본과 모바일 pending queue를 커밋하지 않습니다.

보안 결함은 재현용 실제 개인정보를 첨부하지 말고 아래 정보만으로 보고합니다.

- 영향을 받는 앱과 버전
- 재현 단계와 기대 결과
- 내용 없는 submission/device test ID
- 오류코드와 발생시각

외부 업무데이터 서버, cloud relay, 분석·광고·원격 crash 수집을 도입하는 변경은 현재 보안
경계를 바꾸므로 별도 승인 없이는 구현하지 않습니다. 자세한 경계는
[docs/01_SECURITY_BOUNDARY.md](docs/01_SECURITY_BOUNDARY.md)를 따릅니다.
