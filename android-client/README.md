# AirDesk Android client (Kotlin + Jetpack Compose)

Phase 1 remains a **read-only diagnostic cursor preview**. Phase 2 adds explicit certificate
enrollment, pairing, host-approved sessions and a separately labeled Windows Mock Backend test.
Vehicle safety defaults UNKNOWN; actual Windows input remains disabled. No screen streaming.

On Windows:
- Open this directory in Android Studio. Gradle plugin/dependency versions are based on a Pleos Stay+ development baseline; verify local installation.
- Gradle wrapper jar/scripts are NOT bundled; use Android Studio/installed Gradle to generate a checked wrapper before relying on command-line builds.
- Create a local ignored android-client/local.properties with your Android SDK path and AIRDESK_DEV_TOKEN generated for local debugging. `AIRDESK_WS_URL` optionally overrides the debug-only default `ws://10.0.2.2:8765`. Never publish tokens.
- Start Python diagnostic WS on PC loopback. In standard Android emulator, host loopback is often 10.0.2.2. Verify actual PLEOS Emulator 2.1.0 network behavior.
- Install debug app, tap Connect Diagnostic, and watch synthetic cursor. It will not inject Windows input.
- The client rejects malformed, cross-session, duplicate and out-of-order messages; hides the cursor on tracking timeout/disconnect; and retries a failed debug connection with bounded exponential backoff.
- Source manifest release does NOT allow cleartext. Debug-only manifest opts in for local diagnostics.
- The true safety SDK and remote-screen renderer must be verified separately before enabling production Work Mode.

Do not run the debug URL outside your test environment or distribute debug APKs with dev credentials.

## Phase 2 manual workflow

1. Open the Phase 2 tab. Explicitly generate/read the Android device public information.
   Copy its public JSON into the Windows Host enrollment UI and compare the full SHA-256
   fingerprint on both screens. The nonexportable Android Keystore private key stays on-device.
2. In the Host UI, start pairing. Paste its public bootstrap JSON into Android, inspect the full
   certificate fingerprint, compare it with the PC display, then explicitly confirm and register it.
   Only the same-PC emulator endpoint `wss://10.0.2.2:8766` is supported in this phase.
3. Choose secure connection, submit the Host's current eight-digit code, and approve the displayed
   device on both sides. Pairing does not grant input authority.
4. Request POINTER/KEYBOARD and approve the session in the PC UI. Real control stays locked.
   Explicitly enable **모의 입력 테스트 · 실제 PC 제어 잠금** to send candidates to `/mock-input` only.
5. Test touch movement, tap, long-press drag, two-finger scrolling, committed Korean/English text
   and the special keys. Modifier checkboxes hold Shift/Ctrl/Alt until unchecked or released.
   Enable the separate gesture checkbox and connect Phase 1 diagnostics to test gesture candidates.
   Watch the Windows Mock Backend counters; an Android enqueue count is not proof of host execution.
6. Release all, stop the session, switch tabs or background the app. Input state and authority are
   cleared; reentering, reconnecting or returning to a parked state never restores grants or gestures.
   Disabling mock mode ends its session and requires fresh PC approval.

The client serializes granted-session heartbeat/input sends through a bounded receipt barrier.
Only an authenticated Host heartbeat acknowledging the dispatched sequence opens the next send;
unsent moves coalesce, button/key transitions remain ordered, and queue/send failures lock and close.
The initial heartbeat acknowledges receipt of a new grant before any candidate can be dispatched.

No vehicle SDK permissions were added. A standard phone emulator does not verify PLEOS vehicle
gear/speed/UX restrictions. Android-local resumed lifecycle locking, revocation and Host
watchdog protect mock tests; the Host has no independent cryptographic foreground/safety proof.
Keystore creation, Android↔Windows mTLS interoperability and actual PLEOS UI behavior require manual
device testing; JVM tests and `assembleDebug` alone do not establish those results.

