# Input-first DEMO boundary review

Base: dev f8a3374. Approved choices: ADR-004 and ADR-005.
This is source/model review, not platform safety certification or an actual PLEOS test.

- Only stable app-owned targets registered by DesktopWorkspace can receive callbacks. No global tap,
  Accessibility, external app command, Windows input or text network transport is added.
- The interactive workspace is explicitly DEMO and DEBUG-only. Foreground/window focus and the
  unavailable vehicle gateway are separate from transport authentication. OPERATIONAL scope has no
  permit issuer and is denied, even if a caller supplies mock PARKED.
- Validated hints are delivered synchronously after strict JSON/schema/order/session/freshness
  checks. StateFlow conflation cannot erase release events before action dispatch.
- Click/drop completion needs a fresh valid hand frame after release. Zero confidence, loss, timeout,
  disconnect, screen disposal and background cancel pending actions; reconnect requires DEMO opt-in.
- Hit testing includes current overlay priority and explicit parent IDs. A visually covering target
  cannot fall through to an unrelated underlying scroll/context target. Registry metadata is updated
  even when focus/enabled status changes without a layout move.
- Keyboard targets share one touch/gesture callback and do not steal editor focus. Hide/background
  clears actual focus and transient modifier/composition ownership. F1 help and context actions are
  registered in the same coordinate root; unsupported function keys remain disabled.
- The DEMO text toolbar is redirected through supported LocalTextToolbar to app-owned context
  actions, and the phone IME is suppressed only for these editors via InterceptPlatformTextInput.
  Actual selection-handle/popup behavior and PLEOS keyboard rendering require manual verification.
- Clipboard access occurs only on explicit local copy/cut/paste. No clipboard listener, typed-text
  log, telemetry, file persistence or camera recording is introduced. Undo is bounded editor state
  in memory, discarded when the workspace is disposed (not on a temporary background transition).
- Legacy Windows/mTLS security and historical failures are unchanged. Existing uncommitted work in
  another checkout is preserved; no reset, stash, merge or file overwrite is applied there.

Regression/model results and remaining device checks are recorded in PHASE2_REVISED_HANDOFF.md.

## Experimental AIR extension (ADR-006)

The new `/air` v0.2 profile authenticates separately with the local debug credential and schema-validates
complete finger/mouse frames. It never transports text or pixels. Calibrated motion estimates remain
untrusted DEMO hints, not physical contact or production authority. Explicit server arm and local enable,
current session/epoch/sequence, source/receive freshness, foreground and overlay eligibility are required.
Malformed/replayed wire data cancels/disarms; capture samples with old/future/repeated times close the
profile before any fresh reenvelope. A separate 250ms local watchdog releases input on silence.
Finger ownership, same-key reference counts and neutral rearm prevent replayed/returning held input.
F1/context suspends AIR synchronously, including callback changes within the same atomic frame.
Right-hand mouse loss does not reset left-hand text composition. See AIR_TYPING_HANDOFF.md for the
actual automated evidence; no webcam typing/PLEOS/vehicle-safety certification is claimed.
