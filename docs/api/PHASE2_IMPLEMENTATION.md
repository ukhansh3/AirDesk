# Phase 2 implementation contract

The approved security and transport choices are in
[ADR-003](../decisions/ADR-003-PHASE2-SECURE-CONTROL.md). This document records how the existing 0.1
schemas are used; it does not introduce a new wire schema. See
[PHASE2_HANDOFF](../testing/PHASE2_HANDOFF.md) for executed tests and remaining verification.

## Channels

| Channel | Listener / emulator address | Authority |
| --- | --- | --- |
| Phase 1 diagnostics | `ws://127.0.0.1:8765` / `10.0.2.2:8765` | Existing dev token; read-only cursor only |
| Phase 2 control | `wss://127.0.0.1:8766/control` / `10.0.2.2:8766/control` | Explicitly enrolled mutual TLS peer; pairing/session policy |
| Phase 2 simulation | Same TLS listener, `/mock-input` | Paired peer, current host-approved scoped grant; Mock backend only |
| Phase 2 native input | Same TLS listener, `/input` | All real authorization checks including verified vehicle evidence; currently unavailable |

The Windows Host starts its listener only through the local UI. It binds loopback; no public bind,
firewall configuration, relay, discovery or LAN connection is implemented. Video is deferred.
`VIEW_VIDEO` requests fail with canonical `STREAM_UNAVAILABLE`.

## Bootstrap and identity

The user explicitly creates/imports public connection information using the local UIs and compares
the full certificate DER SHA-256 fingerprints on both devices. The exact public JSON export shapes
are documented in ADR-003, outside the WebSocket schema. An imported public certificate permits TLS
authentication attempts; it does not mark the device paired or create an input grant.

The PC-local offer exposes the one-time eight-digit code only on the host. `pairing.offer` is never
sent over a network. Android learns the public `pairingId` from the host connection-information
export, then sends `pairing.begin`, `pairing.claim`, and explicit `pairing.approval(side=VEHICLE)`.
Only a host UI action can supply HOST approval. Both approvals are required to mark the identity
paired and issue `pairing.completed`. Already paired TLS identities can recover their connection ID
through `pairing.begin`; reconnect never restores an old session/grant.

## Session and input binding

Each new `session.request` uses a fresh envelope session ID. The host internally binds its generated
request ID to that authenticated peer/session and returns it through existing `session.approve`.
The host alone creates the grant and validates the peer, session, grant ID, requested capability,
expiry and host consent on every input. Knowing a session/grant ID is insufficient.

There is one current input session. The shared sender sequence increases across that session's
control and input channels. Receivers reject duplicate keys, unsupported shapes, excessive size,
wrong endpoint/direction, replay and stale messages. Rejected high-sequence/future messages must
not advance accepted replay state. Producer timestamps use monotonic clocks; their relative-clock
estimate is a freshness check, never vehicle evidence or a source of session lifetime authority.
Host monotonic deadlines enforce the approved TTLs.

The Android session sender uses a bounded receipt queue across both sockets. Only one event is in
flight; the host acknowledges accepted Mock input using existing `session.heartbeat.ackSequence`
responses on `/control`. Sequence and producer time are assigned at dispatch, not at enqueue.
Only unsent pointer moves may coalesce; button/key/release transitions preserve order. An initial
heartbeat acknowledges the grant before input. Overflow/ACK timeout closes the session. The host
still drops stale/replayed messages and releases held state on rejected input; no replay window is
weakened to accommodate transport reordering.

Simulation has its own explicit endpoint and UI label. UNKNOWN safety may be shown while exercising
only the Mock backend; this produces no native permit. Unsafe/restricted/background/disconnected
states clear input. Native input remains locked because the platform safety gateway and independent
host evidence verifier have not been validated. An authenticated `PARKED` claim cannot unlock it.

`keyboard.code` follows the approved W3C UI Events code whitelist. `keyboard.text` carries committed
Unicode text, including Korean, not IME composition updates. Text, pairing codes, private keys,
tokens and input payloads must not be logged. Native Windows Unicode/IME behavior remains a separate
manual verification item; building the backend is not proof it was safely executed.

## Compatibility findings from the baseline

The baseline API reference described pairing, sessions and input as unimplemented while their JSON
schemas already existed. Phase 2 implements against those existing shapes, with the runtime limits
and unavailable native-safety verification stated here and in the handoff. The old protocol prose
listed `UNSUPPORTED`, absent from the canonical error enum; the prose is corrected without schema
changes. Pairing offer lookup and session-request correlation use local state/public bootstrap as
described above, rather than adding fields to canonical messages.
