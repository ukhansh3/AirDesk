# ADR-008 — Proportional tenkeyless camera reference

Date: 2026-11-08. Status: accepted user display requirement; camera-only scope.

## Decision

The user requested realistic keyboard proportions, chose tenkeyless, and explicitly selected
**camera display first** rather than changing Android's keyboard and input mapping in this update.

The PC panel defaults to a green, bilingual **87-key ANSI-style TKL reference**. Keep the function
row, separated navigation and inverted-T arrow groups; omit the number pad and the former oversized
mouse rectangle. Preserve transparent interiors, opacity controls, local fonts and the single-webcam
approach from ADR-007. Unsupported system/function keys are reference legends only, not new actions.

Use one key-pitch unit for geometry: ordinary keycaps have equal width/height, with staggered rows
and longer Backspace/Tab/Caps/Enter/Shifts/Space. Space is 6.25u; Enter and left Shift are 2.25u;
right Shift is 2.75u. The main key field spans 15u. A separate 3u navigation group and 0.25u gap
give an 18.25u key span; a chosen 0.25u casing margin yields an 18.75x7u reference frame.
This is an ANSI-style visual model, not a measurement or certification of a particular product.

Fit the complete reference near the bottom center of the image using a **single scale factor**.
Changing video aspect ratio must not stretch keys separately in X/Y. Keep all keys inside the image
for landscape, 4:3 and portrait previews. The keyboard stays fixed in image coordinates; it does not
follow the hands or claim to be fixed in metric world space.

## Preserve the existing input alignment

The new TKL reference is **not aligned with the current v0.2 hit map**. Its 87-key arrangement and
letterboxed proportions deliberately differ from the old 81-key image-space layout. Do not silently
send remapped source coordinates, add keys to the Android controller, reinterpret commands or
claim that touching a drawn TKL key already types that key.

The panel therefore retains **기존 AIR 입력 정렬**, which restores the previous 81-key, independent
X/Y projection and side mouse rectangle. Use that mode when checking existing Android AIR typing.
The TKL choice is visibly labeled as a layout reference. The separate large PC keyboard window and
Android keyboard stay on their prior geometry and behavior. No Android rebuild is needed here.

`CameraKeyboardOverlay()` retains its legacy default for existing callers; the PC runtime explicitly
selects its new TKL default. Switching display mode only replaces the local reference mask and
bounded display notices, with no effect on authentication, calibration, arming, input or freshness.

## Verification and boundaries

Test exact key count/groups, physical relative widths/stagger, square caps, uniform fit, casing
aspect, transparent interiors, English/Hangul labels, tiny-image handling and bounded caching.
Independently test actual runtime output/default selection, legacy pixel parity, switching/failure
recovery, unchanged source RGB/landmarks and stop behavior. Review synthetic rendered examples;
real webcam/Tk legibility and actual in-app typing remain manual checks.

No wire schema, detector, SDK, dependency, permission or production safety change is required.
Spatial press recognition and the approved future v0.3 mode-switching profile remain separate work.
