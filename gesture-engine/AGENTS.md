> **ADR-004 active product scope:** This module remains the PC-webcam MediaPipe gesture **hint** producer for an AirDesk-owned PLEOS Compose cursor. Revised Phase 2 click/drag/scroll routing belongs to Android app-owned UI, not Windows SendInput. Preserve the working Phase 1 websocket/event contracts; no vehicle camera is assumed, and do not silently add scroll event types or change schemas. Historical Windows-host remote-input integration is optional R&D, not the primary requirement.

# Gesture Engine Agent

Ownership: gesture-engine/** only. Read root AGENTS.md, GESTURE_SPEC.md, PROTOCOL_SPEC.md and canonical JSON schemas.
- No Windows SendInput, no Android changes, no OS injection, no webcam recording.
- PC camera processing only; output normalized synthetic-safe events.
- On tracking loss, release active pinch/drag and announce tracking.lost.
- All gesture states require deterministic tests with held/stale/invalid samples.
- Model .task asset is external and must not be committed.
- Do not expose dev WebSocket beyond 127.0.0.1 or use it for actual desktop control.
- A demo event stream and a camera hardware run are separate test gates.
- Report actual camera FPS/latency only when measured.
