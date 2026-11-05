# AirDesk Python ↔ Windows Host IPC specification — DRAFT

## 1. Ownership

Windows Host owns the local child-process lifecycle; Python Gesture Engine owns capture/MediaPipe model and landmark detection. Gesture Engine **does not call SendInput**. Windows host must NOT treat raw gesture messages as authenticated remote PC inputs.

## 2. Candidate transport

Initial recommended: local process with a length-limited newline-delimited JSON (NDJSON) stdout stream for gesture events, stderr for sanitized diagnostics; stdin/control messages for shutdown. Alternative named pipes require separate ADR. This IPC is **not implemented** in the WPF prototype yet.

Only one active capture process per host instance; no auto camera activation at startup. Device selection and starting webcam require local user action and visible indicator.

## 3. Events

Use the same canonical gesture schema; Windows Host must validate type, sessionId, strict sequence, timestamp and payload. Additional IPC control messages (camera.ready, camera.failed, camera.stopped) require a separately versioned schema before being implemented.

Limits: max message size 8 KiB, bounded queue 4; coalesce pointer movement rather than blocking; do not coalesce press/end/lost state. Reject malformed UTF-8/JSON and non-finite numbers. On stdin/pipe close: stop capture and emit tracking.lost if needed.

## 4. Startup/stop state machine

STOPPED → STARTING → READY → STOPPING → STOPPED; failures → FAILED → STOPPED.
A failed model load or webcam busy must not leave a zombie process or a held drag. Child process must be terminated after bounded graceful timeout; do not kill unrelated PIDs.
On host app closing, stop the capture process and free the camera. On Windows crash/restart do not auto-enable capture without fresh local consent.

## 5. Authorization and privacy

Only synthetic data/landmark-derived hints to the car. Do not stream raw frames through IPC or log hand image, user biometrics, credentials, or text input. Host must bind the IPC producer identity to locally started process, not an unauthenticated TCP listener.

## 6. Required tests

Start/stop, missing model, webcam missing, malformed JSON, oversized frame, drag-then-process-crash, stdout flooding, stale sequence, host app shutdown, process path injection and no unexpected auto-restart.
