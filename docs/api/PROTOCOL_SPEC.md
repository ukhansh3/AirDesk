# Protocol v0.1 — DRAFT

Canonical envelope:
~~~json
{"protocolVersion":"0.1","type":"gesture.move","sessionId":"synthetic-demo","sequence":1,"timestampMs":1234567,"payload":{"x":0.4,"y":0.7,"confidence":0.95}}
~~~

- Strict protocolVersion; breaking changes require version migration.
- Per-session sequence strictly increases. Inputs are validated and stale/replayed messages dropped.
- timestampMs represents producer monotonic time, NOT authorization.
- Normalized coordinates x/y within [0,1]. No NaN, missing fields or arbitrary untyped payloads.
- Test message types: auth.hello, auth.accepted, gesture.move, gesture.pinch_start, gesture.pinch_end, gesture.drag_start, gesture.drag_move, tracking.lost.
- Remote OS input is explicitly DISABLED on the diagnostic channel. Future authenticated input contract is separate from gesture hints.
- Raw webcam video is not sent by the gesture transport.
- All protocol DTOs must validate against contracts/ JSON Schemas.

Errors: INVALID_VERSION, INVALID_PAYLOAD, UNAUTHENTICATED, STALE, REPLAY, UNSAFE_VEHICLE, RATE_LIMITED, UNSUPPORTED.
