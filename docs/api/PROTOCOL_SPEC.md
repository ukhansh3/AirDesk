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

Errors are defined exclusively by `contracts/errors/error.schema.json`. In particular, the old prose
name `UNSUPPORTED` is not a canonical error and must not be emitted. Use the existing applicable
error (for example `STREAM_UNAVAILABLE` for the deferred video capability).

Phase 2 transport, trust bootstrap, endpoint separation and approved timeout/key conventions are
specified in [ADR-003](../decisions/ADR-003-PHASE2-SECURE-CONTROL.md). The diagnostic channel remains
read-only. `/mock-input` is a distinct authenticated simulation endpoint; it cannot authorize the
native `/input` backend. Actual platform/runtime verification is recorded separately in
[PHASE2_HANDOFF](../testing/PHASE2_HANDOFF.md). No JSON Schema is changed by this clarification.
