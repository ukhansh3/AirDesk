# AirDesk

**Private development repository** for a parked-vehicle remote-work system: a Windows host, a PLEOS Android client, and a Python/MediaPipe hand-gesture engine.

> Status: **architecture + testable prototype scaffold**, not a working remote desktop. The default client is **locked** until a real, verified vehicle safety gateway and session authorization are integrated. The local synthetic demo does **not** control the Windows desktop.

## Read first

1. [DEVELOPMENT_GUIDE.md](DEVELOPMENT_GUIDE.md)
2. [AGENTS.md](AGENTS.md)
3. [Architecture](docs/architecture/SYSTEM_DESIGN.md)
4. [Protocol](docs/api/PROTOCOL_SPEC.md)
5. [Test plan](docs/testing/TEST_PLAN.md)

## System boundaries

- `gesture-engine/`: PC webcam → MediaPipe 21 landmarks → gestures (Python).
- `windows-host/`: Windows connection/remote-input host (C#, WPF), **placeholder until security validation**.
- `android-client/`: PLEOS Compose UI, gesture diagnostic and fail-closed safety gate.
- `streaming/`: Sunshine/Moonlight vs WebRTC feasibility studies; **no streaming selected yet**.
- `contracts/`: canonical machine-readable control, gesture and input schemas.
- `integration-tests/`: cross-platform E2E and safety scenarios.

## Local test without webcam

```powershell
cd gesture-engine
python -m pip install -e ".[dev]"
python -m unittest discover -s tests -v
$env:PYTHONPATH="src"
python -m airdesk_gesture.cli synthetic --count 15
```

Webcam testing requires independently installing the documented dependencies, obtaining Google's approved Hand Landmarker `.task` model, and granting camera permission. Refer to [gesture-engine/README.md](gesture-engine/README.md).

## Non-goals in the scaffold

No live Windows SendInput, no production pairing, no raw video transmission, no cloud server, no motion restriction bypass, and no full PLEOS verification. Each must be explicitly validated before activation.

**Git workflow:** `main` stores the documented baseline. Work in `dev` or feature branches and merge by reviewed PR. Never commit keys, pairing secrets, camera images or personal data.
