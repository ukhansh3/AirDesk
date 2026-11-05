# Windows Host Agent
Owner: windows-host/**. Required: root AGENTS, INPUT_SPEC, PAIRING_SPEC, SAFETY_SPEC, protocols.
- Windows input remains disabled unless both authenticated session and explicit local user grant.
- No SendInput or desktop capture code in this initial safe skeleton.
- Never log keyboard data, camera data, pairing tokens or full screen captures.
- Bind local diagnostic endpoints to 127.0.0.1; never silently start unattended service.
- Windows UIPI restrictions may not be bypassed.
- Release all pressed inputs on revoke, timeout, disconnect or vehicle safety change.
- Keep WPF presentation separate from session/input engine. Unit-test state logic first.
- Do not modify Android, Gesture Engine, shared contracts or Pleos Stay+.
