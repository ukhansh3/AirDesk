> **2026-11-07 scope update:** AirDesk's active target is a **PC-style desktop DEMO inside AirDesk-owned Compose UI**. Gesture target routing and a full docked keyboard are implemented; actual PLEOS/hardware validation remains separate. See [ADR-004](docs/decisions/ADR-004-INPUT-FIRST.md), [ADR-005](docs/decisions/ADR-005-DESKTOP-DEMO-INPUT.md) and [revised handoff](docs/testing/PHASE2_REVISED_HANDOFF.md). Windows Host instructions below are historical/optional and are not required for local controls.

## Active revised Phase 2

The approved next experiment uses **one existing PC RGB webcam**. The camera panel shows a green
outline keyboard with English/Korean labels over the live preview, with transparent key interiors.
Use **카메라 키보드 표시** and **선 농도** to control it. This is a local image-space reference;
fixed spatial plane/contact sensing is not implemented by drawing the overlay. See
[ADR-007](docs/decisions/ADR-007-SINGLE-WEBCAM-KEYBOARD-OVERLAY.md) and the
[spatial keyboard design](docs/features/SPATIAL_AIR_KEYBOARD_DESIGN.md).
The camera extras now explicitly include Pillow for Unicode/alpha composition. Existing Windows
Malgun Gothic is read locally; no font is bundled. Install the documented camera extra if Pillow
is absent. A missing font/dependency disables only the keyboard overlay and leaves camera capture
available. Neither the preview nor the automated verification starts a new emulator or OS input.

Reuse the Phase 1 `Run-AirDesk.cmd` camera panel and existing debug token in ignored local.properties.
Run the debug Android app, open **데스크톱 DEMO**, connect the camera and explicitly enable gestures.
Use the app's PC keyboard and registered work targets; all actions remain local to this DEMO.
After reconnect/background, enable gestures again. Operational Work has no verified safety authority.

The ordered headless harness is `scripts/verify-input-first.ps1`; it runs Python regression, shared
contract fixtures, Android JVM tests and assembleDebug. It does not launch GUI/camera/emulator or
run the historical Windows TLS/native path. Commands, actual counts and user checks are in the
revised handoff. Keep the old optional host failures truthful.

# AirDesk Development Guide v0.1

## Scope
Initial proof: PC webcam -> MediaPipe hand landmarks -> gesture engine -> local diagnostic WebSocket -> PLEOS emulator cursor.
Later milestones: explicit Windows host authorization, remote input, streaming (Sunshine/Moonlight vs WebRTC), PLEOS Work integration. Do not misrepresent these as already implemented.

## Technology choices
- PC webcam: Python 3.11+, OpenCV, Google MediaPipe Tasks Vision.
- Gesture state: typed Python module and deterministic unit tests.
- Control diagnostics: localhost-only WebSocket with a fresh dev token, no OS input.
- Windows: C# .NET 10 WPF plus the ASP.NET Core shared framework for local mutual TLS; separate policy core, host consent, session controller and Mock input backend.
- Android: Kotlin, Jetpack Compose, fail-closed safety interface, separate diagnostic and secure-session paths.
- Contracts: JSON Schema 2020-12; no mandatory Fastify server or cloud DB.
- Video protocol: undecided until tested on actual PLEOS Emulator 2.1.0.

## Windows setup
1. Clone this PRIVATE repository.
2. cd gesture-engine; py -3.11 -m venv .venv; .venv\Scripts\Activate.ps1.
3. pip install -e ".[dev,ws]"; python -m unittest discover -s tests -v (including loopback WS tests).
4. pip install -e ".[camera]" only for a real webcam; obtain the approved MediaPipe hand_landmarker.task externally and store under ignored models/.
5. Run python -m airdesk_gesture.cli synthetic --count 15. For camera use the documented camera command.
6. Set a fresh AIRDESK_DEV_TOKEN environment variable before starting the localhost diagnostic server.
7. Open android-client with Android Studio; actual Gradle/Pleos verification is a separate gate.
8. dotnet build windows-host/AirDesk.Host/AirDesk.Host.csproj on a Windows PC with .NET 10 desktop tooling.

## Gates
G0 protocol + pure gesture tests, G1 real webcam, G2 emulator localhost connection, G3 authentic pairing + vehicle safety, G4 Windows input, G5 screen stream, G6 Pleos Stay+ Work adapter.
Record measured performance; never invent FPS/latency. Main is the baseline; use dev/feature branches for implementation.

## Historical optional remote-Host Phase 2

Approved decisions: [ADR-003](docs/decisions/ADR-003-PHASE2-SECURE-CONTROL.md).
Start the separate host with `Run-AirDeskHost.cmd`; open `android-client/` in Android Studio.
Public identity enrollment and both-side pairing approval precede a separately approved session.
Only the explicitly labeled Mock endpoint is available for emulator input tests while actual
vehicle evidence remains unverified. Native activation cannot be enabled by a development switch.

Run `scripts/verify-phase2.ps1` for the ordered headless harness, or use the commands documented in
[PHASE2_HANDOFF](docs/testing/PHASE2_HANDOFF.md). That handoff separates build/unit evidence from
actual PLEOS, key-store, camera and native-input tests. Do not start a GUI, camera, emulator or real
input automatically as part of these tests.
