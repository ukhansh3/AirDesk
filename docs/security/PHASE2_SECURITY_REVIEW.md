# Phase 2 security review and implementation checklist

Date: 2026-11-07. Base dev: e46aec44c9129efe028e15f07c8bd4c0edcd36a6.
This is a review checklist, not an independent security certification or proof of live input safety.
User decisions are recorded in ADR-003. No shared schema migration is approved or required by this design.

## Four-agent initial review

Windows: only a locked WPF shell existed; no peer identity, pairing/session manager, backend or watchdog.
Android: Phase 1 diagnostics existed; RemoteGrant booleans were not production authority and no live
vehicle SDK adapter or session transport existed. Security: the schemas were proposals, not an
authentication design. QA: 50 Phase 1 tests passed without actual camera, GUI, emulator or user-server access.

The existing session/keyboard schemas require arrays, booleans and Unicode code-point length checks
that the intentionally limited Phase 1 diagnostic schema evaluator cannot supply. A Phase 2 validator
must support those canonical shapes without silently accepting unsupported keywords or new fields.

## Identity and bootstrap

- Public certificate/fingerprint import is an explicit local user action on each device.
- Fingerprints are SHA-256 over full certificate DER, uppercase hex. Compare the computed value with
  the imported claim and show it for comparison with the other device. Never silently trust first use.
- The exported host connection descriptor contains public pairingId, address, port, certificate and expiry.
  It never contains a pairing code, private key, dev token or grant. Existing pairing.offer stays host-local.
- Device registration authorizes only a TLS identity to attempt pairing. paired=true requires the valid
  one-time code and both local PC and authenticated Android approvals.
- Missing certificates, unregistered/revoked identities, bad pins and invalid validity periods reject.
- Windows ClientCertificateRequired is not alone sufficient; check the supplied peer explicitly.
- Exact peer trust is scoped to this service. Do not replace global trust/hostname checks or allow all certs.
- Persist only app-owned protected keys and public enrollment/revocation metadata. No production keys,
  certificates with private material, real pairing codes, local.properties or session input logs enter Git.

## Native authority versus mock authority

The authenticated sender, current session, grant/capability, PC approval and vehicle evidence are
independent facts. An ID or valid JSON is not authorization. A network side=HOST or PARKED value is
never a trusted local decision. Mock authority is distinct and cannot construct a native permit.

The /mock-input route is a deliberately labeled emulator simulation and uses only a mock backend.
It may demonstrate clicks, held-state and cleanup while production safety stays UNKNOWN.
The /input route requires the real verifier. A missing platform verifier leaves it disabled even if
pairing, mutual TLS and host session approval work. Native API calls must remain zero in all harness runs.

Authorization and dispatch must be serialized: queued down events cannot execute after revocation.
Watchdog cleanup must run without waiting for network/UI. Release previously owned held state even
after consent/grant expiry, but never let another peer release or acquire that state.

## Vehicle evidence investigation

PLEOS officially recommends AAOS Car APIs for standard speed and gear data. Its UX guide documents
CarUxRestrictionsManager registration/callbacks. Vehicle SDK permissions must be specifically approved
for the app; public documentation is not evidence that this app has runtime permission or that the
emulator represents trusted physical vehicle state. A speed of zero or unrestricted UX alone is not
verified parked state. Host verification of an Android network claim remains a separate trust boundary.

Do not substitute cloud trip history, mock PARKED, an arbitrary Boolean or caller-provided timestamps.
Keep SDK-unavailable/permission failure/stale/unsupported states fail-closed. Do not claim PLEOS safety
verification from a normal phone emulator or from a successful Android build.

## Timing and replay

Use host monotonic deadlines for consent, code attempts, grants and watchdogs. JSON UTC expiries are
display projections. Producer timestampMs is monotonic on a different clock, not UTC authorization.
Relative freshness calibration is only an estimate; combine new session binding, strict sequence,
host receive deadlines and non-renewing heartbeat leases. Commit sequence only after valid binding
and freshness checks. Reconnect never restores a grant or held state.

## Verification required before completion

- Bad/expired/reused/cancelled codes and concurrent claimants; five-attempt cooldown boundaries.
- Forged host approval and wrong peer/session/grant/capability; unpaired and revoked device.
- TLS absent/bad/unknown/revoked client cert and wrong host pin; private keys absent from exports.
- Duplicate/out-of-order/stale/future packets cannot poison later valid sequence state.
- Grant/session/watchdog expiry with fake monotonic clock; no consent renewal by heartbeat.
- Revocation during dispatch, background, tracking loss, network/server shutdown, backend partial failure.
- Unicode length/invalid surrogate handling; unknown key codes and modifier cleanup without text logging.
- Mixed DPI, negative display origins, topology change and out-of-bounds coordinate mapping.
- Positive mock network path plus UNKNOWN-denied native path; no test invokes actual SendInput.
- Preserve Phase 1 regression; separate automated, user-reported and not-run evidence.

Actual results and remaining limitations belong in docs/testing/PHASE2_HANDOFF.md after implementation.

## Final review disposition

The implementation was re-reviewed after changes for strict JSON/null handling, transactional replay
state, ACK progress, ordered input receipts, immediate lifecycle/topology locking, revoke persistence
failure cleanup and native/mock type isolation. No native permit issuer or payload/config activation
path was found. This source review is not runtime certification.

The final policy harness passed 22 cases; actual loopback TLS failed before application protocol with
SEC_E_NO_CREDENTIALS, including a minimal public-certificate-only Python client/.NET server control.
Negative TLS assertions are not credited without a positive handshake. Android 39 JVM tests (final XML total) and both
builds passed; actual Android Keystore, production Host CNG enrollment, PLEOS SDK and OS input remain
unverified. The overall aggregate is FAILED, not a production release approval. Known late socket
cleanup, failed revocation persistence, non-US physical key mapping and live evidence limitations are
listed in the handoff. No security setting was weakened to turn the failed TLS test green.

## Primary references

- [TLS 1.3](https://www.rfc-editor.org/rfc/rfc8446.html)
- [Microsoft client-certificate requirement](https://learn.microsoft.com/en-us/dotnet/api/system.net.security.sslserverauthenticationoptions.clientcertificaterequired?view=net-10.0)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [Windows data protection](https://learn.microsoft.com/en-us/dotnet/standard/security/how-to-use-data-protection)
- [Physical key code names](https://www.w3.org/TR/uievents-code/)
- [Unicode Windows input](https://learn.microsoft.com/en-us/windows/win32/api/winuser/ns-winuser-keybdinput)
- [PLEOS API policy](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/pleos-connect-sdk-api)
- [PLEOS driving restrictions](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide)
- [PLEOS permission policy](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/)
