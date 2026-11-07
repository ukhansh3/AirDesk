# Input feature policy — DRAFT, OS INPUT DISABLED

Canonical payloads are in contracts/input/input-event.schema.json; see docs/api/API_REFERENCE.md for required fields and enums.

- INPUT-01 pointer.move: grantId + displayId + normalized x/y.
- INPUT-02 pointer.down / INPUT-03 pointer.up: grantId + button {left,right,middle}.
- INPUT-04 pointer.scroll: grantId, deltaX, deltaY, unit=line.
- INPUT-05 keyboard.down / INPUT-06 keyboard.up: grantId, code (approved W3C UI Events code whitelist; ADR-003).
- INPUT-07 keyboard.text: grantId, text 1..1024 codepoints; never log credentials or text.
- INPUT-08 input.release_all: explicit reason; security-priority release after disconnect.
- INPUT-09 input.lock: safety gate action, local not a remote keystroke.
- INPUT-10 input.unlock: user-triggered only after independently verified vehicle/host session grant.

Every real input message requires peer authentication, expiry/freshness, replay protection, PC user approval, correct capability scope, local vehicle parked/UX gate and independent host watchdog.
No UAC or secure desktop bypass. If press/down state cannot be reconciled after lost connection, release_all; never retry old down events unconditionally.
Coordinate transforms need dynamic app window and letterboxing, not hardcoded 2560x1440.
