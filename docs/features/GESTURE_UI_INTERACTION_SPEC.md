# Gesture-to-UI interaction — revised Phase 2 specification (NOT IMPLEMENTED)

## Desktop-mouse interaction requirement (2026-11-07)

The pointer is intended to **feel like a computer mouse within the AirDesk Work surface**, not merely mark the hand position. In addition to pointer movement, specify and visibly support **left click, right click/context action, drag/drop, scrolling, and focus selection** for eligible AirDesk-owned UI targets. Right click should open a *local app-owned contextual menu*; it must not invoke the Android global context menu or Windows OS input.

Only pinch is currently defined in the Phase 1 gesture contract. **How to distinguish right-click and scroll gestures is not decided**. Agents must propose a mapping with usability/accidental-activation analysis and obtain user approval before changing the gesture contract or adding classifiers. Touch fallback controls for right click/scroll may be implemented in the demonstrator without inventing camera gestures.

Keep the pointer visible and maintain click and drag feedback; suppress phantom clicks from tracking loss or gesture bounce. Target hit testing must be inside the measured Compose workspace, including keyboard dock and desktop-like task controls.

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
- GUI-11: left click, right-click/context menu, drag/drop and scroll must be mapped to explicit **app-owned** actions; unsupported target actions indicate unavailability rather than synthesizing system events.
- GUI-12: a gesture-controlled keyboard key is an explicit target, with one activation per recognized click. Hover is not a click.
- GUI-10: maintain event sequencing, target lifecycle ownership, no repeated action when re-entering screen.

## Proposed acceptance (not executed)
Deterministic tests for coordinate transforms, click once, lost hand while dragging, scroll clamping, focus switch, unknown safety, reconnect, stale/replayed events, and mixed screen sizes. Then human tests on PLEOS emulator. Confirm gesture scroll mapping, screen target model and thresholds before implementing. Do not claim arbitrary Compose buttons become clickable merely by drawing a pointer.
