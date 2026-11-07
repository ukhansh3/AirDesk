# Gesture-to-UI interaction — revised Phase 2 specification (NOT IMPLEMENTED)

## Purpose
Convert Phase 1 validated *gesture hints* into actions on **AirDesk-owned Compose UI elements**. Current cursor demo is read-only. Existing InputCandidates produces remote-Host candidate messages, **not** native Compose clicks.

## Functional requirements
- GUI-01: map normalized cursor to measured app content bounds with safe insets, resize/orientation and letterboxing; reject invalid/stale positions.
- GUI-02: explicit app-owned target/action registry (clickable, scrollable, draggable, editable) with coordinate-to-target hit testing. Do not use global synthetic taps, Accessibility or other-app automation.
- GUI-03: a pinch transitions to one click only on validated release; debounce, jitter suppression, no duplicate action from replay or loss.
- GUI-04: capture eligible draggable target during drag; update within bounds; end on valid release or cancel on loss/background/safety lock. No accidental click after drag.
- GUI-05: scroll on a **reviewed and documented gesture mapping** with direction/sensitivity/rate limits and eligible target. The current gesture contract has no scroll type. Do not silently reinterpret events or change the wire schema.
- GUI-06: selecting an editable target transfers Compose focus; user-visible keyboard may open or close.
- GUI-07: tracking.lost, stale events, disconnect, app background and revoked operational permission cancel held actions immediately. No system/Windows OS input.
- GUI-08: use phase labels DIAGNOSTIC/DEMO/OPERATIONAL accurately. A mock UI demo may be tested while safety UNKNOWN; production Work interaction remains blocked.
- GUI-09: touch remains a supported alternative to camera gestures. UI must be operable without physical peripherals.
- GUI-10: maintain event sequencing, target lifecycle ownership, no repeated action when re-entering screen.

## Proposed acceptance (not executed)
Deterministic tests for coordinate transforms, click once, lost hand while dragging, scroll clamping, focus switch, unknown safety, reconnect, stale/replayed events, and mixed screen sizes. Then human tests on PLEOS emulator. Confirm gesture scroll mapping, screen target model and thresholds before implementing. Do not claim arbitrary Compose buttons become clickable merely by drawing a pointer.
