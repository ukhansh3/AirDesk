> **Desktop UX requirement:** The future AirDesk Compose workspace is PC-like: registered work targets, mouse-style left/right click/drag/scroll, and a **docked full desktop QWERTY keyboard** (function/modifier/navigation rows and supported app-local shortcuts), **not a mobile keypad**. Input remains within AirDesk-owned UI, with explicit keyboard focus and Korean IME composition. Gesture mappings for right-click/scroll require approval. See [keyboard specification](../features/VIRTUAL_KEYBOARD_SPEC.md) and [gesture UI specification](../features/GESTURE_UI_INTERACTION_SPEC.md).

> **Current product architecture (ADR-004/005):** PC webcam (dev only) → Phase 1 MediaPipe hints → unchanged local debug WS → accepted-event observer → measured app-owned DEMO target registry/router → local document/context/drag/scroll callbacks and full PC keyboard controller. Router and composer model tests are distinct from actual PLEOS UI validation. Operational safety is UNKNOWN and locked. The older Windows Host/WSS/streaming architecture below remains isolated historical research, not a dependency. See [DATA_FLOW.md](DATA_FLOW.md).

# AirDesk System Design

## Runtime components
1. Webcam / OpenCV -> MediaPipe 21-landmark detection (camera frames local, no recordings).
2. Gesture Engine -> normalized [0,1] pointer events, pinch, drag, tracking.lost, jitter filtering.
3. Local WebSocket diagnostics -> Android preview cursor; local dev token (desktop panel reads ignored local.properties); no Windows OS input.
4. Android PLEOS client -> Compose diagnostic and secure-session UIs, touch/keyboard/gesture candidates, fail-closed actual safety gateway.
5. Windows Host -> explicit public certificate enrollment and mutual TLS, pairing/session approval, scoped grants, Mock input engine, separately gated native backend. Actual native activation remains unavailable; display capture is deferred.
6. Streaming adapter -> Sunshine/Moonlight and WebRTC feasibility PoCs; no selected transport yet.

Phase 2 WSS binds 127.0.0.1:8766 with distinct `/control`, `/mock-input`, and `/input` routes.
The Android emulator uses 10.0.2.2. Mock input exercises the authorized event path without native
calls while actual safety stays UNKNOWN. It cannot construct native authority. See
[ADR-003](../decisions/ADR-003-PHASE2-SECURE-CONTROL.md) and
[Phase 2 handoff](../testing/PHASE2_HANDOFF.md) for exact limits and evidence.

## Trust boundaries
- A raw gesture event is never authorization to inject OS input.
- Production Windows input requires BOTH a verified parked/UX-allowed Android gate and an authenticated host-side session grant.
- If vehicle state goes UNKNOWN/MOVING/restricted, block local screen and input immediately and release held host input.
- The Python localhost debug WS must not be publicly bound or repurposed for production.
- No cloud signaling, public tunnel, NAT traversal or database in Phase 0.

## First test
PC webcam -> Python MediaPipe -> detected landmarks -> synthetic-safe event transport -> rendered Android cursor. Test in PLEOS Connect emulator and record environment.
This does not establish vehicle camera access or permission to run in motion.

## Non-goals
Automatic sync of files, device GPS tracking, vehicle CAN/control bypass, unattended desktop access, remote screen recording or streaming video over REST.
