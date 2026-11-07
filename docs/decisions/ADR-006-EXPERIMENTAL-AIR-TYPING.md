# ADR-006 — Experimental ten-finger midair typing profile

Date: 2026-11-07. Status: accepted for isolated DEBUG DEMO implementation; hardware accuracy unverified.

The user clarified that a single pointer selecting keys is insufficient: both hands/all ten fingers
should tap in midair, with a full keyboard and mouse area visible together. They approved proceeding
with the calibrated flex/release proposal and explicitly requested a spec before implementation if useful.

Use [AIR_TYPING_SPEC.md](../features/AIR_TYPING_SPEC.md) as the implementation contract. Reuse MediaPipe
Tasks, the existing local PC preview/server, app-owned key geometry and local Korean/editor controller.
Add a separate negotiated v0.2 `/air` profile with complete hand/finger state and calibration epoch;
retain v0.1 contracts/behavior. No new dependency, Android permission or production authorization.

Inference is calibrated finger-shape/motion, not contact or measured key depth. Both-hand neutral and
ten individually separated finger poses are required. Unusable calibration stays blocked. AIR held
modifiers/ownership are separate from touch latches. Neutral transitions separate typing from the
approved index/Pinch mouse behavior. Mouse loss must not reset the other hand's text composition.

Tasks handedness changed in MediaPipe0.10.5. Two-hand AIR observation requires a known version at
least0.10.5; mirrored input swaps the current Tasks result labels. Legacy single-hand xy remains
available. See the upstream [handedness label correction](https://github.com/google-ai-edge/mediapipe/commit/ed0c8d8)
and the [official task guide](https://developers.google.com/edge/mediapipe/solutions/vision/hand_landmarker?tab=Python).

Reject invalid/duplicate wire data, old sessions/epochs and stale capture before reenveloping state.
Input never automatically resumes after reconnect. No camera/text recording, global Android input or
Windows OS input. Actual camera angle, coupled finger motion, occlusion, speed, accuracy and fatigue
require manual measurement; passing unit/loopback tests does not prove practical typing.
