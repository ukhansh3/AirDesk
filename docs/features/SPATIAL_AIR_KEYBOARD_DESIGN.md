# Fixed-position spatial AIR keyboard — design boundary

Updated: 2026-11-08. Status: **SINGLE-RGB-WEBCAM EXPERIMENT USER-APPROVED; PC CAMERA WIREFRAME IMPLEMENTED + AUTOMATED PASS; FIXED-WORLD PRESS SENSING NOT IMPLEMENTED/VERIFIED**.

## Intended interaction

The user wants a PC keyboard occupying a fixed location in front of the hands, like the layout in a
projection-keyboard reference image. Each finger should press the corresponding spatial key as on
a familiar keyboard. The vehicle has no available flat typing surface. A printed keyboard, desk,
projected image on a surface, or physical keyboard is therefore not the selected solution.

The keyboard must stay at its established spatial location; it must not follow moving hands.
This clarification supersedes the earlier hand-following HOME-position proposal. No HOME mapper
was implemented. Preserve the current full desktop keyboard, supported editor behavior and Phase 1.

Desired future behavior:

- Establish the keyboard's origin, orientation, size and interaction plane once, with guided setup.
- Track both hands and ten fingertips relative to that fixed plane, rather than mapping the entire
  camera image directly onto the keyboard or treating any finger curl as a key press.
- Identify a key from its bounded spatial region; require an intentional approach/press/release cycle.
- Separate hover, uncertain location, estimated press, accepted editor action and release visually.
- Apply hysteresis and neutral rearm; an unseen/reappearing finger cannot resume a held key.
- Keep mouse entry explicit; entering the side of an image must not silently change typing intent.
- Show the complete PC keyboard and current target feedback on PLEOS and the development PC.

These are acceptance goals, not claims that the present webcam can measure them accurately.

## Display versus sensing

The current webcam and PLEOS display can render a keyboard and a camera composite on a screen.
They do not project a visible keyboard into free space. The reference image does not establish a
selected projector or sensor model. Surface projection also does not solve a no-surface requirement.

MediaPipe produces normalized image landmarks and wrist-relative depth estimates. Its presence and
handedness scores are not measurements of fingertip contact with a fixed world-space plane.
Consequently the current implementation cannot label a normalized landmark crossing as verified
3D contact. A camera-composited visual experiment and a measured spatial-input system are distinct.
See [official Hand Landmarker output](https://developers.google.com/edge/mediapipe/solutions/vision/hand_landmarker).

## Selected experiment — one RGB webcam first

The user approved proceeding with a visibly labeled **single-RGB-webcam estimation experiment first**.
Use the existing PC webcam/MediaPipe path and expose uncertainty and observed errors. This selects
the research approach; it does not establish a world-space interaction plane or replace the current
v0.2 press classifier in the camera-overlay delivery.

A measured depth/tracking sensor is a future fallback only if the webcam experiment's measurements
show it cannot meet the intended interaction. A supported device, SDK/dependency/permission review
and any hardware purchase require a separate concrete decision. No absolute-depth conversion is
inferred from the current image landmarks.

For the webcam study, establish a common coordinate frame and quantify drift, fingertip occlusion,
stationary false presses, missed presses, adjacent-key errors and release behavior before claiming
comfortable ten-finger typing. Start with the eight home keys and Space/Backspace as a measured
future press experiment; the current visual overlay still contains all 81 keys. Retain the complete
keyboard UI and document the actual reach of other keys.
Do not substitute elapsed timers or generated synthetic trajectories for human typing evidence.

## Current visual step — green keyboard on the live PC camera

The user explicitly requested a green wireframe keyboard over the live PC image, with English and
Hangul labels and clear key interiors. This is a local camera-composited reference, not a physical
hologram, projector or measured fixed-world press classifier.

- Reuse the existing v0.2 normalized image-space 1120×360 geometry: all 81 desktop keys and the side
  mouse region. All 26 letter keys carry English and two-set Hangul labels. Keep F1–F12 and the
  modifier/navigation groups visible; their existing supported/unsupported actions do not change.
- Only green line/text pixels receive configurable opacity, default 0.4 (the PC slider spans 15–80%).
  Key/panel backgrounds have zero alpha, so the hands remain visible. There is no filled translucent keyboard plate.
- Render after MediaPipe has consumed its input, only for the PC preview. The source landmarks,
  v0.1/v0.2 messages, Android editor and calibrated flex/MCP press/release behavior remain unchanged.
  Camera pixels are neither saved nor sent over WebSocket.
- The PC display option defaults ON and exposes a visibility checkbox plus opacity adjustment.
  It is a display setting, not calibration, arm consent or an input permission.
- Detector/overlay quality captions appear outside the panel's video, leaving function keys clear.
  The preview fits its allocated widget, capped at 860×480. This layer belongs to the Run-AirDesk
  panel; the standalone OpenCV `--preview` keeps its previous display/captions without a green keyboard.
- Pillow `>=10,<14` is the reviewed camera/all extra for Unicode labels and real RGBA composition;
  use the installed Windows Malgun font without redistributing font files. Dependency and boundary
  rationale is recorded in [ADR-007](../decisions/ADR-007-SINGLE-WEBCAM-KEYBOARD-OVERLAY.md).

If the optional dependency/font fails, fix it and explicitly toggle visibility OFF then ON to retry.
Opacity changes alone do not repeatedly retry the failed renderer. Existing camera recognition continues.

Automated geometry/alpha tests and actual webcam readability/performance are separate evidence.
The current overlay can show where the existing image-space keys lie; it cannot prove physical
fingertip contact or comfortable spatial typing. Exact implementation/test/manual status is in
[AIR_TYPING_HANDOFF.md](../testing/AIR_TYPING_HANDOFF.md).

## Protocol and preservation

The user approved a separate v0.3 experimental profile for app-controlled keyboard/mouse switching,
preserving Phase 1 and v0.2. Its implementation is deferred until the spatial sensing/coordinate
boundary is concrete. No v0.3 schema, route, command or input authority has been added in the
calibration-recovery repair. Do not reinterpret existing v0.2 fields or commands as a new spatial API.

Current v0.2 remains a camera-image-to-keyboard projection plus calibrated flex/MCP hints. The green
camera overlay adds no profile, route, wire command or input authority. Its
temporary-delay repair preserves completed calibration stages within the same live connection;
that improvement is independent of this future spatial-keyboard design.

Operational vehicle Work remains blocked while official safety evidence is UNKNOWN. All experiments
remain explicitly labeled app-owned DEMO, with no OS input, other-app control or camera recording.
