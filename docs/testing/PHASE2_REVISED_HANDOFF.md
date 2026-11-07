# Revised AirDesk Phase 2 — documentation handoff

Date: 2026-11-07
Status: **PLANNED — specification only, not implemented or tested**.

## Why the direction changed
The intended AirDesk product is physical-mouse/keyboard-free **PLEOS in-app** interaction, not a Windows remote desktop. Phase 1 webcam gesture detection and emulator cursor remain useful. Existing Phase 2 remote-Host code and its historical documentation are preserved as optional R&D, **not** accepted as revised Phase 2.

## Inventory
- REUSE: gesture-engine, its local debug WebSocket and Android read-only cursor; user reported Phase 1 camera/cursor success.
- BUILD: Compose app-owned target interaction router; gesture click/drag/scroll; focus and keyboard state; touch/gesture on-screen keyboard; isolated test workspace.
- KEEP INTACT but DEPRIORITIZE: Windows WPF host, native-input backend, pairing/mTLS, remote-input schemas, Sunshine/Moonlight/WebRTC studies.
- DO NOT ASSUME: native vehicle camera access, official parked signal, global Android input permission, Korean composition correctness or finalized scroll gesture.

## Suggested implementation order for agents
1. Read AGENTS.md, ADR-004, GESTURE_UI_INTERACTION_SPEC and VIRTUAL_KEYBOARD_SPEC; inspect actual Kotlin code, not just claims.
2. Implement a clearly labeled local DEMO Compose work surface and pure target/action router; no global dispatch.
3. Connect Phase 1 gesture states to deterministic click/drag handlers, then review scroll gesture mapping before adding it.
4. Design keyboard/IME strategy for Korean composing text; implement and test with focused app-owned fields.
5. Integrate lifecycle/official safety gate and verify PLEOS compatibility; UNKNOWN stays blocked for operational use.
6. Regression test existing Phase 1 and report changes; do not alter optional remote-Host security just to simplify the demo.

## Mandatory verification labels
IMPLEMENTED = code inspection; AUTOMATED PASS = command/output; USER-TESTED = explicit user result; NOT_RUN = unexecuted hardware/UI check; BLOCKED = required platform/safety proof unavailable. Never turn the old Phase 2's FAILED TLS test into a success. No compile or emulator tests were run as part of this documentation update.

## Document migration notes
Old docs/testing/PHASE2_HANDOFF.md is historical remote-Host evidence. Old ADR-003 remains historical technical decision; ADR-004 defines current *product* scope. Shared contracts/*.schema.json remain unmodified. For remote Host APIs, see docs/api/PHASE2_IMPLEMENTATION.md. For new local UI interaction, see the two new specs; there is not yet a new wire API.

## Completion criteria
Gesture cursor operates app-owned actionable targets (single click, drag, scroll); on-screen keyboard edits focused fields including tested Korean/English; touch and gesture alternatives both work; no other-app/Windows injection; lost tracking and unsafe states release or block; recorded tests and PLEOS emulator evidence. Production vehicle readiness needs independent SDK/safety verification.
