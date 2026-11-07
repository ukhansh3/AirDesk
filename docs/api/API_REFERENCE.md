# AirDesk API / Protocol Reference v0.1 — Contract-first

**Status: private emulator-first development API, not a production remote desktop.** Phase 1 is a local diagnostic WebSocket for a read-only cursor. Phase 2 adds a separate mutually authenticated host/session and Mock input path against the existing schemas. Actual native input remains unavailable until independently verified vehicle safety; video is deferred. Implementation limits are in [PHASE2_IMPLEMENTATION.md](PHASE2_IMPLEMENTATION.md), with executed evidence in [PHASE2_HANDOFF](../testing/PHASE2_HANDOFF.md).

## 0. Canonical sources and versioning

- This document describes; machine-readable `contracts/**/*.schema.json` **define** accepted shapes.
- Protocol `0.1` (exact string). All senders must include `protocolVersion` and fail on unsupported versions; no implicit downgrade.
- Transport architecture:
  - Gesture hints: Windows/Python → PLEOS Android over debug-only WebSocket (existing code).
  - Diagnostic control: two-way debug WebSocket (existing hello/accepted only).
  - Pairing/session: separate mutually authenticated WSS control channel, per ADR-003.
  - Input: separate authorized WSS endpoints; Mock simulation and unavailable native authority remain distinct.
  - Video: dedicated stream (not selected). **Never transmit video frames as REST JSON, Supabase rows or gesture payloads**.
- **No REST routes are currently approved or implemented.** The absence of OpenAPI is deliberate, not an implied `POST /sessions` contract.

## 1. Common event envelope

| Field | JSON type | Rules |
| --- | --- | --- |
| protocolVersion | string | Exactly `0.1` |
| type | string | Enumerated per schema and direction |
| sessionId | string | Nonempty, 1–128, characters [A-Za-z0-9._-] |
| sequence | integer | Nonnegative, strictly increasing **per sender + session**; not across both directions |
| timestampMs | integer | Nonnegative producer monotonic time; diagnostic ordering only, **not absolute wall time** |
| payload | object | Schema-specific properties; unknown fields rejected |

A receiver validates JSON size, shape, protocol, message type, session binding and sequence before acting. **A schema-valid message is not an authenticated or authorized command.** Reject out-of-order or duplicate sequence numbers and stop remote input on network loss. Each sender has its own independent sequence space.

Example (synthetic pointer event; valid):
~~~json
{
  "protocolVersion":"0.1",
  "type":"gesture.move",
  "sessionId":"synthetic-demo",
  "sequence":5,
  "timestampMs":123456789,
  "payload":{"x":0.45,"y":0.31,"confidence":0.96}
}
~~~

## 2. Gesture API: Windows/Python → Android

Schema: `contracts/gesture/gesture-event.schema.json`. **Gesture events are hints, not PC commands.**

| type | payload (all listed fields required) | Semantics |
| --- | --- | --- |
| gesture.move | x, y, confidence | Update preview pointer in normalized app viewport |
| gesture.pinch_start | x, y, confidence | Gesture recognizer pressed; host must NOT treat as authorized click directly |
| gesture.pinch_end | x, y, confidence | Released pinch; if eventual input grant exists, release the held action |
| gesture.drag_start | x, y, confidence | Threshold crossed while pinched |
| gesture.drag_move | x, y, confidence | Continued movement while dragging |
| tracking.lost | reason | Stop processing motion and release any held input |

Numerics `x`, `y`, `confidence` are floating values in `[0,1]`. `reason`: `hand_not_detected | camera_disconnected | capture_failed | model_failed | tracking_timeout`. No raw 21-point landmarks or original webcam frames are sent by this diagnostic contract.

The producer must send `gesture.pinch_end` followed by `tracking.lost` on loss during a pinch, where possible. Receiver/host must **also release independently after its watchdog timeout**, even if these events are lost. Empty or stale frames never prolong a drag.

Acceptance: bounds, missing fields, unknown gesture, non-finite values, malformed coordinates and duplicate sequence rejected; explicit release and one lost-event after disappearance.

## 3. Diagnostic control API: client ↔ Python local server

Schema: `contracts/control/messages.schema.json`. Debug listener `127.0.0.1:8765`, Android emulator usually connects through `10.0.2.2:8765` (must verify on the actual PLEOS emulator). Debug transport has no remote OS input.

| type | direction | payload | handling |
| --- | --- | --- | --- |
| auth.hello | Android → Python | token: string, 24–512 chars | Local dev-only token from environment; compare without logging |
| auth.accepted | Python → Android | mode: `diagnostic_only` | Permit **read-only visual cursor**; never a remote control grant |
| auth.rejected | Python → Android | errorCode: UNAUTHENTICATED / INVALID_VERSION / RATE_LIMITED | Close or show diagnostic failure |
| heartbeat | either | lastReceivedSequence: nonnegative int | Proposed diagnostic liveness; code not yet implemented |
| session.locked | host → Android | reason: UNSAFE_VEHICLE / AUTH_REVOKED / APP_BACKGROUND / STALE_HEARTBEAT / NETWORK_FAILURE | Disable read-only preview and blank future remote UI |
| session.closed | either | reason: USER_REQUEST / EXPIRED / ERROR / REVOKED / SAFETY_LOCK | Release resources and reset counters |

Example hello (placeholder, not a real reusable secret):
~~~json
{
  "protocolVersion":"0.1",
  "type":"auth.hello",
  "sessionId":"diagnostic-client",
  "sequence":0,
  "timestampMs":123456789,
  "payload":{"token":"REPLACE_WITH_UNIQUE_DEV_TOKEN"}
}
~~~

**Security:** local `ws://` and dev token do not establish mutual identity, transport encryption, host approval or a Windows input grant. Never expose this service publicly or re-use it for production input. The real control transport requires a separate security ADR.

## 4. Pairing API: Phase 2 implementation

Schema: `contracts/pairing/pairing-messages.schema.json`. The code is **8 digits**, expires after 120 seconds, and five failures impose a five-minute cooldown. It is never persisted or logged. Both user interfaces must approve the named remote counterpart. **The 8-digit code is an out-of-band user-transcribed secret: the host displays it locally, and the Android client submits it only when the user types it. Broadcasting pairing.offer to an unauthenticated network peer would defeat the purpose of pairing.**

| type | direction | payload | outcome |
| --- | --- | --- | --- |
| pairing.begin | client → host | deviceLabel | Start single bounded attempt |
| pairing.offer | host service → **local host UI only** | pairingId, expiresAtMs, code | Show one-time code locally; NEVER send it to an untrusted client before claim |
| pairing.claim | peer → host | pairingId, code, deviceLabel | Request connection, rate limit, avoid account enumeration |
| pairing.approval | either | pairingId, side=HOST/VEHICLE, approved=boolean | Only both explicit approvals can advance |
| pairing.completed | host → vehicle | pairingId, connectionId | Grant a *connection identity*, **not** an input permission |
| pairing.revoked | either | pairingId, reason | Invalidate code and bound connection; release all input |

`expiresAtMs` here is a **UTC epoch milliseconds expiry** displayed to users; it is separate from the envelope's producer-monotonic timestamp. Authentication and expiration checks use an authoritative secure clock, not client-provided data.

Supported rejection reasons: expired, already claimed, invalid format, too many attempts, approval denied, session conflict, identity mismatch. Explicit return error codes via the common protocol error contract, not ambiguous booleans.

**Approved design:** ADR-003 specifies mutual TLS, explicit certificate-fingerprint enrollment, protected OS key storage, local PC initiation, expiration and revocation. Runtime/device verification remains separate from design approval; see the handoff. Avoid invented cryptography or unsecured auto-pairing.

## 5. Session API: Phase 2 implementation

Schema: `contracts/session/session-messages.schema.json`. Transport connectivity, session status and input grants are different.

| type | direction | payload | outcome |
| --- | --- | --- | --- |
| session.request | Android → host | connectionId, capabilities[] | Request specific VIEW_VIDEO / POINTER / KEYBOARD powers |
| session.approve | host → Android | requestId, approved, expiresAtMs | Explicit host-user approval response |
| session.granted | host → Android | grantId, capabilities[], expiresAtMs | Short-lived scoped permissions; host validates its own grant record |
| session.heartbeat | both | ackSequence | Update liveness, never extend an expired consent implicitly |
| session.revoke | either | reason | Stop display/input locally first; notify peer if possible |
| session.ended | host → Android | reason | Clear session state, IDs and held input |

The session state machine is DISCONNECTED → PAIRING → WAITING_APPROVAL → CONNECTED → LOCKED / ERROR → DISCONNECTED. CONNECTED alone **never** means PC control is allowed. Grants expire within ten minutes and require explicit fresh approval to renew; a one-second heartbeat and three-second watchdog do not extend their lifetime.

An input grant is always cryptographically bound to the authenticated peer and current session. The caller cannot become HOST or VEHICLE just by sending a `side` field; the receiver checks the authenticated identity.

Do not auto-resume input after reconnect or on transition back to PARKED: require fresh safety verification and appropriate host consent. Host must independently release keys/buttons on missed heartbeat.

Session state snapshot schema `contracts/session/session-state.schema.json` is a UI projection; it cannot serve as the authoritative vehicle state or permission source.

## 6. Input API: Mock implementation; native activation unavailable

Schema: `contracts/input/input-event.schema.json`. Inputs MUST come only from an authenticated, approved session over an encrypted channel. The current diagnostic server intentionally has **no input handler**.

| type | required payload | validation / behavior |
| --- | --- | --- |
| pointer.move | grantId, displayId, x, y | Normalize x/y to 0..1 for named target display |
| pointer.down | grantId, button | button=left/right/middle; hold tracked per session |
| pointer.up | grantId, button | idempotent release of held button |
| pointer.scroll | grantId, deltaX, deltaY, unit | bounded integer [-120,120], unit=line |
| keyboard.down | grantId, code | approved W3C UI Events code whitelist in ADR-003 |
| keyboard.up | grantId, code | releases held key; idempotent |
| keyboard.text | grantId, text | 1..1024 chars; never logged; cannot bypass OS permission |
| input.release_all | reason | emergency release; host prioritizes release even on safety revocation |

`grantId` is a session-bound opaque identifier (16–128 chars). **It is not a secret or independent proof of authorization.** Every command must additionally pass peer identity, host-approved scope, local vehicle safe-state, time freshness and sequence checks. Inputs are not retried blindly: duplicate pointer.down or keyboard.down can leave held state; safely reconcile with acknowledgements and release_all.

Coordinate mapping must include content viewport/letterboxing, display orientation and multi-monitor selection; screen pixels are **not** transmitted as raw unbounded x/y. For secure desktop/UAC, respect Windows UIPI restrictions; never elevate or bypass.

Example valid input message **for contract tests** (not a native authorization):
~~~json
{
  "protocolVersion":"0.1",
  "type":"pointer.move",
  "sessionId":"illustrative-session",
  "sequence":10,
  "timestampMs":123456790,
  "payload":{"grantId":"illustrative-grant-0001","displayId":"primary","x":0.4,"y":0.6}
}
~~~

## 7. Shared errors

Schema: `contracts/errors/error.schema.json`, type `protocol.error`.

Required payload fields:
- code enum: INVALID_VERSION, INVALID_PAYLOAD, UNAUTHENTICATED, FORBIDDEN, EXPIRED, RATE_LIMITED, REPLAY, STALE, UNSAFE_VEHICLE, NOT_PAIRED, SESSION_NOT_FOUND, HOST_DENIED, STREAM_UNAVAILABLE, INTERNAL_ERROR
- message: short safe user-readable message, 1–180 chars; no secret or stack trace
- retryable: boolean
- correlationId: 1–128 chars, never a token
- retryAfterMs: optional 0–60000 (only for truly retryable rate-limit/backoff)

Example:
~~~json
{"protocolVersion":"0.1","type":"protocol.error","sessionId":"illustrative-session","sequence":13,"timestampMs":123456791,"payload":{"code":"UNSAFE_VEHICLE","message":"차량 상태를 확인할 수 없어 원격 조작을 중단했습니다.","retryable":false,"correlationId":"error-example-13"}}
~~~

Behavior: invalid version → fail closed; malformed payload → reject; expired session → release and require fresh auth; network loss → local lock + host independent release; stale frame → drop without side effects.

## 8. Safety and authorization ordering

A message is accepted for real remote input ONLY when ALL checks pass:
1. Message shape + version valid.
2. Peer mutually authenticated by production secure transport.
3. Session and grant exist, are unexpired, and are scoped to requested capabilities.
4. Strictly increasing sender sequence and freshness within bounded window.
5. PC user has explicitly approved current remote session.
6. Android foreground, vehicle state **PARKED_CONFIRMED** and UX unrestricted verified through approved vehicle SDK.
7. Host is still online and heartbeat fresh.

If any check fails, **never execute an input command**. Android must immediately hide future remote video/input on unsafe transition **before** waiting on network. Host independently drops expired input and releases held buttons. Debug-only synthetic vehicle state can render an explanatory UI but never unlock real OS control.

## 9. Test evidence and acceptance

- Each event family: at least one positive fixture, missing field, invalid enum, unexpected property, incorrect protocol version, out-of-range numeric and replay/out-of-order test.
- `jsonschema` CI verifies syntax+payload shapes; sender/receiver runtime ordering is separate logic.
- Schema presence or a compiled native backend does **NOT** establish safe native input activation. See the Phase 2 handoff for runtime tests and outstanding gates.
- Webcam input must be validated with a local compatible MediaPipe model and camera.
- PLEOS app window + SDK safety must be verified on actual emulator (not inferred from Compose compile).
- Video rendering and embedded client require independent streaming PoC and licensing confirmation.

## 10. Open decisions blocking LIVE_REMOTE

- Actual device validation of the ADR-003 identity/key-storage, approval and expiration implementation.
- Which side supplies and validates vehicle safety evidence without trusting forged network flags.
- Target-device watchdog/TTL behavior under measured network jitter (approved defaults are in ADR-003).
- Windows keyboard Unicode/IME behavior and multi-monitor mapping in a separately safety-approved native test. The UI Events key-name convention is approved.
- WebRTC vs Sunshine/Moonlight on target PLEOS, packaging/distribution/permissions.
- Possible future REST endpoints only after approved ADR and authenticated use case.

**Until those decisions and tests pass: live Windows remote input and video remain disabled.**
