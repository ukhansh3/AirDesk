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

- Start calibration explicitly. It begins with both hands' neutral pose, then prompts LEFT and RIGHT
  THUMB, INDEX, MIDDLE, RING, LITTLE in order. Capture is delayed so the user can return their hand
  after touching the control. Gather multiple fresh, stable both-hand samples; reject unusable samples.
- Calibrate finger-shape/flex separation relative to that hand's neutral pose. Thumb features are
  distinct from the other fingers. No contact/pressure or metric keyboard plane is inferred.
- Map camera image x/y to the complete normalized keyboard/mouse design surface (1120x360).
  Show hovered keys before enabling typing. This is an image-space mapping, not calibrated physical depth.
- Require explicit arm plus local DEMO enable. Each finger must rearm in neutral, then produce one
  down edge after calibrated hysteresis/duration. Bind the pre-stroke key until release. No hold-repeat.
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
- `air.frame` is an atomic complete state: calibration epoch/stage/status/armed, at most ten unique
  hand/finger observations, plus mouse state. Omitted/invalid fingers release. Text is never transported.
- Source sequence is global per connection. Reject replay, mixed versions/sessions and stale/regressing
  timestamps. New calibration epoch discards previous held state; reconnect never restores arm/consent.
- App delivers accepted frames synchronously to the controller, not via conflating StateFlow alone.
  A separate local stale watchdog cancels input even when network callbacks stop arriving.

## Scope and verification

Only explicitly labeled DEBUG DEMO can run while the official vehicle gateway is UNKNOWN. Operational
Work stays blocked. No vehicle camera/permission or new external library is selected. PC webcam frames
stay local and calibration is in memory, not a recording or permanent biometric profile.

Automate calibration separation/failure, two hands, finger identity/order, loss/rearm, repeated/stale
frames, same-key references, simultaneous modifiers, keyboard/mouse transitions, strict v0.2 validation,
auth rejection and v0.1 regressions. Hardware acceptance separately measures each finger's missed/false
presses, simultaneous-key behavior, typing accuracy/latency, camera angle, occlusion and fatigue.
Ordinary monocular webcams may not reliably distinguish small ring/pinky/thumb movements.
