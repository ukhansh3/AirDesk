# Integration harness
Fixtures are synthetic only; no real biometric footage or device sessions.
Add hardware E2E evidence under a separate local ignored directory with no personal content; summarize sanitized results in docs/testing.
Run PowerShell scripts/verify.ps1 or gesture-engine tests first.

## Current revised Phase 2: desktop-style app-owned input

Run `scripts/verify-input-first.ps1` for the ADR-004 path: Phase 1 Python
regression, unchanged canonical wire fixtures, Android JVM tests and assembleDebug.
The script obtains test totals directly from JUnit XML; incomplete/skipped checks
do not become an overall pass. Reports go to ignored `out/input-first-verification/`.
Use `-PythonExe`, `-GradleExe`, `-JavaHome`, `-AndroidSdk` and `-GradleUserHome`
for existing local tooling. It does not launch a camera, emulator, WPF host or OS input.

Acceptance is the app's PC-style full keyboard and registered mouse targets, including
focus, composition/selection, one click per release, drag cancellation, context actions,
bounded scroll and lifecycle safety. No global input or new wire schema is implied.
Actual PLEOS/IME/ergonomics tests remain manual. See the
[revised handoff](../docs/testing/PHASE2_REVISED_HANDOFF.md).

## Historical optional remote-Host Phase 2 harness

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
