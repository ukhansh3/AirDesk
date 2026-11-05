# AirDesk System Design

## Runtime components
1. Webcam / OpenCV -> MediaPipe 21-landmark detection (camera frames local, no recordings).
2. Gesture Engine -> normalized [0,1] pointer events, pinch, drag, tracking.lost, jitter filtering.
3. Local WebSocket diagnostics -> Android preview cursor; ephemeral dev token; no Windows OS input.
4. Android PLEOS client -> Compose UI and fail-closed safety gate; future authenticated remote video+input.
5. Windows Host -> explicit user confirmation, authenticated session; future SendInput and display capture.
6. Streaming adapter -> Sunshine/Moonlight and WebRTC feasibility PoCs; no selected transport yet.

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
