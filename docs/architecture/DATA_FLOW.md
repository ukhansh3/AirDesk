> **Active product flow (ADR-004/005; DEMO code implemented):** developer PC webcam → existing MediaPipe/WS hints → strict diagnostic reducer → ordered accepted-event callbacks → measured app-owned targets → local mouse actions and focused full PC keyboard. UI state snapshots are visual projections, not action queues. Keyboard text remains local. UNKNOWN safety permits the explicitly enabled isolated DEBUG DEMO, not operational Work. Actual PLEOS validation remains separate; Windows/video paths below are optional historical R&D.

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
