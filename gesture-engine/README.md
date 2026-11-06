# AirDesk Gesture Engine

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

