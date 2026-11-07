# ADR-005 — Desktop DEMO interaction implementation

Date: 2026-11-07. Base: dev f8a3374fe109cd72e54ecb6cda364ec8dfc932b0.
Status: user-approved interaction choices; implementation and actual device validation are separate.
Scope: implements ADR-004 within AirDesk-owned Compose UI. No shared wire-schema migration.

## Approved choices

- A visible full PC-style QWERTY keyboard, docked below a usable work surface. Number/punctuation,
  function, modifier and navigation groups remain recognizable; this is not a phone keypad.
- The user approved an app-rendered keyboard with a directly implemented, unit-tested two-set
  Korean composer, without an added library. Preserve TextFieldValue selection/composing state.
- Left click: pinch at least 80ms, movement within 12dp, release, minimum 250ms between activations.
  These are configurable initial defaults, not measured ergonomic guarantees.
- Drag reuses Phase 1 drag_start/drag_move. No new camera classifier or event type is added.
- Explicit Scroll mode converts pinch-drag over an eligible scroll target to vertical scrolling.
  Upward drag scrolls content downward; limit each update to 48dp and 1200dp/s, at least 16ms apart.
- Explicit Right click once mode makes the next eligible pinch click open an app-owned context menu,
  then returns to left-click mode. Mode buttons remain selectable in every mode.
- Keyboard keys activate once per accepted click. No automatic Backspace/arrow key repeat in this phase.

## Local target and focus boundary

Register stable IDs, measured bounds and explicit callbacks only inside the isolated DEMO composable.
Normalized camera positions map to its actual measured viewport including the keyboard dock, excluding
system insets and outer navigation. Use one Compose root coordinate system, never fixed screen pixels.
Targets leaving composition unregister. A viewport change cancels pending interaction. A captured
draggable keeps its identity while its own bounds move; disabled/removed targets cancel capture.

Accepted Phase 1 messages must reach the router without StateFlow conflation. A visual state snapshot
is not an event queue. Validate session/order/freshness before action; cancel on disconnect, loss,
background, screen exit or safety lock. Reconnect and foreground resume never restore a held action.
Phase 1 can emit pinch_end immediately before tracking.lost. Release-to-click therefore waits for the
next fresh valid hand move; loss/zero confidence cancels it. Hover alone never activates a key.

The keyboard retains editor focus when clicking keys. Focus changes/hide/lifecycle clear modifiers
and transient composition ownership. Ctrl+A/C/V/X/Z (and any supported undo/redo) apply to eligible
app-owned editors only. Clipboard access uses standard Android APIs on explicit copy/cut/paste,
never a background listener. Text and keystrokes are not logged or sent over either network channel.
F1 may open app help; unsupported F2–F12 are visible and disabled. No global OS shortcuts are synthesized.

## Compose text input

Use supported BasicTextField/TextFieldValue editing and selection. Its value-based overload does not
honor showKeyboardOnFocus, so the DEMO suppresses the phone IME with Compose's documented
InterceptPlatformTextInput/awaitCancellation pattern. This is scoped to DEMO editors and does not
install a system IME. Actual Korean/selection/keyboard behavior on PLEOS remains a manual gate.

## Safety and preservation

DEMO callbacks have no production Work or Windows input route. Official vehicle evidence remains
UNKNOWN; OPERATIONAL interaction stays unavailable. No Accessibility, global Android event injection,
Windows SendInput, new vehicle permission, camera capture or emulator launch is part of this work.
Keep Phase 1 producer/wire contracts, Windows Host/mTLS code and ADR-003 historical evidence intact.
Pre-existing uncommitted Windows fixes stay in their original checkout, outside this change.

## Verification

Pure router/keyboard/composer tests, callback integration tests, Phase 1 regression and assembleDebug
provide automated evidence. They do not prove a rendered PLEOS layout or actual webcam ergonomics.
The revised handoff records exact XML counts, hardware NOT_RUN and remaining BLOCKED safety gates.

References:
- [Compose focus behavior](https://developer.android.com/develop/ui/compose/touch-input/focus/change-focus-behavior)
- [Compose supported soft-keyboard suppression sample](https://android.googlesource.com/platform/frameworks/support/+/HEAD/compose/ui/ui/samples/PlatformTextInputModifierNodeSample.kt)
- [BasicTextField API](https://developer.android.com/reference/kotlin/androidx/compose/foundation/text/package-summary)
