# PLEOS Android Agent — input-first product scope

Own android-client/** only.
- Active user UX requirement is a **PC-style desktop QWERTY keyboard**, not the Android phone keypad: visible F1–F12, Ctrl/Alt/Shift, Esc/Tab, Del/Backspace, arrows, useful editor-only shortcuts, language toggle, and responsive keyboard dock.
- Provide mouse-like left/right click, drag/drop and scroll on app-owned Compose targets; right click means a local contextual menu. Camera scroll/right-click mappings must be agreed, not guessed.
- A standard Android IME may assist Korean composition but must not replace the visible desktop keyboard requirement.
- Nonfunctional keys and shortcuts must be disabled/labeled accurately; never simulate global OS controls. Required root AGENTS, SAFETY_SPEC, INPUT_SPEC, PROTOCOL_SPEC and PLEOS official permissions.
- Current goal per ADR-004: gestures control **AirDesk-owned Compose UI targets**, and a PC-style on-screen keyboard edits only focused app-owned fields. Read both feature specifications and ADR-005; implementation/test/manual status is in PHASE2_REVISED_HANDOFF.md.
- Keep the existing secure Windows Host UI/code as optional historical R&D; do not make its mTLS/pairing a precondition for local in-app controls or remove its security.
- No global touch injection, Accessibility automation or other-app control. Demo gestures while safety UNKNOWN must be visibly marked DEMO and isolated from operational Work.
- Never claim correct Hangul composition, app-wide click routing, or vehicle camera/SDK support without tests.
- Fail-closed SafetyGateway default UNKNOWN. A debug cursor may display only if explicitly labeled DIAGNOSTIC; never map it to remote OS input.
- No vehicle camera, gear, speed, HVAC or hidden SDK calls without official API/permission/version verification.
- PLEOS main content region is platform-defined; don't hide top status bars or driving panel with overlays.
- WindowMetrics and Compose constraints, not 2560x1440 fixed pixels; target earlier app content around 1682x1210 as visual reference only.
- Debug-only ws://10.0.2.2:8765 may be enabled only in a debug variant, no release cleartext and no shipping token.
- On unsafe vehicle state, blank/lock any future remote content locally, then request host input release.
- Preserve module boundaries; no redesign of Pleos Stay+ or shared contracts without supervisor.
- Always distinguish assembleDebug success from actual emulator permission/SDK behavior.
