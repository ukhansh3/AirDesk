# Integration harness
Fixtures are synthetic only; no real biometric footage or device sessions.
Add hardware E2E evidence under a separate local ignored directory with no personal content; summarize sanitized results in docs/testing.
Run PowerShell scripts/verify.ps1 or gesture-engine tests first.

## Phase 2 harness

Run `scripts/verify-phase2.ps1` from PowerShell for Phase 1 regression, shared wire
conformance, Windows mock/security tests and WPF build, then Android JVM tests and
assembleDebug. It never launches an emulator, GUI, real camera or native input.
Use `-PythonExe` to supply an existing environment; `PYTHONPATH` is pinned to the
current checkout. `-SkipWindows`/`-SkipAndroid` produce NOT_RUN and a non-success
overall result. Sanitized reports go to ignored `out/phase2-verification/`.

`fixtures/phase2-wire-cases.json` is a synthetic cross-platform catalog. A valid
shape is not an authenticated command: forged HOST approval, unknown physical key,
VIEW_VIDEO and possession of a grant ID still need runtime rejection. The Python
fixture harness tests strict JSON/schema conformance only; Windows/Android runtime
tests must prove authorization, session, safety, watchdog and Mock/native isolation.
No certificates, private keys, real codes, tokens or keyboard records are fixture artifacts.

See [Phase 2 handoff](../docs/testing/PHASE2_HANDOFF.md) and
[approved architecture](../docs/decisions/ADR-003-PHASE2-SECURE-CONTROL.md).
