# AirDesk machine-readable contracts

Version 0.1. JSON Schema 2020-12. These schemas describe message shapes, **not** permissions or a deployed network service.

| File | Status |
| --- | --- |
| gesture/gesture-event.schema.json | Implemented diagnostic event format |
| control/messages.schema.json | auth.hello/accepted currently used; other events reserved |
| input/input-event.schema.json | Fully shaped future OS input events; disabled |
| pairing/pairing-messages.schema.json | Proposed 8-digit two-sided pairing; disabled |
| session/session-messages.schema.json | Proposed authenticated session control; disabled |
| session/session-state.schema.json | UI projection only; not an authority |
| errors/error.schema.json | Shared structured error format; proposed |

For payload and state behavior see docs/api/API_REFERENCE.md. All message types reject unknown fields. A schema-valid input event is **never by itself authorized Windows input**. The debug websocket does not accept input events.

Before changing event fields, bump protocol version as needed and update producer, consumer, tests and migration notes.
