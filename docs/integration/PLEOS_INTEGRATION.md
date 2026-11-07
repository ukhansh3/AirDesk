> **Direction update (ADR-004):** Future Pleos Stay+ Work integration should embed AirDesk-owned **gesture cursor + Compose target interactions + on-screen keyboard** rather than require Windows remote desktop, secure Host pairing or video. The legacy gateways/integration gates below are historical optional proposals; no Stay+ files or SDK permissions have been changed. Verify PLEOS vehicle safety and permissions before real Work input. See [revised Phase 2 handoff](../testing/PHASE2_REVISED_HANDOFF.md).

# Integrating standalone AirDesk with Pleos Stay+ Work — NOT YET IMPLEMENTED

Current separate repository: AirDesk. Existing destination: Pleos Stay+ Android Work feature, currently driven by Kotlin/Compose and a separate Realtime manual file transfer API.

## 1. Rule
Do NOT edit Pleos Stay+ from AirDesk without explicit approval. AirDesk build/test independently first; no automatic repo coupling or copy-pasting credential code.

## 2. Gateway boundaries
- AirDeskGestureGateway: StateFlow<GesturePreview>; visual-only diagnostic without control grants.
- AirDeskSessionGateway: session state, auth, PC host grant; fail-closed.
- AirDeskVideoGateway: stream status and surface lifecycle; only after transport PoC.
- AirDeskInputGateway: scoped pointer/keyboard commands; never direct access without safety.
- WaitingSafetyGateway adapter: only approved PLEOS vehicle state, uses existing lifecycle and does not start a second monitor.

These are proposed interfaces, NOT official PLEOS SDK APIs; exact types and permission names must be confirmed.

## 3. Window size
PLEOS total display size is not equal to the app content area. Measure actual WindowMetrics+insets at runtime. Previous test app content approximately 1682×1210 px but not a permanent hardware guarantee.

## 4. Integration gates
1. Android diagnostic cursor on PLEOS Emulator 2.1.0.
2. True verified parked/UNKNOWN/driving behavior on the supported SDK.
3. Secure host pairing, PC approval, input heartbeat and release tests.
4. Actual video stream in app region with automatic blackout on unsafe.
5. API compatibility adapter that leaves Parking, Waiting, Pickup and Realtime untouched.
6. Regression evidence and user approval before merging into Pleos Stay+.

## 5. UI
AirDesk uses the established premium navy/purple Work visual language, but does not draw fake system bars or promise unverified seat/HVAC control. If unsafe, display explicit lock status; don't show an operational remote desktop.
