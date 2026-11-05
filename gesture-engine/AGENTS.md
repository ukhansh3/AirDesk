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
