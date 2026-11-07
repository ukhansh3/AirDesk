# ADR-003 — Phase 2 secure control, emulator-first

Status: accepted choices from the user on 2026-11-07; implementation provided, actual TLS/platform verification incomplete. See PHASE2_HANDOFF for failures and limits.
Base: dev e46aec44c9129efe028e15f07c8bd4c0edcd36a6. No changes to existing JSON Schemas.

## Approved scope

The user approved certificate fingerprint confirmation plus an 8-digit code and both-side approval;
same-PC emulator/loopback first; code TTL 120 seconds, five failures and five-minute cooldown;
session/grant maximum ten minutes with explicit renewal; heartbeat every second with three-second
host watchdog; UI Events code names and committed Unicode text from Android IME.
The implementation may contain a native backend, but no native input may run without separately
verified vehicle evidence, authentication, scoped current grant and explicit PC consent.

## Separate channels and compatible contracts

- Preserve Python ws://127.0.0.1:8765 and the Phase 1 read-only cursor exactly as diagnostics.
- New Windows host uses WSS on 127.0.0.1:8766, /control and /input. Android emulator uses
  wss://10.0.2.2:8766. No public bind, LAN discovery, firewall changes, relay or cloud service.
- A separately routed /mock-input endpoint uses the same canonical input JSON but can dispatch
  only to the Mock Input Backend under typed mock authority. This explicitly labeled diagnostic
  lane permits emulator end-to-end simulation while real safety remains UNKNOWN; it does not
  create a native permit. Pair/session/grant/capability/foreground/heartbeat/replay checks still apply.
  The real /input path remains fail-closed without verified safety. Payloads cannot select a backend.
- Use operating-system TLS via .NET Kestrel/SslStream and existing Android OkHttp. Prefer TLS 1.3;
  permit only TLS 1.2/1.3 for existing Windows/Android runtime compatibility, never old TLS.
  The ASP.NET Core shared framework is an explicit platform dependency, not a new cloud server.
- TLS certificate/key proof is separate from application pairing, session approval and input authority.
- Strictly validate canonical 0.1 JSON messages, size, allowed endpoint/direction, authenticated peer,
  session binding, replay/order and bounded freshness before dispatch. Keep one sender sequence space
  per peer/session across control/input, with serialized validation. Late cross-channel messages drop.
- During an approved session, serialize Android input and control-heartbeat sends with a bounded
  receipt queue: one event in flight, acknowledged by the host's existing session.heartbeat ackSequence
  on /control. Assign sequence/time only at dispatch, coalesce only unsent pointer.move candidates,
  and prioritize heartbeat without reordering button/key transitions. An initial heartbeat acknowledges
  receipt of the grant before input. Queue overflow or ACK timeout closes/revokes rather than replaying
  held input. This prevents independent TCP connections from reordering normal sender traffic without
  weakening the canonical replay checks or adding a schema field.
- A session.request uses a fresh envelope sessionId. At most one pending request exists for that
  peer/session. The host creates requestId, maps it internally to the authenticated request tuple,
  and returns it in session.approve. This does not add a schema property.

## Trust bootstrap and user interface

Before network pairing, exchange PUBLIC connection/device information through the two local UIs.
This is an explicit out-of-band trust enrollment; never accept unknown certificates automatically.
Both users compare full SHA-256 fingerprints, and the host explicitly registers the Android public key.
The eight-digit code is NOT a TLS PSK and is never used to invent an encryption key.

Host public connection information format (local UI export/import, not a WebSocket message):

```json
{"protocol":"airdesk-bootstrap-v1","host":"10.0.2.2","port":8766,"serverCertificate":"BASE64_DER_PUBLIC_CERT","serverFingerprint":"SHA256_HEX","pairingId":"PUBLIC_OFFER_ID","expiresAtMs":0}
```

Android public device information format (local UI export/import):

```json
{"protocol":"airdesk-device-v1","deviceLabel":"Android device","clientCertificate":"BASE64_DER_PUBLIC_CERT","clientFingerprint":"SHA256_HEX"}
```

These exports MUST NOT contain private keys, the eight-digit code, dev tokens or grants.
The public pairingId in the host export resolves the existing claim lookup gap. An expired or
replaced offer requires fresh host connection information. No pairing.offer is sent on the network.
Use UI text import/export and fingerprint confirmation initially; no QR camera permission/library.

Mutual TLS accepts only explicitly enrolled peer certificates, checking validity and key proof.
Reject missing, mismatched and revoked client certificates explicitly; ClientCertificateRequired
alone is not proof that a client certificate was supplied. Per-service trust must not weaken global TLS.
Enrollments are not paired devices: only a valid claim plus HOST-local and VEHICLE-authenticated
approval can set paired=true. No network payload can act as HOST merely by saying side=HOST.

PC keys use Windows current-user CNG/certificate-store protection, never plaintext private-key files.
Android device keys use Android Keystore; private keys never leave it. Keystore hardware backing
must not be assumed on an emulator. Persist revocations; pending codes, approvals, sessions and grants
are memory-only and invalid after restart. Public identity metadata may persist; credentials are not logs.

## Pairing and session rules

- Only the PC user's Connect device action creates a CSPRNG eight-digit code; show it locally only.
- One claimant at a time; five wrong attempts consume the offer and impose a five-minute cooldown.
- Successful claim consumes the code. Both-side approvals have a 60-second pending deadline.
- Client sends pairing.begin, pairing.claim and explicit pairing.approval(side=VEHICLE) over mTLS;
  host UI supplies HOST approval. Host sends pairing.approval(side=HOST) and pairing.completed.
- paired connectionId is a host-side registered identity reference, not a bearer credential.
  Reconnection may reuse a registered device identity but never restores any session or input grant.
- One active input session on the host. Request POINTER/KEYBOARD only in Phase 2.
  VIEW_VIDEO remains reserved and must be refused as STREAM_UNAVAILABLE, not advertised as working.
- Host approval creates a capability-scoped grant no longer than the session's ten-minute lifetime.
  Host monotonic deadlines authorize; UTC expiry in JSON is only the shared UI projection.
- Heartbeats acknowledge validated receive sequence; they never renew consent or expiry.
- Relative monotonic-clock freshness is a bounded channel estimate, not a trusted UTC timestamp.
  Fresh peer/session binding, sequence and host receive/watchdog deadlines are all required.

## Vehicle and native-input boundary

Official PLEOS policy directs standard speed/gear to AAOS Car APIs and UX restrictions to
CarUxRestrictionsManager. Reading documented APIs does not prove AirDesk has the required
platform support/permissions or that Windows can verify a received safety claim.

Default Android gateway and host verifier remain UNKNOWN/unavailable. An authenticated PARKED
string is not vehicle evidence. Missing, stale, unsupported, denied or restricted data locks input.
Production native activation is unavailable until the actual safety verifier is independently validated.
There is no UI/config switch that turns mock safety into native authority.

Mock/simulation is an explicitly labeled, separate backend/policy path. It can demonstrate candidates,
held-state and cleanup without native calls. Mock authority must be unable to construct a native permit.
Real safety lock and foreground/revoke handling run locally before any network notification.
Host watchdog and serialized dispatch independently release held keys/buttons on loss or expiry.
Cleanup of owned held state remains possible after grant expiry; release is not blocked by revoked consent.

## Input conventions

- whitelist W3C KeyboardEvent.code for physical keys (Enter, Backspace, Delete, Arrow*, ShiftLeft/Right,
  ControlLeft/Right, AltLeft/Right). Unknown codes reject, not guessed Windows virtual keys.
- modifiers describes the intended modifier set; do not silently synthesize arbitrary extra privileges.
- keyboard.text contains IME-committed Unicode, not composition updates or recorded user typing.
  Validate code-point length and invalid surrogates; native Unicode packet behavior is app-dependent.
- User selects one Windows display. Map normalized coordinates to physical pixels with its bounds,
  account for negative virtual-desktop origins and Per-Monitor DPI, and revoke/release on topology change.
- No elevation, secure-desktop/UIPI bypass, global input hooks, clipboard capture or input logging.

## Required evidence and references

Baseline Python tests: 50 passed, no skips, isolated loopback; Phase 2 tests not yet implemented at ADR creation.
User reported webcam/Android cursor success; latest request also reports PLEOS success. Existing captured
device name was Medium Phone API37. Specific PLEOS SDK/permissions/safety and measured FPS/latency remain unverified.

- [TLS 1.3](https://www.rfc-editor.org/rfc/rfc8446.html)
- [Client certificate request is not sufficient](https://learn.microsoft.com/en-us/dotnet/api/system.net.security.sslserverauthenticationoptions.clientcertificaterequired?view=net-10.0)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [UI Events code](https://www.w3.org/TR/uievents-code/)
- [Windows KEYBDINPUT](https://learn.microsoft.com/en-us/windows/win32/api/winuser/ns-winuser-keybdinput)
- [PLEOS SDK API policy](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/pleos-connect-sdk-api)
- [PLEOS UX restrictions](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide)
- [PLEOS permissions](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/)
