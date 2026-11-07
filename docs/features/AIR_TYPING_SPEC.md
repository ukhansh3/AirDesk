# Experimental two-hand ten-finger AIR typing

Status: approved implementation design, 2026-11-07. Actual webcam typing accuracy is NOT_VERIFIED.
The user explicitly requested implementation after the calibrated-flex proposal and clarified the
camera view and full-keyboard layout. This spec supersedes the draft proposal's pending status.

## User interaction

Hold both hands forward in midair, palms/fingers loosely curved downward, like preparing to type.
The camera views them from slightly above the front. The app shows the entire PC keyboard and mouse
area, all visible fingertips and recognized down/up feedback; input text stays in the local editor.
The current touch/single-Pinch profile remains available separately.

## Guided calibration and recognition

- Press **자동 보정 시작** once. Allow five seconds to return both hands, then hold the comfortable
  neutral pose. Capture requires at least six stable both-hand samples spanning 500ms. The guide
  automatically prompts LEFT and RIGHT THUMB, INDEX, MIDDLE, RING, LITTLE in order; no per-stage clicks.
- Between poses, return to neutral for 700ms, then perform only the indicated finger stroke and hold
  briefly. Separable/stable motion advances the stage automatically. Missing/coupled/unstable hands
  reset the sampling window, not the completed stages; retry by returning to neutral. Idle time never
  substitutes for a valid pose. Cancel disarms; completion does not automatically enable typing.
- Calibrate a vector of chain folding and signed knuckle flex relative to the palm. The learned
  neutral-to-stroke direction recognizes MCP-only depression even when finger segments remain straight;
  whole-hand translation/rigid rotation/scale must not create a press. Thumb features remain separate.
  Down/Up hysteresis uses 55%/25% of the calibrated motion with 60ms Down/50ms Up confirmation.
  This estimates motion, not contact/pressure or a metric keyboard plane.
- Map camera image x/y to the complete normalized keyboard/mouse design surface (1120x360).
  Show hovered keys before enabling typing. This is an image-space mapping, not calibrated physical depth.
- Camera joint markers and AIR hover XY use the same stabilization profile after their raw validity/
  identity checks: median of three samples, time-aware EMA with 60ms time constant, normalized radial
  soft deadzone .004. Small stationary jitter is suppressed, while cumulative intentional motion still
  moves the point. A single-frame spike is rejected; loss/invalidity clears history immediately.
  Filters have separate lifecycle histories but the same settings. Raw CameraSample landmarks,
  calibration features/noise, Down/Up thresholds and the existing mouse path stay unchanged.
  Captured key XY remains anchored until release. The filter adds response lag; actual webcam
  stability/latency must be checked separately. A stable-looking dot cannot force calibration to pass.
- Require explicit arm plus local DEMO enable. Each finger must rearm in neutral, then produce one
  down edge after calibrated hysteresis/duration. Bind the pre-stroke key until release. No hold-repeat.
- Neutral rearm is independent per finger (200ms); a held pinky cannot block the same hand's index.
  The app displays local enable/guard status, raw detected presses, accepted keys and per-finger
  neutral/hover/down/outside/unsupported states. Server ARMED alone must not claim local typing is ON.
- Reject ambiguous identity and crossing/unstable observations. Hand-result array order is not identity.
  Missing fingers release their ownership. Reappearance flexed does not create a new down edge.

## Local editor semantics

Own each held key by hand/finger. Two fingers on the same key reference-count its hold and generate
one down activation. Simultaneous modifiers go down before character activations and up afterward,
with deterministic hand/finger order. AIR Shift/Ctrl/Alt are held modifiers; existing touch latches
retain their own semantics. No focus means no character. Focus change cancels and requires neutral rearm.
No text or key-event log, Windows input, Accessibility or global Android dispatch.

## Side mouse

The right-side mouse rectangle is x972..1112/y44..352 of the same design. Moving the right wrist into
the region releases right-hand typing ownership and waits for neutral dwell/rearm. Reuse index motion
and Pinch click/drag, with explicit right-click-once and scroll modes. The same hand cannot type and
operate the mouse simultaneously. Returning to typing requires neutral rearm. Left-hand loss or motion
does not transfer right-hand ownership. Input cancels on tracking/connection loss, stale data or background.

## Versioned diagnostic transport

- Keep existing v0.1 schemas/route/fixtures intact. Add `/air` on the same loopback-only server, v0.2
  `auth.hello`/`auth.accepted` with explicit `air_typing_demo` mode; never silently fall back to v0.1.
- Use the existing local debug credential only for the isolated DEMO. This is not production authentication.
- `contracts/air/air-messages.schema.json` is the separately reviewed new profile. Envelope is strictly
  version/type/session/sequence/timestamp/payload; reject duplicate JSON, unknown fields and non-finite values.
- `air.command` actions: start_calibration, capture, arm, disarm, reset. Bind commands to the authenticated
  server session, increasing client sequence and fresh clock-relative timestamps; calibrating disarms first.
- `start_calibration` now starts the automatic guide. Existing `capture` remains an explicit manual
  fallback with the original 1.5-second delay; it cancels automatic progression. Wire schemas are unchanged.
- `air.frame` is an atomic complete state: calibration epoch/stage/status/armed, at most ten unique
  hand/finger observations, plus mouse state. Omitted/invalid fingers release. Text is never transported.
- Source sequence is global per connection. Reject replay, mixed versions/sessions and stale/regressing
  timestamps. New calibration epoch discards previous held state; reconnect never restores arm/consent.
- App delivers accepted frames synchronously to the controller, not via conflating StateFlow alone.
  A separate local stale watchdog cancels input even when network callbacks stop arriving.

## Scope and verification

For development, the user chose a **PC large-keyboard reference window**. The existing PC panel exposes
**큰 키보드 창**, which the user opens/resizes before connecting the app. It renders the same 1120x360
key/mouse design, ten fingertips and estimated Down states from a one-latest-state in-memory callback.
It receives no typed text, camera video or Android input authority. Its estimated highlights are not
proof that the app accepted a key. Disconnection or a local stale watchdog clears the display. The
Android editor remains the input/result surface and must stay foreground. No Windows keyboard/mouse
injection is added. The actual large-window readability and emulator host-focus behavior need user tests.

Only explicitly labeled DEBUG DEMO can run while the official vehicle gateway is UNKNOWN. Operational
Work stays blocked. No vehicle camera/permission or new external library is selected. PC webcam frames
stay local and calibration is in memory, not a recording or permanent biometric profile.

Automate calibration separation/failure, two hands, finger identity/order, loss/rearm, repeated/stale
frames, same-key references, simultaneous modifiers, keyboard/mouse transitions, strict v0.2 validation,
auth rejection and v0.1 regressions. Hardware acceptance separately measures each finger's missed/false
presses, simultaneous-key behavior, typing accuracy/latency, camera angle, occlusion and fatigue.
Ordinary monocular webcams may not reliably distinguish small ring/pinky/thumb movements.
