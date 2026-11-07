# AirDesk Windows Host — Phase 2

Separate WPF host, policy core and headless test console. Phase 1's Python camera
and diagnostic WebSocket are unchanged. This host never starts them or an emulator.

## Start and pair

Build/run `AirDesk.Host/AirDesk.Host.csproj` with .NET 10 Windows Desktop and
ASP.NET Core shared runtimes. The repository root launcher opens this separate host.
The user, not an agent/test, launches the WPF UI.

1. Click **보안 Host 시작**. Only this user action opens/creates the PC's non-exportable
   current-user CNG key and stores the server certificate in CurrentUser/My. No private
   key is exported. The certificate covers 127.0.0.1, 10.0.2.2 and localhost.
2. Copy Android's **public device information** into the host. Compare the full SHA-256
   certificate fingerprint on both local screens and enter the confirmed fingerprint.
   Registering a public key does not pair a device or grant input.
3. Explicitly create an eight-digit code. It is shown only in the PC UI, expires after
   120 seconds, and is absent from the public bootstrap JSON and all network replies.
   Copy the host's public connection information into Android and compare fingerprints.
4. Enter the code on Android, explicitly approve there, and approve the named device
   and full fingerprint on the PC. Five wrong attempts consume the offer and impose a
   five-minute cooldown. Both approvals are required and expire after 60 seconds.
5. Android requests a fresh session. The PC user approves the exact device and requested
   POINTER/KEYBOARD scopes. VIEW_VIDEO is reserved and refused with STREAM_UNAVAILABLE.
6. Select **모의 입력 테스트** in Android to exercise `/mock-input`. The host shows only
   counts and held-state, never keyboard strings or a keystroke history. `/input` is the
   separate native role and stays blocked because official vehicle evidence is UNKNOWN.

The new mTLS listener is 127.0.0.1:8766 only. Endpoints are `/control`, `/input`, and
the explicitly labeled `/mock-input` diagnostic lane. There is at most one socket per
role and one active input session. No LAN bind, firewall rule, tunnel or cloud service
is configured. All replies and input receipt barriers use the ordered control socket.

Public device/certificate/revocation metadata is saved under the user's local app-data
AirDesk/Phase2 directory. Pairing codes, approvals, sessions and grants are memory-only.
Restart never resumes an input grant. A revoked certificate cannot re-enroll silently.

## Safety and cleanup

- Grants expire within ten minutes, never implicitly renewed by heartbeat. Initial
  input requires acknowledgement of the grant. Fresh heartbeats occur each second;
  three seconds without valid receive/ack progress independently revokes/releases.
- Invalid/replayed cross-channel input while a button/key is held fails closed:
  release and revoke rather than allowing a dropped key-up to stick.
- Mock and live authority have different types. No issuer for `LiveInputPermit` exists;
  NativeInputBackend construction is unavailable. Neither UI, config nor mock PARKED
  state can unlock it. Native attempted-call counters remain observable.
- Native mapping/SendInput code is present but was never executed in tests. Its future
  activation still requires separately verified vehicle evidence and renewed security
  review. UIPI, secure desktop and ordinary-user restrictions are not bypassed.
- Monitor bounds are physical pixels under Per-Monitor-V2 DPI awareness, including
  negative virtual origins. The logical input displayId `primary` means the single
  user-selected host monitor. Changing monitor/topology revokes the session.
- Backend release failures retain ownership and attempt the remaining releases, so
  later watchdog cleanup can retry instead of falsely reporting all keys released.

## Headless tests

```powershell
$env:DOTNET_GENERATE_ASPNET_CERTIFICATE = 'false'
dotnet build windows-host/AirDesk.Host/AirDesk.Host.csproj --configuration Release
dotnet run --project windows-host/AirDesk.Host.Tests/AirDesk.Host.Tests.csproj --configuration Release
```

The console harness exits nonzero on failures and emits
`AIRDESK_HOST_TESTS {"passed":N,"failed":N,"nativeCalls":0}`.
It covers canonical schemas, pairing, expiry/replay, grants, scoped mock input,
watchdog/ack progress, cleanup, native denial, coordinate mapping, and actual loopback
mTLS positive/negative cases with synthetic certificates. It never launches WPF,
an emulator, a camera or Windows input.

Windows Schannel cannot use ephemeral private-key handles for TLS. Only the synthetic
TLS tests therefore round-trip encrypted PKCS#12 **in memory** into disposable
OS-protected UserKeySet containers (no PersistKeySet, no certificate-store trust).
Certificate disposal cleans those temporary keys. Non-TLS policy fixtures stay purely
ephemeral. A restricted-token sandbox may deny the protected temporary-key operation;
that is a failed/blocked test, not a TLS success. Negative TLS assertions are conditioned
on a successful positive handshake, avoiding false security evidence.
`--ephemeral-test-keys` reproduces the platform limitation, and `--tls-diagnostic`
performs a separate no-client-certificate HTTPS HEAD control. Neither is a workaround
that weakens authentication. The test runner must not print key bytes, codes or input text.

See ADR-003 and docs/testing/PHASE2_HANDOFF.md for the approved design and evidence.
