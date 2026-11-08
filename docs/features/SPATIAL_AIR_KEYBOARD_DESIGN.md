# Fixed-position spatial AIR keyboard — design boundary

Updated: 2026-11-08. Status: **USER REQUIREMENT CLARIFIED; DESIGN, NOT IMPLEMENTED**.

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

## Open implementation decision

Before replacing the current press classifier, resolve whether to pursue a visibly labeled,
single-webcam spatial-press estimation experiment or a measured depth/tracking sensor design.
The first option must expose uncertainty and report observed errors; the second needs a specific
supported device, coordinate calibration and SDK review. No hardware purchase, new dependency,
new permission or inferred absolute-depth conversion is approved by this document.

For either option, establish a common coordinate frame and quantify drift, fingertip occlusion,
stationary false presses, missed presses, adjacent-key errors and release behavior before claiming
comfortable ten-finger typing. Start with the eight home keys and Space/Backspace as a measured
experiment; retain the complete keyboard UI and document the actual reach of other keys.
Do not substitute elapsed timers or generated synthetic trajectories for human typing evidence.

## Protocol and preservation

The user approved a separate v0.3 experimental profile for app-controlled keyboard/mouse switching,
preserving Phase 1 and v0.2. Its implementation is deferred until the spatial sensing/coordinate
boundary is concrete. No v0.3 schema, route, command or input authority has been added in the
calibration-recovery repair. Do not reinterpret existing v0.2 fields or commands as a new spatial API.

Current v0.2 remains a camera-image-to-keyboard projection plus calibrated flex/MCP hints. Its
temporary-delay repair preserves completed calibration stages within the same live connection;
that improvement is independent of this future spatial-keyboard design.

Operational vehicle Work remains blocked while official safety evidence is UNKNOWN. All experiments
remain explicitly labeled app-owned DEMO, with no OS input, other-app control or camera recording.
