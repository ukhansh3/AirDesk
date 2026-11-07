# ADR-004 — AirDesk input-first product scope

Date: 2026-11-07
Status: Accepted user product-direction decision; implementation details and platform capabilities remain unverified.

## Desktop input form-factor clarification (2026-11-07)

The user explicitly requires a **computer-work-environment-style mouse and full physical-PC-like on-screen keyboard**, **not a mobile keypad**. The visual keyboard should expose desktop QWERTY rows, modifier/navigation keys and F1–F12 with honest supported/disabled behavior; mouse interaction should offer left/right click, drag and scroll. Interactions remain scoped to AirDesk-owned Compose Work UI; this is **desktop-like UX**, not a claim that Windows, arbitrary Android apps or system-wide shortcuts can be controlled. See VIRTUAL_KEYBOARD_SPEC.md and GESTURE_UI_INTERACTION_SPEC.md. The scroll/right-click camera gesture mapping needs user approval before implementation.

## Decision
AirDesk's primary function is **hands-free interaction within AirDesk-owned PLEOS/Android Compose work screens**: a camera-driven virtual cursor, click/drag/scroll gestures, and an on-screen keyboard (Korean/English/numbers/symbols). It should not require a physical mouse or keyboard. **Windows remote desktop is not a required product feature.**

The Phase 1 Windows-PC webcam/OpenCV/MediaPipe → local WebSocket → emulator cursor is retained as the *development input path*. Vehicle camera access has NOT been confirmed and must not be assumed. This change does not mean any in-car sensor integration exists.

## Preservation / supersession
ADR-004 supersedes the **product priority** of the old remote-desktop roadmap, NOT its historical records, contracts or security rules. Keep the Windows Host, mTLS/pairing, OS input, and streaming experimental implementation intact as optional legacy R&D. ADR-003 and docs/testing/PHASE2_HANDOFF.md document historical tests (including FAILED TLS) and must remain truthful. Do not erase tests, modify main, or claim revised Phase 2 is implemented.

## Safety and platform constraints
- Route gestures only to registered **AirDesk-owned Compose UI targets** through explicit application callbacks and state, not global Android input injection, AccessibilityService automation, hidden APIs, root, or cross-app control.
- The debug WebSocket is untrusted diagnostic input; dev tokens are not production authentication. Only a conspicuously labeled DEMO workspace may respond to gestures while vehicle safety is UNKNOWN, without touching production Work actions.
- Real in-vehicle Work input needs official PLEOS vehicle parked/UX-restriction checks, freshness, lifecycle constraints, and permission verification. UNKNOWN/DRIVING/UX_RESTRICTED/UNSUPPORTED fail closed.
- The on-screen keyboard edits focused app-owned Compose fields, using supported input/IME behavior. Proper Korean composition is a requirement, **not verified implementation**. No text/keystroke logs.
- No guaranteed system-wide control, vehicle camera integration, Windows streaming or Pleos Stay+ integration. Each requires separate approval/capability review.

## Revised roadmap and acceptance
Phase 1 (existing, user-tested): camera → MediaPipe → diagnostic cursor.
Revised Phase 2 (PENDING): in-app cursor target mapping, stable click/drag/scroll, focused virtual keyboard, isolated demo workspace, tests and PLEOS emulator validation.
Phase 3: ergonomics, Korean IME/gesture accuracy and official PLEOS camera/safety feasibility verification.
Phase 4: independently reviewed Pleos Stay+ Work integration.

Do not choose a new scroll gesture or custom Hangul composition algorithm without review. Document and test an implementation strategy first.
