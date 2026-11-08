# Experimental two-hand ten-finger AIR typing

Status: existing v0.2 implementation plus implemented/automatically verified PC camera reference, 2026-11-08.
Actual webcam typing accuracy and fixed-world spatial sensing are NOT_VERIFIED.
The user explicitly requested implementation after the calibrated-flex proposal and clarified the
camera view and full-keyboard layout. This spec supersedes the draft proposal's pending status.

## User interaction

Hold both hands forward in midair, palms/fingers loosely curved downward, like preparing to type.
The camera views them from slightly above the front. The app shows the entire PC keyboard and mouse
area, all visible fingertips and recognized down/up feedback; input text stays in the local editor.
The current touch/single-Pinch profile remains available separately.

### Live PC camera reference (user-approved display update)

The single-RGB-webcam research approach is approved first; depth/tracking hardware is only a future
fallback if measured results are insufficient. The current display step composites a full green PC
keyboard and side mouse outline on the live PC camera preview using the existing normalized v0.2
image-space geometry. All 26 letter keys show English/two-set Hangul labels. Only line/text pixels
have opacity (default 40%; PC adjustment 15–80%); key/background interiors stay transparent. The PC
checkbox defaults ON and the opacity control adjusts only the display layer.

Compositing happens after MediaPipe input and is local to the PC preview. It does not alter raw
landmarks, calibration/press thresholds, packets or Android editor behavior. No frame is saved or
transmitted. Pillow `>=10,<14` in the camera/all extras and the local Windows Malgun font provide
Unicode text/RGBA composition; see [ADR-007](../decisions/ADR-007-SINGLE-WEBCAM-KEYBOARD-OVERLAY.md).
No font file is bundled. Turning the layer off does not turn tracking or typing on/off.
The Run-AirDesk panel fits the complete image within its actual preview bounds (maximum 860×480)
and shows quality/guard captions outside the image. Standalone OpenCV `--preview` is unchanged.
After fixing a missing renderer/font, toggle OFF/ON to retry; opacity-only changes do not retry failure.

This image overlay is not a free-space hologram or the future fixed-world-plane classifier. The
existing absolute image-to-key mapping and flex/MCP press model remain until a separately tested
spatial input implementation is approved and delivered. See
[SPATIAL_AIR_KEYBOARD_DESIGN.md](SPATIAL_AIR_KEYBOARD_DESIGN.md).

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

### Same-connection calibration pause (2026-11-08)

A short capture/inference or transport delay must cancel input without restarting completed calibration
poses. Keep the 250ms input freshness bound. Within the same authenticated connection and source epoch,
a strictly increasing, nonfuture camera timestamp older than 250ms is discarded; the server publishes
an empty, unarmed state preserving stage/epoch/completed models. A pending camera read uses the same
pause. Discard the unfinished sampling window and require fresh neutral before continuing that stage.
This is an internal engine operation, not a new wire command or relaxed press threshold.

The client may keep automatic-calibration intent and display-only progress during temporary silence
only after an explicit start has been confirmed for that session/epoch and input is unarmed. Clear
all local keys/mouse and remove the old frame immediately. Never redisplay stale fingertip/press data.
Fully validated duplicate wire frames are dropped without delivery or watchdog renewal; a stale valid
new frame releases input. Malformed/session-invalid data cancels calibration and disarms. Armed or
pending-arm input loss revokes consent and requires explicit re-enable; recovery never issues arm.

Invalid/future/repeated/regressing raw capture clocks still terminate the source connection. Actual
disconnect, authentication loss, replacement epoch, camera change or explicit cancel/reset must not
restore calibration automatically. Reconnection reports that fresh calibration is required. No disk
profile/cache or cross-session restoration is introduced. Hardware comfort and delay remain manual gates.

## Scope and verification

For development, the user chose a **PC large-keyboard reference window**. The existing PC panel exposes
**큰 키보드 창**, which the user opens/resizes before connecting the app. It renders the same 1120x360
key/mouse design, ten fingertips and estimated Down states from a one-latest-state in-memory callback.
It receives no typed text, camera video or Android input authority. Its estimated highlights are not
proof that the app accepted a key. Disconnection or a local stale watchdog clears the display. The
Android editor remains the input/result surface and must stay foreground. No Windows keyboard/mouse
injection is added. The actual large-window readability and emulator host-focus behavior need user tests.

Only explicitly labeled DEBUG DEMO can run while the official vehicle gateway is UNKNOWN. Operational
Work stays blocked. No vehicle camera or new permission is selected. The reviewed Pillow camera/all
dependency is only for local Unicode/RGBA preview composition, not input authority. PC webcam frames
stay local and calibration is in memory, not a recording or permanent biometric profile.

Automate calibration separation/failure, two hands, finger identity/order, loss/rearm, repeated/stale
frames, same-key references, simultaneous modifiers, keyboard/mouse transitions, strict v0.2 validation,
auth rejection and v0.1 regressions. Hardware acceptance separately measures each finger's missed/false
presses, simultaneous-key behavior, typing accuracy/latency, camera angle, occlusion and fatigue.
Ordinary monocular webcams may not reliably distinguish small ring/pinky/thumb movements.
