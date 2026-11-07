> **Active product flow (ADR-004; planned):** developer PC webcam → local MediaPipe → gesture hints → emulator diagnostic cursor → **future** AirDesk-owned Compose UI target router (click/drag/scroll) and focused virtual keyboard. No global OS input. UNKNOWN vehicle safety permits labeled isolated DEMO previews, not operational Work input. Current Windows remote-control and video flows below are optional historical R&D, not the new Phase 2 dependencies.

# AirDesk data flow

## Gesture diagnostic
PC camera → OpenCV capture → MediaPipe Hand Landmarker (local model) → 21 landmarks → gesture state machine → JSON Schema event → Python 127.0.0.1:8765 WS → debug PLEOS cursor.
No camera frames or Windows keystrokes are transmitted.

## Future remote control
Android touch or gesture → verified vehicle safety → approved authenticated control session → Windows host policy → allowed OS input.
Transport heartbeat loss → local lock, host watchdog release. UI never assumes a message was executed without acknowledgement.

## Future remote video
Windows desktop capture → encoder → selected streaming transport → Android decoder / view. Hide stream locally as soon as safety fails.

## Data boundaries
Camera inference local. Gesture hints are not authorization. Driver safety is not inferred from camera or network. No cloud backend, motion history or input logs in Phase 0.
