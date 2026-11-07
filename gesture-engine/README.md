# AirDesk Gesture Engine

## PC 실행 화면 (터미널 명령 없이 사용)

AirDesk 루트의 **Run-AirDesk.cmd**를 더블클릭한다.
카메라 번호를 선택하고 **시작 · 카메라 + 앱 연결**을 누른 뒤,
Android AirDesk 앱의 **진단 연결**을 누른다.

- PC 실행 화면 안에서 실시간 영상과 손 관절을 확인한다. Android 연결 전에도 PC 미리보기가 동작한다.
- 다른 캠이 나오면 **중지 → 카메라 번호 변경 → 시작**. 번호와 모델 선택은 ignored .venv/airdesk-panel.json에 기억된다.
- 기존 android-client/local.properties의 개발 토큰을 자동으로 읽는다. 토큰을 출력하거나 새로 만들지 않으므로 앱을 다시 빌드할 필요가 없다.
- 모델 경로도 자동 탐색하며 **모델 파일 선택**으로 직접 바꿀 수 있다.
- 서버 중지 및 창 종료 시 카메라와 연결을 해제한다. 기존 터미널 서버가 켜져 있다면 처음 한 번 Ctrl+C로 종료한다.
- 영상은 메모리의 최신 프레임 하나만 표시한다. 이미지/영상 저장이나 WebSocket 영상 전송은 없다.
- Tkinter 표준 라이브러리를 사용하므로 GUI용 새 SDK/라이브러리는 추가하지 않는다.

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

