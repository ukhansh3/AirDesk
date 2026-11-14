# 담당자 검토·실행 가이드

## 1. 무엇을 검토하는가

현재 앱은 로컬 desktop DEMO와 카메라 기반 입력 실험입니다. **Windows나 다른 Android 앱을 조작하지 않습니다.** 실제 운영 Work 입력은 공식 차량 안전 상태가 UNKNOWN이면 차단합니다.

`main`은 최신 `dev`의 기능을 보존해 정리한 검토 기준입니다. 이번 정리는 README·문서 탐색과 빌드 재현성을 위한 것이며 입력 모델·와이어 스키마·안전 정책을 변경하지 않습니다.

## 2. Android 빌드

- JDK 17 이상
- Android API 35 플랫폼 및 프로젝트 지정 Build Tools
- Gradle Wrapper 9.6.0 — 기존 CI와 같은 버전

```powershell
cd android-client
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --max-workers=1
```

첫 빌드에는 Gradle/Maven 다운로드 연결이 필요합니다. APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다. 이 명령은 에뮬레이터를 실행하지 않습니다.

## 3. 로컬 시연 설정

카메라 입력을 쓰려면 PC와 APK에 같은 개발 토큰이 필요합니다. `android-client/local.properties`는 Git에서 제외됩니다.

```properties
sdk.dir=C:/Users/your-user/AppData/Local/Android/Sdk
AIRDESK_DEV_TOKEN=replace_with_a_fresh_local_random_token
AIRDESK_WS_URL=ws://10.0.2.2:8765
```

위 토큰은 예시입니다. 실제 값은 암호학적 난수로 따로 만들고 로그·스크린샷·Git에 넣지 않습니다. APK는 빌드 시 이 값을 사용하므로 토큰을 바꾸면 다시 빌드해야 합니다. Debug 개발 토큰은 운영 인증 수단이 아닙니다.

이번 로컬 시연 APK에는 사용자가 승인한 **별도 새 시연 토큰**을 사용합니다. 기존 PC 개발 토큰과 다르므로 APK와 함께 준비한 비공개 PC 시연 설정을 짝으로 사용합니다. GitHub에는 실제 값이 없습니다.

PC 진단 연결은 `127.0.0.1:8765`만 허용하며, 에뮬레이터의 `10.0.2.2`는 호스트 PC를 뜻합니다. 실차·외부 네트워크·공개 터널 연결은 이 시연 구성에 포함하지 않습니다.

## 4. PC 카메라 준비와 실행

[gesture-engine README](../gesture-engine/README.md)의 설치·모델 취득·사용 조건을 먼저 확인합니다. Python 가상환경, OpenCV/MediaPipe/Pillow/WebSocket 의존성, 공식 Hand Landmarker 모델이 필요합니다.

기존 환경이 준비됐다면 저장소 루트의 `Run-AirDesk.cmd`를 실행합니다. 카메라를 선택하고 필요한 경우 모델 파일을 지정합니다. 카메라 영상은 로컬 미리보기에만 사용합니다.

시연 순서:

1. Android에서 명시적으로 표시된 desktop DEMO를 엽니다.
2. PC 카메라 패널에서 연결합니다.
3. 큰 키보드 창에서 보정을 시작하고 안내에 따라 중립·손가락 자세를 유지합니다.
4. 타건을 직접 켜고, 중립 자세로 입력을 재활성화합니다.
5. 연결이 끊어지면 재연결·재보정·입력 켜기가 필요합니다.

기본 87키 TKL은 카메라 참조 표시입니다. 기존 Android AIR 입력 좌표를 시험하려면 **기존 AIR 입력 정렬**을 선택합니다. 타건은 손 움직임 추론이며 실제 키 접촉 감지가 아닙니다. 정확도·지연·한글 IME·카메라 배치는 [수동 체크리스트](testing/AIR_TYPING_HANDOFF.md)로 별도 확인합니다.

## 5. 코드 탐색

| 관심 영역 | 시작 파일 |
|---|---|
| 앱 진입 | [MainActivity.kt](../android-client/app/src/main/java/com/pleos/airdesk/MainActivity.kt) |
| 앱 내부 대상·레이아웃 | [DesktopWorkspace.kt](../android-client/app/src/main/java/com/pleos/airdesk/DesktopWorkspace.kt) · [DesktopWorkspaceLayout.kt](../android-client/app/src/main/java/com/pleos/airdesk/DesktopWorkspaceLayout.kt) |
| 커서·클릭 전달 | [GestureUiRouter.kt](../android-client/app/src/main/java/com/pleos/airdesk/GestureUiRouter.kt) |
| 키보드·한글 편집 상태 | [DesktopKeyboardController.kt](../android-client/app/src/main/java/com/pleos/airdesk/DesktopKeyboardController.kt) · [DesktopHangulComposer.kt](../android-client/app/src/main/java/com/pleos/airdesk/DesktopHangulComposer.kt) |
| AIR 보정·입력 | [AirTypingController.kt](../android-client/app/src/main/java/com/pleos/airdesk/AirTypingController.kt) |
| 안전·신선도 | [SafetyPolicy.kt](../android-client/app/src/main/java/com/pleos/airdesk/SafetyPolicy.kt) |
| PC 카메라·연결 수명 | [desktop_runtime.py](../gesture-engine/src/airdesk_gesture/desktop_runtime.py) · [ws_server.py](../gesture-engine/src/airdesk_gesture/ws_server.py) |
| 메시지 계약 | [contracts](../contracts/) · [integration-tests](../integration-tests/) |

## 6. 카메라 없이 자동 검증

기존 Python 가상환경에서:

```powershell
# 저장소 루트
$env:PYTHONPATH = "$PWD\gesture-engine\src"
python -m unittest discover -s gesture-engine/tests
python -m unittest discover -s integration-tests -p test_phase2_contract_harness.py
python -m unittest discover -s integration-tests -p test_air_typing_contracts.py

cd android-client
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --max-workers=1
```

[verify-input-first.ps1](../scripts/verify-input-first.ps1)은 Python·계약·JVM·빌드의 순차 검증 도구입니다. 실행 정책을 임의로 변경하지 않고 사용 환경의 허용 범위에서 실행합니다.

## 7. 과거 Windows Host와 분리

`Run-AirDeskHost.cmd`, `windows-host/`, mTLS·페어링은 이전 원격 입력 연구 경로입니다. 현재 앱 내부 DEMO에는 필요하지 않습니다. 기존 TLS 실패를 빌드 성공으로 덮어쓰거나 네이티브 입력 권한을 해제하지 않습니다.

현재 검증 결과는 [전달 기록](testing/2026-11-14-review-handoff.md)에 있습니다. 실제 카메라·PLEOS 에뮬레이터·운영 차량 안전·전역 입력은 자동 테스트 결과와 구분합니다.
