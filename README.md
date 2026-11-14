# AirDesk

**PLEOS 화면 안에서 마우스와 PC형 키보드처럼 조작하는 입력 실험 앱입니다.**

PC 웹캠에서 얻은 손 움직임을 로컬 WebSocket으로 전달하고, AirDesk가 소유한 Compose 화면의 커서·클릭·드래그·스크롤·문자 입력에 연결합니다. 현재 검토 대상은 **앱 내부 desktop DEMO와 실험적 AIR 입력**입니다.

> `main`은 담당자 검토 기준입니다. [검토·실행 가이드](docs/REVIEW_GUIDE.md) → [검증 결과](docs/testing/2026-11-14-review-handoff.md) → [현재 제품 결정 ADR-004](docs/decisions/ADR-004-INPUT-FIRST.md) 순서로 읽어 주세요.
> **Windows 원격 데스크톱이나 시스템 전체 입력 제어가 아닙니다.** Pleos Stay+ Work와도 아직 통합하지 않았습니다.

## 현재 기능과 경계

| 영역 | 구현 범위 | 아직 검증하지 않은 부분 |
|---|---|---|
| 앱 내부 desktop DEMO | 등록된 Compose 대상 클릭·컨텍스트 메뉴·드래그·스크롤 | 실제 PLEOS에서의 전체 터치·제스처 인수 테스트 |
| PC형 키보드 | QWERTY, 수정·이동 키, 지원되는 로컬 단축키, 한/영 편집 | 실제 기기의 한글 IME·가독성·사용성 |
| PC 카메라 입력 | MediaPipe 손 랜드마크, 검지·Pinch 프로필, 로컬 진단 연결 | 차량 카메라 접근 및 실제 차량 입력 권한 |
| 실험적 AIR 타건 | 양손 보정, 열손가락 움직임 추론, 중립 복귀·재활성화 | 일반적인 카메라 환경의 정확도·지연·실사용 타건 |
| 카메라 키보드 표시 | 기본 87키 TKL 참조, 기존 AIR 입력 정렬 선택 | 화면 참조를 물리 접촉 감지로 해석하면 안 됩니다. |
| 안전 게이트 | UNKNOWN/주행/제한/오래된 신호에서 운영 입력 차단 | 공식 PLEOS 차량 상태·UX 제한 통합 |

실제 지원되지 않는 F키 동작은 비활성화합니다. DEMO는 운영 Work 권한을 열지 않으며, 입력은 앱 소유 편집기·대상 안에 머뭅니다.

## 실행 요약

1. Android의 담당자 검토용 **Debug APK**를 설치합니다. 에뮬레이터 자동 실행은 하지 않습니다.
2. PC 카메라 시연에는 Python 환경·공식 Hand Landmarker 모델·카메라 권한이 필요합니다.
3. PC와 APK가 같은 **로컬 개발 토큰**을 사용해야 합니다. 토큰은 Git에서 제외한 `android-client/local.properties`에 설정합니다.
4. 준비된 PC에서 `Run-AirDesk.cmd`를 실행하고 모델·카메라를 선택합니다.
5. 카메라의 큰 키보드 창에서 연결·보정을 진행한 후 명시적으로 입력을 켭니다.

PC 진단 서버는 loopback `127.0.0.1:8765`, Android 에뮬레이터는 `ws://10.0.2.2:8765`를 사용합니다. 공개 서버·터널·Windows Host는 이 DEMO의 필수 요소가 아닙니다. 카메라 영상과 입력 문장은 외부 서버로 보내지 않습니다.

[자세한 설치·실행·토큰 설정](docs/REVIEW_GUIDE.md) · [카메라 모듈 안내](gesture-engine/README.md) · [AIR 수동 검증](docs/testing/AIR_TYPING_HANDOFF.md)

## 저장소 구조

```text
android-client/     Kotlin / Jetpack Compose 앱·DEMO 입력·안전 게이트
gesture-engine/     PC 웹캠·MediaPipe·진단 WebSocket·카메라 UI
contracts/         제어·제스처·AIR 입력의 JSON 스키마
integration-tests/ 계약·통합 시나리오와 비기밀 테스트 fixture
scripts/           자동 검증 도구
docs/              현재 명세·결정·검증과 과거 연구 이력
windows-host/      별도 보존 중인 Windows Host 연구 코드
streaming/         원격 영상 기술검토 — 현재 DEMO 실행 경로 아님
```

## 코드 읽는 순서

1. [MainActivity](android-client/app/src/main/java/com/pleos/airdesk/MainActivity.kt): 앱 진입과 화면 연결
2. [DesktopWorkspace](android-client/app/src/main/java/com/pleos/airdesk/DesktopWorkspace.kt) · [GestureUiRouter](android-client/app/src/main/java/com/pleos/airdesk/GestureUiRouter.kt): 앱 소유 대상에만 입력 전달
3. [DesktopKeyboardController](android-client/app/src/main/java/com/pleos/airdesk/DesktopKeyboardController.kt) · [AirTypingController](android-client/app/src/main/java/com/pleos/airdesk/AirTypingController.kt): 편집·보정·입력 상태
4. [desktop_runtime.py](gesture-engine/src/airdesk_gesture/desktop_runtime.py) · [ws_server.py](gesture-engine/src/airdesk_gesture/ws_server.py): PC 카메라와 로컬 연결
5. [SafetyPolicy](android-client/app/src/main/java/com/pleos/airdesk/SafetyPolicy.kt) · [contracts](contracts/): 권한·상태·스키마 경계
6. [문서 목차](docs/README.md): 기능 명세와 검증 기록

## 빌드·테스트

JDK 17 이상과 Android API 35 SDK가 필요합니다. 기존 버전 고정 Gradle Wrapper를 사용합니다.

```powershell
cd android-client
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --max-workers=1
```

APK: `android-client/app/build/outputs/apk/debug/app-debug.apk`

Python 및 계약 테스트 명령은 [검토 가이드](docs/REVIEW_GUIDE.md)에 있습니다. [이번 결과](docs/testing/2026-11-14-review-handoff.md)는 자동 검증과 실제 카메라·PLEOS 미실행 항목을 분리합니다.

## 과거 연구와 제한

Windows Host/mTLS·페어링·원격 입력 연구는 이력을 보존합니다. 과거 실제 TLS 검증은 `SEC_E_NO_CREDENTIALS`로 실패했으며, Windows 네이티브 입력은 잠겨 있습니다. [당시 기록](docs/testing/PHASE2_HANDOFF.md)을 현재 DEMO 성공 근거로 바꾸지 않습니다.

차량 카메라·운영 차량 안전 신호·Windows 전역 입력·화면 스트리밍·Stay+ 통합·정식 배포는 현재 완료 범위가 아닙니다. [AGENTS.md](AGENTS.md)와 [DEVELOPMENT_GUIDE.md](DEVELOPMENT_GUIDE.md)의 안전·승인 규칙을 따릅니다.

개발은 `dev`/기능 브랜치에서 진행하며, 검증된 이력을 `main`에 통합합니다. 키·토큰·영상·개인 문서는 커밋하지 않습니다.
