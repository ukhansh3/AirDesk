# PLEOS Android Agent

Own android-client/** only. Required root AGENTS, SAFETY_SPEC, INPUT_SPEC, PROTOCOL_SPEC and PLEOS official permissions.
- Fail-closed SafetyGateway default UNKNOWN. A debug cursor may display only if explicitly labeled DIAGNOSTIC; never map it to remote OS input.
- No vehicle camera, gear, speed, HVAC or hidden SDK calls without official API/permission/version verification.
- PLEOS main content region is platform-defined; don't hide top status bars or driving panel with overlays.
- WindowMetrics and Compose constraints, not 2560x1440 fixed pixels; target earlier app content around 1682x1210 as visual reference only.
- Debug-only ws://10.0.2.2:8765 may be enabled only in a debug variant, no release cleartext and no shipping token.
- On unsafe vehicle state, blank/lock any future remote content locally, then request host input release.
- Preserve module boundaries; no redesign of Pleos Stay+ or shared contracts without supervisor.
- Always distinguish assembleDebug success from actual emulator permission/SDK behavior.
