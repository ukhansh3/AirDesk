# Feature and contract responsibility

| IDs | Interface | Owner | Status |
| --- | --- | --- | --- |
| GEST-01..08 | gesture-event schema | Gesture | Deterministic engine planned |
| CTRL-01..04 | debug control schema | Contracts + Android | Synthetic-only |
| INPUT-01..10 | input-event schema | Windows + Android | DISABLED |
| PAIR-01..08 | secure session pairing | Windows + Android | REVIEW_REQUIRED |
| STREAM-01..10 | chosen video transport | Streaming | SDK_VERIFY |
| SAFE-01..10 | Android vehicle gate | Android | fail-closed skeleton |
| REST | OpenAPI | Future server | NOT PLANNED for PoC |

Definitions in docs/features; event envelopes in docs/api/PROTOCOL_SPEC.md.
