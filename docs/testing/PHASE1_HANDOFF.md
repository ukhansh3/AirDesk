# AirDesk Phase 1 개발 및 사용자 검증 안내

## 구현 범위

실제 카메라 실행과 PLEOS 실행은 사용자가 수행한다. 개발 과정에서 컴퓨터 유즈와
에뮬레이터 실행을 중단한 이후에는 코드·자동 테스트만 실행했다.
Windows 입력, 화면 스트리밍, 차량 SDK, Pleos Stay+ 변경은 포함하지 않는다.

### 기존 코드와 계약의 차이 및 수정

- auth.accepted와 첫 제스처의 sequence가 모두 0이었다. 제스처는 1부터 시작하고 연결마다 새 sessionId를 사용한다.
- 카메라 read/model/open 실패와 종료 자원 해제를 보강했다. 손 소실 중 pinch/drag는 release 후 tracking.lost를 전송한다.
- pinch 진입에 60ms debounce, 해제에는 즉시 반응하는 hysteresis를 사용한다. EMA alpha=0.38, drag 거리=0.035는 실물 보정 전 기본값이다.
- Android는 공통 스키마를 assets로 읽고 현재 진단 스키마의 키워드만 평가한다. 미지원 키워드는 거부한다. 전체 JSON Schema 구현이 아닌 현재 스키마에 한정한 검증기다.
- 서버는 jsonschema 2020-12로 수신 hello와 모든 송신 메시지를 검증한다. 기존 jsonschema 의존성을 ws extra에도 포함했다.
- Android는 session, sequence, timestamp, 크기, 좌표를 검증한다. 인증 수신 시 두 단조 시계의 상대 offset을 잡고 750ms 이상 늦은 이벤트를 거른다. 최초 인증 지연을 포함한 절대 단방향 지연 측정은 아니다.
- 750ms 손 추적 watchdog, 5초 인증 timeout, 네트워크 재접속 1/2/4/8초 backoff를 사용한다. 인증 거부는 자동 재시도하지 않는다.
- pinch/drag/손 상태 표시와 safeDrawing insets, 커서 반지름을 반영한 실제 Canvas 좌표 변환을 추가했다.
- MediaPipe Tasks 결과에는 landmark 신뢰도가 별도로 없다. confidence=1.0은 detection/presence 임계값을 통과했다는 표식이며 보정된 확률이 아니다. handedness score를 추적 신뢰도로 오해하지 않는다.
- PROTOCOL_SPEC의 UNSUPPORTED 오류는 공통 error schema에 없다. 이번 구현에서는 사용하지 않고 공통 문서를 수정하지 않았다.

계약 파일과 기존 SDK/라이브러리 버전 및 Android 권한은 변경하지 않았다.
Android JVM 테스트에만 org.json:json:20240303을 추가했다.

## 자동 검증

- Python: python -m unittest discover -s tests -v — 30개 통과. 단위·전체 계약·loopback WebSocket 정상 인증/거부·비루프백 bind 거부 포함.
- 카메라 자원 해제/실패: fake OpenCV/MediaPipe 테스트. 실제 하드웨어 증거가 아니다.
- Android: :app:testDebugUnitTest — protocol 7개 + safety 4개, 총 11개 통과.
- Android: :app:assembleDebug 통과. 에뮬레이터 실행·설치는 하지 않았다.
- 실제 카메라 21관절, 실제 MediaPipe 추론, 실물 pinch/drag, PLEOS 접속 및 렌더링, 앱 콘텐츠 영역 실측, FPS/지연/오인식은 NOT_RUN이다.

빌드 환경: Windows, Python 3.13.9, Gradle 9.6.0, Android Studio 번들 JDK,
compileSdk 35. 설치된 SDK는 37뿐이어서 작업 폴더에 공식 SDK 35를 별도로 준비했다.
Gradle/Kotlin 캐시 권한 오류는 작업 폴더 캐시와 in-process 컴파일로 해결했다.

## 사용자 실행

1. 소스 폴더의 gesture-engine에서 Python 3.11 또는 MediaPipe 지원 Python으로 가상환경을 만든다.

```powershell
py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev,ws,camera]"
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

2. Google 공식 MediaPipe Hand Landmarker task 모델을 외부에서 받아 루트 models/hand_landmarker.task에 둔다. 모델은 커밋하지 않는다.
공식 안내: https://ai.google.dev/edge/mediapipe/solutions/vision/hand_landmarker/python

3. 먼저 로컬 프리뷰로 손을 확인한다. Q/ESC로 종료하며 영상은 저장하지 않는다.

```powershell
.\.venv\Scripts\python.exe -m airdesk_gesture.cli camera --model ..\models\hand_landmarker.task --device 0 --preview
```

4. 사용자 PowerShell에서 일회용 dev token을 생성한다. 아래 명령은 값을 출력하지 않는다.

```powershell
$env:AIRDESK_DEV_TOKEN = & .\.venv\Scripts\python.exe -c "import secrets; print(secrets.token_urlsafe(32))"
```

5. 같은 PowerShell 환경에서 Android debug 앱을 빌드한다. Android SDK 35와 기존 AGP가 요구하는 build-tools가 필요하다.
Gradle wrapper는 원래 저장소에 없으므로 설치된 Gradle 9.6.0 또는 Android Studio의 빌드를 사용한다.

```powershell
Push-Location ..\android-client
gradle '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest :app:assembleDebug
Pop-Location
```

Android Studio에서 직접 빌드한다면 같은 dev token을 ignored android-client/local.properties의
AIRDESK_DEV_TOKEN 값으로 직접 설정한다. token을 보고서·로그·Git에 넣지 않는다.
빈 token으로 빌드한 검증 APK는 연결이 차단된다. 사용자 실행에는 동일 token으로 다시 빌드해야 한다.

6. 같은 PowerShell에서 서버를 켠다. 프리뷰 명령과 서버를 동시에 켜면 카메라가 충돌할 수 있으므로 프리뷰부터 종료한다.

```powershell
.\.venv\Scripts\python.exe -m airdesk_gesture.ws_server --source camera --model ..\models\hand_landmarker.task --device 0
```

7. 사용자가 PLEOS 에뮬레이터를 켜고 APK를 설치·실행한 뒤 진단 연결을 누른다.
기본 주소는 ws://10.0.2.2:8765이며 Python listener는 127.0.0.1:8765만 사용한다.
표준 Android emulator의 10.0.2.2는 host loopback 별칭이다. 실제 PLEOS 동작은 직접 확인해야 한다.
접속이 안 되면 사용자가 adb reverse tcp:8765 tcp:8765를 설정하고
AIRDESK_WS_URL=ws://127.0.0.1:8765로 다시 debug 빌드하는 로컬 대안을 사용할 수 있다.
서버를 0.0.0.0으로 바꾸거나 방화벽을 외부에 개방하지 않는다.

## 수동 확인 순서

1. 검지 이동: Canvas 안에서 커서가 이동하고 손 인식 상태가 바뀌는지.
2. 엄지·검지를 60ms 이상 붙이기: PINCHED/노란 커서. 이동 유지: DRAGGING/붉은 커서. 손가락 벌리기: TRACKING.
3. 드래그 중 손을 프레임 밖으로 빼기: 커서·pinch·drag 초기화. 다시 등장하면 새 tracking.
4. 카메라 분리/서버 종료: 커서 비활성화. 서버 재시작 후 재인증하고 held 상태가 남지 않는지.
5. 잘못된 dev token 앱: 인증 거부, 커서 없음. 올바른 token으로 다시 빌드하면 연결.
6. 앱 background/foreground: 자동 control 재개 없이 연결 해제. 사용자가 다시 진단 연결.
7. 창 크기·비율·플랫폼 패널: 커서가 실제 앱 Canvas 안에 남고 시스템 영역을 덮지 않는지.
8. 프레임 속도/체감 지연/오인식, 해상도, 카메라 장치 번호, PLEOS 버전과 결과를 기록한다. 원본 영상은 보관하지 않는다.

## 저장소 전달 상태

로컬 Git 인증 실패로 정상 clone은 불가능했다. GitHub 연결 API로 dev의 92파일 스냅샷을 읽어
별도 작업 폴더에 복원했고 로컬 dev 브랜치를 초기화해 변경분을 관리했다.
이 로컬 Git 이력은 원격 dev와 공통 이력이 없으므로 그대로 push하지 않는다.
사용자의 dev 푸시 요청에 따라 GitHub Git Data API로 기존 원격 dev 커밋을 부모로 지정하고
변경된 18개 파일만 새 커밋에 반영한다. 원격 head를 다시 확인하고 fast-forward로만 갱신한다.
main은 변경하지 않는다. 이후 작업은 원격 dev를 정상 clone/pull한 체크아웃을 사용한다.

다음 Phase 전에 위 실물 테스트를 완료하고 임계값과 watchdog을 실측 보정한다.
실제 Windows 입력·차량 안전·production 인증은 별도 승인과 검증이 필요하다.
