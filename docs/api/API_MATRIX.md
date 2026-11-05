# AirDesk feature → contract matrix (v0.1)

This matrix maps contract responsibility; it is **not** a list of implemented HTTP endpoints.

| Feature family | Schema / spec | Producer → consumer | Status | Gate |
| --- | --- | --- | --- | --- |
| GEST-01..08 | contracts/gesture/gesture-event.schema.json; docs/features/GESTURE_SPEC.md | Python → Android | SYNTHETIC_TESTED; camera NOT_RUN | Real webcam+model |
| CTRL-DIAG-01..06 | contracts/control/messages.schema.json; docs/features/CONTROL_SPEC.md | Android ↔ Python | hello/accepted IMPLEMENTED; other events PROPOSED | WS integration tests |
| PAIR-01..08 | contracts/pairing/pairing-messages.schema.json; docs/features/PAIRING_SPEC.md | Windows ↔ Android | CONTRACT_DRAFT | Security ADR + mutual approval |
| SESS-01..06 | contracts/session/session-messages.schema.json, session-state.schema.json | Windows ↔ Android | CONTRACT_DRAFT | Authentication, TTL, replay |
| INPUT-01..08 | contracts/input/input-event.schema.json; docs/features/INPUT_SPEC.md | Android → Windows | CONTRACT_DRAFT / DISABLED | Host grant + vehicle safety |
| ERROR | contracts/errors/error.schema.json | Bidirectional | CONTRACT_DRAFT | Reason-specific tests |
| IPC | docs/features/IPC_SPEC.md | Python ↔ Windows process | DESIGN_DRAFT | Choose process bridge + ownership |
| STREAM-01..10 | docs/features/STREAMING_SPEC.md | Windows video → Android decoder | SDK_VERIFY / NONE | PoC with PLEOS / licensing |
| SAFE-01..10 | docs/features/SAFETY_SPEC.md | Verified PLEOS → Android → Windows policy | FAIL_CLOSED_SKELETON | Actual SDK capability |
| REST | contracts/rest/README.md | n/a | NONE_INTENTIONALLY | Authenticated backend use case and ADR |

Official implementation contract: [API_REFERENCE.md](API_REFERENCE.md). Do not introduce Fastify endpoints just because a feature has an identifier.

Every feature PR must include schema compatibility, producer/consumer ownership, rollback and a test demonstrating failure behavior.
