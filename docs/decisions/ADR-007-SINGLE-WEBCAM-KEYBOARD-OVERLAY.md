# ADR-007 — Single-webcam experiment and camera keyboard reference

Date: 2026-11-08.
Status: Accepted user direction: one RGB webcam first; transparent camera keyboard reference.
Fixed world-space contact recognition remains experimental design, not implemented by this display change.

## User decision

Use the existing single Windows-PC webcam for the next experiment. No new depth device, projector,
physical typing surface or AR display is selected. The user wants the live camera preview to show
a full keyboard with green rectangular outlines, English and Korean labels and transparent interiors.
The effect is composited into the displayed camera image, not projected into physical space.

The long-term interaction goal is still ten-finger typing against fixed spatial key locations.
Do not substitute a keyboard that follows the hands or claim that a 2D image overlay establishes
world-space depth/contact. See [spatial design](../features/SPATIAL_AIR_KEYBOARD_DESIGN.md).

## Existing stack and new display dependency

- Keep Python/OpenCV/MediaPipe for capture and hand tracking, the existing loopback WebSocket and
  JSON Schema checks, and Kotlin/Jetpack Compose for the app-owned keyboard/editor.
- Reuse the existing 1120x360 keyboard geometry and two-set Korean letter labels. Map both axes
  independently onto the displayed camera image so the overlay agrees with current v0.2 normalized
  image-space coordinates. This mapping is intentionally not a perspective/world-plane calibration.
- Use Pillow for the PC-only RGBA overlay and Unicode TrueType labels. Declare `Pillow>=10,<14`
  only in the `camera` and `all` optional extras. The local webcam environment already contains
  Pillow13.0.0, so this does not require upgrading or downgrading its existing packages.
- Read Windows' installed Malgun Gothic font; do not embed or redistribute it. Missing Pillow or
  a suitable Hangul font disables this optional visualization with a bounded explanation, while
  the camera and existing recognition continue.

Alternatives reviewed: OpenCV's existing Hershey text path cannot supply the required Hangul labels.
A Tk Canvas overlay would avoid an image library but lacks the same per-pixel RGBA compositing
path and would be separate from the displayed frame. Pillow provides Unicode font rasterization
and explicit alpha compositing without a new UI framework, Android permission or transport format.
No detector/SDK version is changed. No additional direct NumPy dependency is needed by this renderer.

## Rendering boundary

Run MediaPipe against the existing original image. Only afterward, on the resized RGB preview copy,
composite green outlines and labels. Key interiors outside text and outline pixels have zero alpha;
there is no tinted key fill, opaque keyboard panel or dark full-image backdrop.

Default overlay visibility is on; the camera panel exposes a checkbox and line-opacity control.
The keyboard is stable in image coordinates and does not animate/follow individual hands. Rebuild
one cached mask only for dimensions/opacity changes, not for each hand update. Preserve the existing
latest-frame queue and clear/ignore late preview images when stopping the camera.

The embedded panel moves detector/overlay guard captions outside the video so they do not cover
function keys. Its video fits the actual preview allocation without cropping the keyboard. The
standalone OpenCV preview retains its existing diagnostic captions and does not gain this PC-panel
keyboard layer.

The overlay does not create presses, authorize input, reinterpret calibration, display typed text,
change Windows/Android global input, or transmit/save frames. Existing joint markers and the
separate large-keyboard estimate window retain their purpose. Current v0.1/v0.2 schemas stay intact;
the separately approved v0.3 mode-switching profile remains deferred.

## Verification and next experiment

Automate projection parity for all keys, bilingual labels, pixel-level transparent interiors,
opacity, unchanged detector/source pixels, cache bounds, graceful font/dependency failure and
late-frame rejection. Inspect a synthetic rendered artifact without launching camera or GUI.
Actual webcam legibility, calibration comfort, spatial accuracy and PLEOS behavior remain user gates.

A separate Ubuntu headless CI job installs Pillow, OpenCV's headless wheel and the distribution's
Noto CJK/Tk packages, and rejects skipped camera-keyboard tests. It does not install MediaPipe or
the runtime OpenCV-contrib wheel in that isolated job. The ordinary lightweight gesture CI remains
separate. This exercises real Unicode/alpha composition without a webcam/display; a local Windows
test does not establish that the new remote Ubuntu job has executed successfully.

Next, measure single-camera estimated spatial press/release on a small set of keys before scaling
to the complete keyboard. Evaluate stationary false presses, misses, adjacent-key errors, occlusion,
release and camera/hand motion. The one-camera choice resolves the initial hardware direction;
it does not prove the fixed-plane estimator or commit to an unreviewed absolute-depth algorithm.

References:
- [MediaPipe Hand Landmarker outputs](https://developers.google.com/edge/mediapipe/solutions/vision/hand_landmarker/python)
- [Pillow alpha compositing](https://pillow.readthedocs.io/en/stable/reference/Image.html#PIL.Image.alpha_composite)
- [Pillow TrueType font loading](https://pillow.readthedocs.io/en/stable/reference/ImageFont.html#PIL.ImageFont.truetype)
