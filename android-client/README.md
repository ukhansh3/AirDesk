# AirDesk PLEOS diagnostic client (Kotlin + Jetpack Compose)

This is a **read-only diagnostic cursor preview**, not a working remote desktop.
Vehicle safety defaults UNKNOWN, remote input is ALWAYS DISABLED.

On Windows:
- Open this directory in Android Studio. Gradle plugin/dependency versions are based on a Pleos Stay+ development baseline; verify local installation.
- Gradle wrapper jar/scripts are NOT bundled; use Android Studio/installed Gradle to generate a checked wrapper before relying on command-line builds.
- Create a local ignored android-client/local.properties with your Android SDK path and AIRDESK_DEV_TOKEN generated for local debugging. Never publish tokens.
- Start Python diagnostic WS on PC loopback. In standard Android emulator, host loopback is often 10.0.2.2. Verify actual PLEOS Emulator 2.1.0 network behavior.
- Install debug app, tap Connect Diagnostic, and watch synthetic cursor. It will not inject Windows input.
- Source manifest release does NOT allow cleartext. Debug-only manifest opts in for local diagnostics.
- The true safety SDK and remote-screen renderer must be verified separately before enabling production Work Mode.

Do not run the debug URL outside your test environment or distribute debug APKs with dev credentials.
