# Release blocking checklist

- [ ] PLEOS SDK capability/permission and parked UX policy verified on target emulator.
- [ ] Encrypted peer-authenticated control transport.
- [ ] PC local user actively approves each session.
- [ ] No remotely accessible debug WS and no hardcoded secrets.
- [ ] Host-side rate limiting, sequence, expiry and key-release watchdog.
- [ ] UNKNOWN/moving/UX restricted instantly blanks remote screen and blocks inputs.
- [ ] Signed binaries and installed dependencies reviewed for license/re-distribution.
- [ ] Camera footage and keystrokes never persisted by default.
- [ ] Pairing revocation, network reconnect and session expiry threat tests pass.
- [ ] A third-party stream launch is not represented as embedded app support.
