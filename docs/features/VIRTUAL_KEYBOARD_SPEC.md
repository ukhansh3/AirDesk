# AirDesk virtual keyboard — revised Phase 2 specification (NOT IMPLEMENTED)

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

## Acceptance (not executed)
Unit/UI tests: Hangul composing, English case, symbols, enter/backspace, focus switch, cursor selection, duplicate gesture taps, empty field, background cancel, safe insets. Manual emulator keyboard/IME validation required before completion claims.
