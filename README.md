# AirDesk

> **UX clarification (2026-11-07):** This is a **desktop-like PLEOS Work input surface** with a full PC-style QWERTY keyboard (function/modifier/navigation keys, supported local shortcuts) and mouse-style left/right click, drag and scroll. **Do not build a mobile phone keypad** as the primary keyboard. All actions remain scoped to AirDesk-owned Compose UI, not Windows or system-wide Android control. Details: [keyboard specification](docs/features/VIRTUAL_KEYBOARD_SPEC.md) and [mouse interaction specification](docs/features/GESTURE_UI_INTERACTION_SPEC.md).

> **Current product direction (2026-11-07):** Revised Phase 2 now has an isolated **desktop DEMO inside AirDesk-owned Compose UI**: an actionable cursor, local context menus, drag/drop, scrolling and a full docked PC keyboard with Korean/English editing. Production Work stays locked while official vehicle safety is UNKNOWN. Code/model tests and actual PLEOS validation are separate; see [revised handoff](docs/testing/PHASE2_REVISED_HANDOFF.md) and [ADR-005 implementation choices](docs/decisions/ADR-005-DESKTOP-DEMO-INPUT.md). The PC webcam remains the development camera; vehicle camera and official parked/UX capability remain unverified.

## Use the desktop DEMO

1. Start the existing PC camera panel with `Run-AirDesk.cmd` and select your camera/model.
2. Open `android-client/` in Android Studio and run the debug `app` on your emulator.
3. For a readable development view, click **큰 키보드 창** on the PC panel and resize it first.
   Default **양손 열손가락** is the experimental AIR profile. Select **연결 → 자동 보정 시작** once;
   use the five-second preparation time, then follow the neutral/ten-finger prompts hands-free.
   Stable poses advance automatically; return to neutral between fingers. No repeated capture clicks.
4. Select **메모 입력 → 타건 켜기**, show a neutral pose to rearm, then flex/release individual fingers.
   Ten fingertip markers and held-key feedback use the complete keyboard layout. Move the right hand
   into its side mouse area and rearm for index/Pinch mouse controls. All text stays in app-owned editors.
5. **검지·Pinch** preserves the earlier camera connection/enable, click, context, drag and scroll profile.
   The complete PC keyboard and side mouse controls fit one view, scaled on small screens. F1 opens
   help; F2–F12 are visibly disabled. Ctrl+A/C/V/X/Z remain editor-local.

AIR typing uses calibrated motion inference, not physical contact. Usable webcam typing accuracy,
camera placement and latency are **NOT_VERIFIED**. Read [AIR setup/tests](docs/testing/AIR_TYPING_HANDOFF.md)
and the [approved experimental spec](docs/features/AIR_TYPING_SPEC.md). Reconnect requires recalibration
and explicit input enable. No Windows Host is required; official vehicle Work input remains blocked.

No Windows Host, pairing or mTLS setup is required for this local DEMO. Text is not sent to the PC.
Run `scripts/verify-input-first.ps1` for its automatic checks; the separate historical host harness
retains its original failed evidence. Follow the revised handoff for manual PLEOS checks.

> **Historical implementation preserved:** The Windows Host/mTLS/remote-input sections below describe prior optional research and existing code, not the required in-app product path. Its actual TLS integration failed; native Windows input is locked. Do not treat its automated tests as proof of revised Phase 2.


**Private development repository** for a parked-vehicle remote-work system: a Windows host, a PLEOS Android client, and a Python/MediaPipe hand-gesture engine.

> Status: **Phase 1 gesture diagnostics + Phase 2 secure-session/Mock input development**. Actual Windows remote input stays **locked** until a real vehicle safety gateway and host-verifiable evidence are validated. Streaming is deferred. See the [Phase 2 handoff](docs/testing/PHASE2_HANDOFF.md) for implemented behavior, actual tests and remaining gates.

The current development environment fails the real TLS integration test with `SEC_E_NO_CREDENTIALS`.
Policy/JVM tests and builds pass, but actual device pairing/mTLS is not yet verified. Run the handoff
checks outside the restricted agent environment before treating the integration as usable.

## Read first

The camera preview defaults to a proportional **87-key tenkeyless reference**. Choose **기존 AIR 입력 정렬**
when testing the existing Android AIR hit map. This camera-only change is documented in
[ADR-008](docs/decisions/ADR-008-TKL-CAMERA-REFERENCE.md).

The current one-webcam experiment adds a green English/Hangul outline keyboard to the local PC
camera preview. Key interiors remain transparent; visibility and opacity are adjustable. It is
an image-space reference, not verified spatial contact sensing. See
[ADR-007](docs/decisions/ADR-007-SINGLE-WEBCAM-KEYBOARD-OVERLAY.md) and the
[AIR handoff](docs/testing/AIR_TYPING_HANDOFF.md) for implementation and manual checks.

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
