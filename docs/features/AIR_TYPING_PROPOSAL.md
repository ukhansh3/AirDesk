# Ten-finger midair keyboard and side mouse — proposal

Status: **SUPERSEDED BY APPROVED IMPLEMENTATION SPEC**.
Date: 2026-11-07. The user explicitly requested implementation after reviewing this proposal.
See [AIR_TYPING_SPEC.md](AIR_TYPING_SPEC.md) and [AIR_TYPING_HANDOFF.md](../testing/AIR_TYPING_HANDOFF.md)
for the current design/code/test status. The proposal below is historical, not a hardware success report.

## Confirmed interaction

- Both hands and all ten fingers type as if using a PC keyboard, with the hands held in midair.
- Camera looks from slightly above the front. Hands are held forward with fingers loosely curled
  downward (the user's ghost-hands description), rather than a single index pointing at keys.
- The complete PC keyboard and a mouse region beside it are visible together, scaled as needed.
- The screen shows fingertip/key position, recognized press/release and the resulting local text.
- Current single-index/Pinch selection is not ten-finger typing. No ten-finger success is claimed.

## Proposed experimental recognition (pending approval)

1. Guide both hands into the camera keyboard region and identify stable tracks. Calibrate the neutral
   pose and deliberate flex/release samples for each of ten fingers; thumbs need their own features.
2. Normalize joint flexion and fingertip displacement by palm size/orientation. Reject overlapping,
   occluded or unstable calibration rather than guessing a key press.
3. Show all ten fingertip markers and hovered keys before arming. Bind a key at the calibrated
   downstroke, using the last stable pre-stroke position so the press motion cannot slide into a neighbor.
4. Maintain independent per-finger unarmed/ready/down/wait-release state with hysteresis and no
   automatic hold-repeat. Hover alone never types. Reacquisition while flexed must not create a Down.
5. Hold Shift/Ctrl/Alt by finger ownership in AIR mode; preserve existing click-latched touch/Pinch
   behavior in its existing mode. Apply simultaneous frame edges deterministically and reference-count
   keys shared by multiple fingers. No global Android/Windows input is introduced.
6. Moving the right hand into the side mouse region first releases its typing ownership, then requires
   a neutral rearm. Proposed mouse behavior reuses the approved index cursor/Pinch click and existing
   explicit right-click/scroll controls. Returning to typing also requires a neutral rearm.

## Proposed data boundary (pending approval)

The current v0.1 event has one position/gesture and cannot represent ten independently held fingers.
Do not add unreviewed fields to it or mix two hands into its one cursor state. A separate explicitly
negotiated diagnostic AIR profile would carry complete sequenced frames, stable hand/finger identity,
calibration epoch, normalized fingertip positions, estimated press states and tracking validity.
Android maps these hints to its own measured key geometry; text stays local. Preserve v0.1 schemas,
fixtures and producer behavior. No new wire schema has been created or changed for this proposal.

Loss, ambiguity, stale frames/epochs, disconnect, calibration changes, background and safety lock must
release owned state. Only an isolated explicit DEBUG DEMO may respond while vehicle safety is UNKNOWN.

## Hardware limitations and required evidence

MediaPipe provides 21 landmarks per hand. Image z is relative to the wrist and world landmarks are
relative to each hand's center; these are not pressure/contact measurements. The proposed typing
stroke is an inference from motion and finger shape, not a detected physical key contact.
[Official Hand Landmarker documentation](https://developers.google.com/edge/mediapipe/solutions/vision/hand_landmarker?tab=Python).

Required model tests include hand-order swaps/crossing, per-finger rearm/loss, same-frame chords,
key capture/release, false coupled finger motions, boundary transitions, replay and calibration epoch
changes. Actual webcam tests must measure per-finger false/missed presses, simultaneous recognition,
typing accuracy, latency, camera placement and fatigue. Ring/pinky coupling, thumb motion, occlusion
and weak monocular depth may prevent comfortable typing. Synthetic tests cannot establish reliability.

The user approved proceeding with calibrated flex/release typing, side-mouse neutral transitions
and a separate experimental protocol version. Actual webcam accuracy remains unverified.
