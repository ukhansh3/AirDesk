# ADR-001 — Separate AirDesk repository (ACCEPTED)
Date: 2026-11-05.
AirDesk is developed independently from Pleos Stay+ and integrated into Work Mode only after Windows/PLEOS tests.
Reasons: uncertain PLEOS camera/input/streaming support, strict vehicle safety and independent gesture experimentation.
Initial webcam is physically connected to the Windows developer PC. A debug-only WebSocket delivers events to the PLEOS emulator.
This ADR does not approve automatic remote PC access, public signaling, biometric identification or video streaming framework selection.
