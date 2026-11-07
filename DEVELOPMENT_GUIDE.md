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

## Phase 2

Approved decisions: [ADR-003](docs/decisions/ADR-003-PHASE2-SECURE-CONTROL.md).
Start the separate host with `Run-AirDeskHost.cmd`; open `android-client/` in Android Studio.
Public identity enrollment and both-side pairing approval precede a separately approved session.
Only the explicitly labeled Mock endpoint is available for emulator input tests while actual
vehicle evidence remains unverified. Native activation cannot be enabled by a development switch.

Run `scripts/verify-phase2.ps1` for the ordered headless harness, or use the commands documented in
[PHASE2_HANDOFF](docs/testing/PHASE2_HANDOFF.md). That handoff separates build/unit evidence from
actual PLEOS, key-store, camera and native-input tests. Do not start a GUI, camera, emulator or real
input automatically as part of these tests.
