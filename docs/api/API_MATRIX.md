# AirDesk feature → contract matrix (v0.1)

This matrix maps contract responsibility; it is **not** a list of implemented HTTP endpoints.

| Feature family | Schema / spec | Producer → consumer | Status | Gate |
| --- | --- | --- | --- | --- |
| GEST-01..08 | contracts/gesture/gesture-event.schema.json; docs/features/GESTURE_SPEC.md | Python → Android | Synthetic/loopback tested; camera USER_REPORTED | Actual model/device and performance recorded separately |
| CTRL-DIAG-01..06 | contracts/control/messages.schema.json; docs/features/CONTROL_SPEC.md | Android ↔ Python | hello/accepted/rejected/closed implemented; generic diagnostic heartbeat remains reserved | Read-only Phase 1 regression |
| PAIR-01..08 | contracts/pairing/pairing-messages.schema.json; docs/features/PAIRING_SPEC.md | Windows ↔ Android | Phase 2 mTLS/emulator implementation; runtime evidence in handoff | ADR-003 + both-side approval; manual Keystore/TLS test |
| SESS-01..06 | contracts/session/session-messages.schema.json, session-state.schema.json | Windows ↔ Android | Phase 2 state/Grant implementation; snapshot schema remains a UI contract | Authentication, host consent, TTL, replay, watchdog |
| INPUT-01..08 | contracts/input/input-event.schema.json; docs/features/INPUT_SPEC.md | Android → Windows | Mock endpoint and gated native code; native activation unavailable | Host grant + independently verified vehicle safety |
| ERROR | contracts/errors/error.schema.json | Bidirectional | Canonical enum used by Phase 2 runtime | Reason-specific negative tests |
| IPC | docs/features/IPC_SPEC.md | Python ↔ Windows process | DESIGN_DRAFT | Choose process bridge + ownership |
| STREAM-01..10 | docs/features/STREAMING_SPEC.md | Windows video → Android decoder | SDK_VERIFY / NONE | PoC with PLEOS / licensing |
| SAFE-01..10 | docs/features/SAFETY_SPEC.md | Verified PLEOS → Android → Windows policy | UNKNOWN / native locked; real evidence verifier unavailable | Actual SDK/permission/evidence verification |
| REST | contracts/rest/README.md | n/a | NONE_INTENTIONALLY | Authenticated backend use case and ADR |

Official implementation contract: [API_REFERENCE.md](API_REFERENCE.md). Do not introduce Fastify endpoints just because a feature has an identifier.

Phase 2 transport details: [PHASE2_IMPLEMENTATION.md](PHASE2_IMPLEMENTATION.md).
Only [PHASE2_HANDOFF](../testing/PHASE2_HANDOFF.md) records executed test/build counts; implementation
presence in this matrix does not certify actual PLEOS safety or native input behavior.

Every feature PR must include schema compatibility, producer/consumer ownership, rollback and a test demonstrating failure behavior.
