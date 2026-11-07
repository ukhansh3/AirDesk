# AirDesk

**Private development repository** for a parked-vehicle remote-work system: a Windows host, a PLEOS Android client, and a Python/MediaPipe hand-gesture engine.

> Status: **Phase 1 gesture diagnostics + Phase 2 secure-session/Mock input development**. Actual Windows remote input stays **locked** until a real vehicle safety gateway and host-verifiable evidence are validated. Streaming is deferred. See the [Phase 2 handoff](docs/testing/PHASE2_HANDOFF.md) for implemented behavior, actual tests and remaining gates.

The current development environment fails the real TLS integration test with `SEC_E_NO_CREDENTIALS`.
Policy/JVM tests and builds pass, but actual device pairing/mTLS is not yet verified. Run the handoff
checks outside the restricted agent environment before treating the integration as usable.

## Read first

1. [DEVELOPMENT_GUIDE.md](DEVELOPMENT_GUIDE.md)
2. [AGENTS.md](AGENTS.md)
3. [Architecture](docs/architecture/SYSTEM_DESIGN.md)
4. [Protocol](docs/api/PROTOCOL_SPEC.md)
5. [Test plan](docs/testing/TEST_PLAN.md)

## System boundaries

- `gesture-engine/`: PC webcam → MediaPipe 21 landmarks → gestures (Python).
- `windows-host/`: separate WPF host, certificate enrollment, pairing/session consent, Mock backend and gated native input code.
- `android-client/`: PLEOS Compose UI, gesture diagnostic and fail-closed safety gate.
- `streaming/`: Sunshine/Moonlight vs WebRTC feasibility studies; **no streaming selected yet**.
- `contracts/`: canonical machine-readable control, gesture and input schemas.
- `integration-tests/`: cross-platform E2E and safety scenarios.

## Local test without webcam

```powershell
cd gesture-engine
python -m pip install -e ".[dev,ws]"
python -m unittest discover -s tests -v
$env:PYTHONPATH="src"
python -m airdesk_gesture.cli synthetic --count 15
```

Webcam testing requires independently installing the documented dependencies, obtaining Google's approved Hand Landmarker `.task` model, and granting camera permission. Refer to [gesture-engine/README.md](gesture-engine/README.md).

## Run the separate Windows Host

Open `Run-AirDeskHost.cmd` with the .NET 10 SDK installed. This starts the AirDesk WPF application;
it does not open an emulator or change Stay+. The local UI starts the loopback TLS service explicitly.
Use the Android app's secure-connection screen for public certificate enrollment, pairing and session
approval. Follow the [manual checklist](docs/testing/PHASE2_HANDOFF.md) before using Mock input.
`Run-AirDesk.cmd` remains the separate Phase 1 webcam diagnostic panel.

## Deferred and unavailable features

Native Windows input activation, production deployment, raw video transmission, cloud connectivity
and full PLEOS safety verification are unavailable. Mock input is explicitly labeled and cannot
unlock native authority. Each deferred feature requires its own validation before activation.

**Git workflow:** `main` stores the documented baseline. Work in `dev` or feature branches and merge by reviewed PR. Never commit keys, pairing secrets, camera images or personal data.
