# Gesture feature specification

- GEST-01: index fingertip landmark 8 -> normalized pointer visual position.
- GEST-02: thumb tip 4 + index tip 8 normalized pinch ratio -> pinch_start.
- GEST-03: hysteresis threshold -> pinch_end.
- GEST-04: held pinch with displacement -> drag_start / drag_move.
- GEST-05: hand lost -> tracking.lost, and downstream release of held input.
- GEST-06: smoothing/filtering for jitter.
- GEST-07: debounce across rapid threshold oscillations.
- GEST-08: mirror/orientation mapping and safe coordinate clamp.

States: IDLE, TRACKING, PINCHED, DRAGGING, LOST. No gesture classifier may call SendInput itself.
Camera default is local Windows webcam; MediaPipe outputs 21 hand landmarks. Model file is external and not committed.
Test positive and negative threshold crossing, lost hand while dragging, jitter, out-of-order time, mirror and zero palm length.
All numeric thresholds must be calibrated; do not claim 20–30 fps without measured evidence.
