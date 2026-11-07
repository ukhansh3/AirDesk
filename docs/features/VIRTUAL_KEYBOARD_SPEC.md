# AirDesk virtual keyboard — revised Phase 2 DEMO implementation

Implementation: `DesktopKeyboardDock`, `DesktopKeyboardController`, `DesktopHangulComposer`.
The user explicitly approved the app-rendered full PC keyboard and tested two-set Hangul strategy;
see ADR-005 and PHASE2_REVISED_HANDOFF for exact evidence. Actual PLEOS rendering/IME behavior remains NOT_RUN.

The dock keeps all desktop key groups, F1 help and disabled F2–F12. Narrow screens pan with registered
keyboard arrow controls; short screens have vertical pan controls. No key auto-repeat is implemented.
Both editors use Enter as newline; Tab/Shift+Tab changes editor focus. Page keys move eight logical
newline rows, not measured visual pages. Undo/redo uses bounded per-edit committed snapshots.
The supported Compose text-input interceptor suppresses the phone IME in DEMO editors; native
selection callbacks and TextFieldValue are synchronized with the approved local composer. No system IME is installed.

## Product clarification — PC-style full keyboard (required; 2026-11-07)

**This is NOT a mobile phone keypad or a compact chat keyboard.** The intended experience is a **desktop-PC-style full QWERTY keyboard rendered on the PLEOS Work display**, operable without a physical keyboard. The keyboard must use a recognizably desktop-like multi-row arrangement, with keys clearly labeled and usable via both touch and AirDesk's gesture cursor. Do not satisfy this requirement merely by displaying the standard phone IME.

The target is **desktop-style interaction inside AirDesk-owned Android/Compose work screens**, not Windows OS control or globally injected key presses. A PLEOS/Android IME may provide text composition under the hood, but the visible interface must be a PC-style keyboard. Any platform constraints preventing a custom Compose keyboard or correct Korean composition must be reported rather than silently substituting a mobile layout.

### Mandatory visible key groups
- Desktop QWERTY letters, number row, punctuation and symbols; Korean/English toggle.
- Esc, Tab, Caps Lock (or explicitly modeled case mode), Shift, Ctrl, Alt, Space, Enter, Backspace, Delete.
- Four arrow keys with recognizable desktop-style placement; Home/End/Page Up/Page Down where supported by the app-owned editor.
- Function row F1–F12, with unsupported actions visibly disabled rather than pretending they work.
- Modifier keys with clearly visible pressed/latched state and reliable release/reset; support meaningful **in-app** shortcuts such as Ctrl+A/C/V/X/Z when the focused app-owned editor supports them. Clipboard behavior must use normal supported Android/Compose APIs and must not expose secrets.
- Optional extra keys (e.g., Insert, Print Screen, Windows key, numeric keypad) require an explicit target action and platform feasibility review, not fake system-wide behavior.

### Layout and interaction
- Docked/collapsible bottom keyboard with an explicit show/hide button; preserve usable workspace above it. Support resizing/insets rather than copying phone-keyboard proportions.
- Use touch targets large enough for a vehicle display, with scalable rows/spacing and clear press/hover/selection feedback.
- A pinch click must activate a key **once**, not repeat continuously. Hold/repeat behavior for Backspace/arrows requires its own explicit tested rule.
- Mouse cursor, editor focus, keyboard visibility and active modifiers must remain synchronized. When changing focus or suspending the app, release latched modifiers and cancel pending input.
- Show the currently supported action semantics: keyboard shortcuts operate **only within AirDesk-owned Compose work UI**. Do not promise Windows-style global shortcuts, OS task switching, secure desktop access or control of other apps.

## Scope
An **on-screen** keyboard for **focused AirDesk-owned Compose text fields**, usable by touch and optionally by the in-app gesture cursor. Not Windows SendInput and not a system-wide Android keyboard replacement.

## Requirements
- KB-01: focus acquisition/release, explicit keyboard show/hide, no input without eligible focused editor.
- KB-02: Korean and English text entry; preserve Korean composing region/IME behavior, deletion and cursor placement. Do not assume append-only strings satisfy Hangul composition.
- KB-03: numerals, punctuation/symbols, whitespace, Enter, Backspace, Shift, language switch; clarify newline versus submit per editor.
- KB-04: state synchronization with editor selection, cursor move, backspace and composing text; avoid duplicate text from gesture/touch callbacks.
- KB-05: use supported Compose text-input/IME APIs, or document a design requiring explicit review; do not invent a custom Hangul IME without approval.
- KB-06: keyboard layout adapts to PLEOS app content bounds, insets and accessibility/touch targets. Do not assume fixed 2560×1440.
- KB-07: no keystrokes, passwords, credentials or entered text in logs or telemetry. Clear transient state on close/background/safety lock.
- KB-08: operation on the isolated demo editor while safety UNKNOWN is clearly labeled DEMO; no production vehicle Work input until verified parked/UX gate.
- KB-09: provide both direct touch and eligible gesture-cursor key selection; protect against repeated click/release.
- KB-10: document whether platform-provided IME vs an app-rendered keyboard is selected and why. Confirm actual Korean composition and PLEOS keyboard behavior by manual testing.

## Additional desktop-keyboard acceptance (automated model tests; manual UI pending)

A reviewer must see the full PC-style arrangement and key groups listed above, not a phone keypad. Validate modifier-chord semantics (Ctrl+A/C/V/X/Z where editor supports them), Esc/Tab, arrows, navigation, Delete/Backspace, F1–F12 disabled/handled states, show/hide docking, keyboard focus and gesture/touch parity. Document platform behavior for Korean IME composing regions rather than pretending a plain key append suffices.

## Acceptance (exact results in revised handoff)
Unit/UI tests: Hangul composing, English case, symbols, enter/backspace, focus switch, cursor selection, duplicate gesture taps, empty field, background cancel, safe insets. Manual emulator keyboard/IME validation required before completion claims.
