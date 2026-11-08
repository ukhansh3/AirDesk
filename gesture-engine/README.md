# AirDesk Gesture Engine

## PC 실행 화면 (터미널 명령 없이 사용)

### 녹색 키보드를 캠 영상에 겹쳐 보기

기존 PC 패널이 열려 있으면 닫은 뒤 **Run-AirDesk.cmd**를 다시 실행한다.
PC 패널의 **카메라 키보드 표시**는 기본 켜짐이며, **선 농도**는 기본 **40%**,
조절 범위는 **15~80%**다. 카메라를 시작하면 초록 테두리와 영문·한글 글자가 영상 위에 표시된다.
키 배경은 채우지 않아 손과 배경 영상이 그대로 보인다. 체크를 끄면 이 표시만 사라진다.
추적·보정·타건의 시작/종료와는 별개의 PC 표시 설정이다.
영상은 미리보기 공간에 맞춰 최대 860×480 안에서 잘리지 않게 조절되며,
손 감지·관절 표시·Guard 진단은 영상 바깥에 표시해 기능키를 가리지 않는다.
이 녹색 합성은 Run-AirDesk 패널에 적용된다. 별도의 OpenCV `--preview` 화면은 기존 표시를 유지한다.

전체 81개 키와 마우스 영역은 기존 v0.2의 정규화된 영상 좌표를 따른다. 이 단계는
카메라 화면에 합성한 안내선이며, 물리적 홀로그램이나 고정된 3D 공간의 접촉 판정은 구현하지 않는다.
사용자는 **웹캠 한 대로 추정하는 실험을 먼저 진행**하기로 승인했다. 깊이 센서는 측정 결과가
부족할 때 별도 검토할 후속 대안이다. [선택 근거](../docs/decisions/ADR-007-SINGLE-WEBCAM-KEYBOARD-OVERLAY.md)와
[공간 키보드 설계 경계](../docs/features/SPATIAL_AIR_KEYBOARD_DESIGN.md)를 참고한다.

한글 글자와 실제 RGBA 합성을 위해 `camera`/`all` 선택 의존성에 `Pillow>=10,<14`를 추가한다.
Windows에 설치된 맑은 고딕을 읽으며 폰트 파일은 저장소에 배포하지 않는다. Pillow나 한글 폰트가
없으면 이유를 표시하고 이 겹쳐보기만 비활성화하며, 기존 카메라 인식은 계속할 수 있다.
문제를 해결한 뒤 **카메라 키보드 표시**를 껐다 켜면 재시도한다. 선 농도만 움직이면 재시도하지 않는다.
초기 설치 또는 의존성 갱신은 `gesture-engine` 폴더에서 `pip install -e ".[camera]"`로 한다.

합성은 MediaPipe가 영상을 처리한 **뒤**, PC 미리보기 복사본에만 적용된다. 원본 관절 데이터와
기존 입력 판정·Android 키보드·통신 계약은 유지하며, 영상 저장이나 WebSocket 영상 전송은 없다.
실제 웹캠의 한글 가독성·투명도·지연·타건 정확도는 직접 확인해야 한다.

### 카메라 연결과 기존 AIR 실험

AirDesk 루트의 **Run-AirDesk.cmd**를 더블클릭한다.
카메라 번호를 선택하고 **시작 · 카메라 + 앱 연결**을 누른 뒤,
큰 화면이 필요하면 PC 패널의 **큰 키보드 창**을 먼저 열고 크기를 조절한다.
Android AirDesk 앱의 **양손 열손가락 → 연결 → 자동 보정 시작**을 한 번 누른다.
5초 준비 후 화면 안내대로 중립 자세와 열 손가락을 차례로 움직이면 자동으로 보정된다.
단계마다 클릭할 필요가 없으며, 완료 후 **타건 켜기**로 실험한다.
PC 키보드 창은 손끝과 누름 추정만 표시한다. 실제 입력 결과는 Android 앱에서 확인한다.
기존 한손 입력은 **검지·Pinch**, 읽기 전용 화면은 **Phase 1 진단 → 진단 연결**에 남아 있다.
전체 절차와 미검증 항목은 [AIR 인계 문서](../docs/testing/AIR_TYPING_HANDOFF.md)를 참고한다.

- PC 실행 화면 안에서 실시간 영상과 손 관절을 확인한다. Android 연결 전에도 PC 미리보기가 동작한다.
- 영상 관절 점과 AIR 손끝 이동에는 같은 좌표 안정화 기준을 적용한다. 순간적인 튐과 작은 흔들림을 줄이며, 빠른 이동에는 지연이 추가될 수 있다. 원본 관절 데이터와 보정·타건 판정 조건은 유지한다.
- 다른 캠이 나오면 **중지 → 카메라 번호 변경 → 시작**. 번호와 모델 선택은 ignored .venv/airdesk-panel.json에 기억된다.
- 기존 android-client/local.properties의 개발 토큰을 자동으로 읽으며 출력하거나 새로 만들지 않는다. Android 소스가 변경된 업데이트만 앱을 다시 빌드한다. 이번 녹색 겹쳐보기는 PC 표시 변경이다.
- 양손 AIR는 MediaPipe Tasks 0.10.5 이상을 확인한 경우 사용한다. 오래되거나 버전을 확인할 수 없으면 PC 패널은 Phase 1 한손 추적만 유지하며 AIR 보정은 진행할 수 없다.
- 모델 경로도 자동 탐색하며 **모델 파일 선택**으로 직접 바꿀 수 있다.
- 서버 중지 및 창 종료 시 카메라와 연결을 해제한다. 기존 터미널 서버가 켜져 있다면 처음 한 번 Ctrl+C로 종료한다.
- 영상은 메모리의 최신 프레임 하나만 표시한다. 이미지/영상 저장이나 WebSocket 영상 전송은 없다.
- PC 패널 UI는 기존 Tkinter 표준 라이브러리를 유지한다. 한글/RGBA 카메라 표시에는 검토된 Pillow 선택 의존성을 사용하며 새 Android 권한·SDK는 추가하지 않는다.

CLI 서버도 환경변수에 토큰이 없으면 같은 local.properties를 읽는다.
토큰 자체가 없으면 인증을 생략하지 않고 실행을 차단한다.
실제 카메라/GUI 하드웨어 동작은 사용자가 직접 검증해야 한다.

Python 3.11+ suggested. MediaPipe Tasks Hand Landmarker and OpenCV are **optional webcam dependencies**; the synthetic gesture processor/tests use the standard library.

~~~powershell
cd gesture-engine
py -3.11 -m venv .venv
.venv\Scripts\Activate.ps1
pip install -e ".[dev]"
python -m unittest discover -s tests -v
python -m airdesk_gesture.cli synthetic --count 20
~~~

For real webcam:
~~~powershell
pip install -e ".[camera]"
python -m airdesk_gesture.cli camera --model ..\models\hand_landmarker.task --device 0 --preview
~~~

Get an official compatible Hand Landmarker .task model from Google's MediaPipe page and save it locally under ignored models/ (never commit it). Camera permission, webcam selection and model compatibility must be checked on your Windows PC.
The preview is local-only and is never recorded or transmitted; press Q or ESC to stop. Use
`--no-mirror` only when the target display needs camera-native orientation.

Debug-only local WebSocket:
~~~powershell
$env:AIRDESK_DEV_TOKEN = "a-long-random-local-development-token"
python -m airdesk_gesture.ws_server --source synthetic
python -m airdesk_gesture.ws_server --source camera --model ..\models\hand_landmarker.task --device 0
~~~

The standard Android emulator normally connects to host loopback using ws://10.0.2.2:8765. Pleos runtime needs actual test. Client must first send auth.hello with the dev token. This is **synthetic cursor diagnostic only**, NOT secure remote control. Start with synthetic before trying --source camera.

The camera mode uses MediaPipe VIDEO mode with monotonic frame timestamps so a local Windows webcam can be checked without an Android camera. It does NOT implement streaming Windows display pixels.
The server intentionally refuses non-loopback bind addresses. It emits contract-defined
camera/model loss reasons and keeps authentication plus gesture sequence numbers strictly increasing.

## Choose a webcam and watch it while using the Android cursor

The camera extra uses opencv-contrib-python 4.x, which provides cv2 and satisfies
MediaPipe's OpenCV dependency. Do not install opencv-python alongside it: both
distributions own the same cv2 package. See https://github.com/opencv/opencv-python.

From the AirDesk root in the same PowerShell session as the existing dev token:

~~~powershell
# Stop the old synthetic server with Ctrl+C first. Keep its token environment.
& .\gesture-engine\.venv\Scripts\python.exe -m airdesk_gesture.ws_server --source camera --model .\gesture-engine\.venv\models\hand_landmarker.task --select-camera --preview
~~~

The local camera picker opens first. Press N for the next readable camera, P for the
previous camera, Enter to select, or Q/ESC to cancel. It checks indices 0 through 5;
use --max-devices 10 if your camera has a higher index. Indices are OpenCV indices,
not guaranteed to match Windows device names or remain stable after reconnecting USB devices.

Then tap Diagnostic Connect in Android. The selected webcam opens with live video,
21 landmark dots and highlighted thumb/index tips on the PC. Only gesture events
are sent to Android. No video is saved or sent over the WebSocket.
Q/ESC or closing the preview releases tracking and closes the diagnostic session;
the client does not automatically reopen the camera after this user stop.
Without --preview no video window is shown. Without --select-camera use --device N
to choose a previously checked camera directly.

To identify a camera without a model or WebSocket client:

~~~powershell
& .\gesture-engine\.venv\Scripts\python.exe -m airdesk_gesture.cli select-camera
~~~

Camera picking opens devices temporarily and releases each before switching. Close other
apps that occupy the webcam. The model must exist before running the tracking server.
The local setup model under .venv/models is ignored by Git; another external model
path can also be passed with --model. Camera GUI behavior still needs user hardware testing.

