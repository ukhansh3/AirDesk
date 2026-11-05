# AirDesk Threat model v0.1 — security review REQUIRED before real remote input

## 1. Assets and data boundaries

Assets: Windows screen contents, OS input authority, webcam frames, pairing/session credentials, vehicle motion/restriction signals.
Boundaries: (A) camera ↔ Python process; (B) Python ↔ Windows Host IPC; (C) Windows ↔ vehicle network; (D) PLEOS platform ↔ Android SafetyGateway; (E) future video transport.
No raw camera frames or keystroke logs stored by default.

## 2. Threats and mandatory mitigations

| Threat | Likely failure | Mandatory mitigation | Test |
| --- | --- | --- | --- |
| Unauthorized client | attacker controls PC over exposed socket | Peer-authenticated encryption, PC user approval; no public debug listener | reject unauthenticated input |
| Pairing brute-force or code disclosure | 8-digit code guessed, replayed, or leaked to pre-auth client | bounded attempts, short expiry, fresh cryptographic randomness, **display only on trusted local host UI**, explicit both-side approval, revoke | unauthenticated peer cannot read code; repeated wrong code blocked |
| Replay/out-of-order | repeated pointer-down / keyboard-down | per-peer session sequence, freshness window, deduplicate and safe release | replay never causes input |
| Vehicle state forged | network payload claims parked | only approved **local PLEOS SDK** defines parked state; never accept peer claim | fake parked payload rejected |
| Safety change with lost network | remote keyboard remains held | local immediate lock and independent Windows host watchdog release | disconnect during drag |
| Token leak | compromised log/screenshot reveals grant | redact log, secrets outside Git, protect client storage, short TTL | logs and repo scans |
| Video disclosure | frames cached/recorded elsewhere | encrypted stream, session scope, no recording by default, immediate blank | revoke during stream |
| Python process compromise | malformed landmarks/IPC flood | process permissions, size & rate caps, no direct SendInput, sanitization | fuzz invalid/huge event |
| UAC/privilege escalation | injected keys to admin/secure desktop | no elevation, UIPI respected, no accessibility abuse | secure desktop ignored |
| Driver distraction | remote screen during driving | verified PLEOS UX restrictions, UNKNOWN locks, lifecycle lock | parked→moving transition |

## 3. Key policy

Diagnostic env token is explicitly not a cryptographic device credential. Never embed production token in APK or assume a one-time code grants persistent authorization. Grant ID identifies a session-scoped permission; verification is a **server/host state lookup**, not possession alone.

## 4. Release blocking decisions

- Approved vehicle state API/method, permission, age threshold and SDK lifecycle.
- Pairing cryptography and secure storage, lockout, revocation on every endpoint.
- Video protocol/decoder package license and PLEOS platform policy.
- Windows restricted-input scope and explicit user consent UX.
- Safe discovery/NAT/relay design if remote internet support is requested.
- Privacy notice for webcam processing and scope of telemetry retention.

## 5. Review gate

Fail if any critical security test cannot be run or fails. The only acceptable Phase-0 demo is **read-only gesture pointer visualization**; not a functioning remote desktop.
