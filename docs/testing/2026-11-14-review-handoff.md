# 담당자 검토 전달 검증 — 2026-11-14

## 기준과 변경 범위

- 시작 dev: `ab4d182c2fa93efba0a252a57ddcc4ef7189382c`.
- 확인한 원격 main: `451f0f996ec09458cd420dc48dd884fd82c7c023`; main은 dev의 조상이다.
- 사용자 승인: 최신 dev 기능·규칙으로 main 정리, 담당자용 Debug APK, **새 로컬 시연 전용 토큰**.
- PC 원본 `C:\Users\ukhan\AirDesk`에는 Git 메타데이터가 없었다. 기존 원격 저장소를 별도 체크아웃해 비교했다. 원격 추적 파일 198개의 PC 내용은 줄바꿈·파일 끝 공백을 정규화하면 모두 동일했다. 원본 PC 환경·모델·기존 토큰을 덮어쓰지 않았다.
- 변경: root README, 문서 목차, 검토/실행 안내, 본 기록, Gradle Wrapper 4파일. 기능 코드·계약·차량 안전 정책·과거 실패 기록은 보존했다.
- 누락된 Wrapper는 기존 로컬 Gradle 9.6.0의 wrapper 태스크로 생성했다. 기존 CI와 같은 버전이며 앱 의존성을 추가/업데이트하지 않았다.
- wrapper JAR SHA-256: `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`.
- 최종 전체 Git SHA·APK SHA-256은 로컬 전달 폴더 manifest에 기록한다.

## 이번 실행 결과

| 항목 | 결과 | 근거 |
|---|---|---|
| Android JVM | PASS | 182/182, failures/errors/skipped 0 |
| Android Debug APK | PASS | 45개 태스크 실행, 새 로컬 시연 설정으로 패키징 |
| Python 회귀 | PASS | 180/180 |
| 기존 제어·제스처 계약 fixture | PASS | 5/5 |
| 실험적 AIR 계약 fixture | PASS | 7/7 |
| APK ZIP·서명 | PASS | ZIP 검사·apksigner v2 검증 |
| 토큰 격리 | PASS | 새 시연 토큰은 해당 Debug APK·비공개 로컬 PC 시연 설정에만 사용. Git 미포함, 기존 PC 토큰 변경 없음 |
| 서버 비밀키 제외 | PASS | 로컬 Stay+ 서버 secret/API 키가 tracked 파일·APK에 없음. 실제 값은 출력하지 않음 |
| 기능 소스 보호 | PASS | 시작 dev 대비 Android 앱·Python 엔진·contracts·Windows Host 변경 없음 |
| 문서 링크 / diff | PASS | 새 안내 로컬 링크 및 diff 공백 검사 |

기존 컴파일 경고(Gradle sourceSets API 사용, WebSocketListener 인자 이름, 네이티브 심볼 strip)는 남아 있으나 이번 빌드 오류는 없다.

## 미실행과 제약

- NOT RUN: PLEOS 에뮬레이터·실제 Compose 조작·카메라·보정·타건 정확도·지연·한글 IME. 이번에 카메라와 에뮬레이터를 시작하지 않았다.
- BLOCKED/미검증: 공식 차량 카메라·운영 Work 안전 신호. UNKNOWN 차단은 그대로 유지한다.
- 과거 Windows mTLS 실제 연동 실패(`SEC_E_NO_CREDENTIALS`)는 [기존 기록](PHASE2_HANDOFF.md)에 그대로 남아 있다. 이번에 재검증하거나 성공으로 바꾸지 않았다.
- 현재 기능은 앱 내부 desktop DEMO/실험적 AIR 입력이다. Windows 전역 입력·스트리밍·Stay+ 통합으로 표현하지 않는다.
- 정식 Release·배포·외부 서버·새 SDK·라이선스 도입은 수행하지 않았다.

## 로컬 APK와 PC 설정

- applicationId `com.pleos.airdesk`; versionName `0.1.0`, versionCode `1`; minSdk28, targetSdk34.
- 새 시연 토큰은 기존 PC 개발 토큰과 다르다. **같이 준비한 비공개 PC 시연 설정**을 사용해야 카메라 진단 연결이 맞는다.
- 토큰을 포함한 Debug APK·PC 설정은 로컬 검토용이다. GitHub 소스나 공개 다운로드로 게시하지 않는다.
- PC 서버는 loopback8765, emulator는 `ws://10.0.2.2:8765`를 사용한다.
- 설치·코드 탐색·추가 수동 검증은 [담당자 가이드](../REVIEW_GUIDE.md)를 따른다.
