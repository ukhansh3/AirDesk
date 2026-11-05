# Test plan — initial harness vs actual hardware

## Runnable deterministic tests
1. Gesture state transitions and no stuck pinch/drag.
2. Sequence monotonicity and loss-of-tracking behavior.
3. JSON Schema acceptance/rejection for synthetic examples.
4. Diagnostic WS local auth success/failure (needs websockets package).

## Manual required gates
CAM-01 real camera+MediaPipe .task installed and landmarks observed.
PLEOS-01 emulator 2.1.0 ADB device, 10.0.2.2 connection, diagnostic cursor displays.
SAFE-01 unknown and moving lock screen/input without network.
PAIR-01 mutual code approval and revocation.
INPUT-01 host permission, command rejection, release on disconnect.
STREAM-01 actual screen render and latency; standalone vs embedded checked separately.
E2E-01 end-to-end PC webcam -> emulator -> authorized Windows control (only after gates).

Record exact hardware, emulator/version, commands, actual result and NOT_RUN separately. Never report a simulation as real camera proof.
