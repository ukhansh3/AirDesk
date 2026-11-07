# Revised AirDesk Phase 2 — documentation handoff

Date: 2026-11-07
Status: **IMPLEMENTED (code) + AUTOMATED PASS; actual PLEOS UI/gesture/IME NOT_RUN; operational vehicle input BLOCKED**.
Base: `dev` `f8a3374fe109cd72e54ecb6cda364ec8dfc932b0`. This handoff records revised input-first
work separately from the historical remote-Host Phase 2.

## Why the direction changed
The intended AirDesk product is physical-mouse/keyboard-free **PLEOS in-app** interaction, not a Windows remote desktop. Phase 1 webcam gesture detection and emulator cursor remain useful. Existing Phase 2 remote-Host code and its historical documentation are preserved as optional R&D, **not** accepted as revised Phase 2.

## Clarification for in-flight coding agents (2026-11-07)

**Do not implement a phone keypad.** The user's intent is a full **PC-like work keyboard** integrated into the PLEOS app: desktop-style QWERTY, modifiers, arrows, Delete, Esc/Tab, F1–F12 (honest disabled state if unsupported), Korean/English entry and app-supported shortcuts, docked near a usable work surface. Mouse-like left/right click, drag/drop and scroll are also requested for app-owned targets. No Windows OS/global Android injection.

The user approved the following app-local design choices in this chat:

- Scroll: explicitly turn on scroll mode, then hold Pinch and drag vertically over an eligible scroll target. Reuse existing gesture events; no schema change or new hand classifier.
- Right click: explicitly select the one-shot right-click control, then use the next Pinch click to open an eligible target's local context menu. Return to left-click mode afterward.
- Click: at least 80 ms Pinch, at most 12 dp movement, 250 ms repeat debounce. Existing Phase 1 drag-state events remain authoritative for drag candidates. Defaults require later human calibration.
- Keyboard: app-rendered desktop QWERTY with a directly implemented and tested two-set Korean composer. The visible desktop keyboard does not depend on a mobile phone keypad. Do not log text.

These approvals do not authorize production vehicle use, global Android/Windows input, another app's UI, a vehicle camera, or changes to the canonical wire contracts.

## Inventory
- REUSE: gesture-engine, its local debug WebSocket and Android read-only cursor; user reported Phase 1 camera/cursor success.
- IMPLEMENTED: Compose app-owned target interaction router; gesture click/drag/scroll/context actions; focus and keyboard state; touch/gesture desktop keyboard; isolated test workspace.
- KEEP INTACT but DEPRIORITIZE: Windows WPF host, native-input backend, pairing/mTLS, remote-input schemas, Sunshine/Moonlight/WebRTC studies.
- DO NOT ASSUME: native vehicle camera access, official parked signal, global Android input permission or actual PLEOS Korean/IME behavior. App-local scroll/right-click mappings above are approved; real ergonomics remain unmeasured.

## Suggested implementation order for agents
1. Read AGENTS.md, ADR-004, GESTURE_UI_INTERACTION_SPEC and VIRTUAL_KEYBOARD_SPEC; inspect actual Kotlin code, not just claims.
2. Implement a clearly labeled local DEMO Compose work surface and pure target/action router; no global dispatch.
3. Connect Phase 1 gesture states to deterministic click/drag handlers, then review scroll gesture mapping before adding it.
4. Design keyboard/IME strategy for Korean composing text; implement and test with focused app-owned fields.
5. Integrate lifecycle/official safety gate and verify PLEOS compatibility; UNKNOWN stays blocked for operational use.
6. Regression test existing Phase 1 and report changes; do not alter optional remote-Host security just to simplify the demo.

## Mandatory verification labels
IMPLEMENTED = code inspection; AUTOMATED PASS = command/output; USER-TESTED = explicit user result; NOT_RUN = unexecuted hardware/UI check; BLOCKED = required platform/safety proof unavailable. Never turn the old Phase 2's FAILED TLS test into a success. The original specification-only update had no implementation evidence; the executed checks below belong to this revised implementation. No emulator was launched.

## Document migration notes
Old docs/testing/PHASE2_HANDOFF.md is historical remote-Host evidence. Old ADR-003 remains historical technical decision; ADR-004 defines current *product* scope. Shared contracts/*.schema.json remain unmodified. For remote Host APIs, see docs/api/PHASE2_IMPLEMENTATION.md. For new local UI interaction, see the two new specs; there is not yet a new wire API.

## Completion criteria
Gesture cursor operates app-owned actionable targets (single click, drag, scroll); on-screen keyboard edits focused fields including tested Korean/English; touch and gesture alternatives both work; no other-app/Windows injection; lost tracking and unsafe states release or block; recorded tests and PLEOS emulator evidence. Production vehicle readiness needs independent SDK/safety verification.

## Automated verification evidence

| Group | Current result | Scope and limits |
| --- | --- | --- |
| Phase 1 Python regression | AUTOMATED PASS, 50 tests | Final ordered harness stage 6.488 seconds; no failures/skips. Fake camera and isolated loopback only. |
| Canonical wire fixtures | AUTOMATED PASS, 5 tests / 17 fixtures | Final stage 0.408 seconds. Existing schemas and strict parsing; not authority to dispatch UI or OS input. |
| Revised router/delivery/integration | AUTOMATED PASS, 41 JVM tests | Router 30 + diagnostic delivery 5 + cross-component callback integration 6. No rendered-device UI test. |
| Desktop keyboard/composer/editor | AUTOMATED PASS, 22 JVM tests | Composer 7 + controller/layout/editor 15. Supported local Korean model, not proof of native PLEOS IME equivalence. |
| Complete Android JVM suite | AUTOMATED PASS, 102 tests / 13 suites | Exact final JUnit XML: 0 failures, 0 errors, 0 skipped. Includes 39 preserved existing tests and 63 revised tests. |
| Android `assembleDebug` | AUTOMATED PASS | Combined JVM/build stage 58.805 seconds. Actual rendering/selection/IME still manual. |
| Actual PLEOS screen/touch/gesture/IME | NOT_RUN | User runs the emulator; no GUI/camera/emulator is launched by the automated harness. |
| Official parked/UX/camera capability | BLOCKED / NOT_VERIFIED | Default UNKNOWN cannot enable operational Work input. |
| Historical Windows Host TLS | Unchanged historical evidence | Not part of the revised required path. The old failure is not a revised Phase 2 pass. |

The final ordered `verify-input-first.ps1` run exited **0**. The report is
`out/input-first-verification/verification.json`, generated 2026-11-07; test totals were independently
recomputed from all JUnit XML. Its Android suite counts are 7+6+15+5+7+30+5+6+6+4+9+1+1=102.
An earlier 97-test JVM run had four mode-related failures. The mode handling was fixed and regression
coverage retained/extended; tests were not removed or ignored to obtain the final 102-test pass.

No WPF/Windows CNG/mTLS or native-input test is required or executed by this revised path. Existing
Windows source/tests, ADR-003 and historical PHASE2_HANDOFF remain preserved. A successful revised
harness does not change any old TLS failure to PASS.

## Implemented editing semantics and limits

- The keyboard is a six-row PC layout with a number row, QWERTY/punctuation, a function row,
  modifiers and a navigation/arrow cluster. F1 opens local help; F2–F12 are visible and disabled.
  Alt is visibly latched but Alt chords are unsupported in this demo and do not trigger global actions.
- Enter inserts a newline in the demo editors; it does not submit externally. Home/End act on logical
  lines, and Page Up/Down move eight newline-delimited logical lines by default, not a measured visual page.
  Backspace/arrows activate once per click; automatic hold-repeat is not implemented.
- Ctrl+A/C/V/X/Z, redo via Ctrl+Y or Ctrl+Shift+Z, and selection/navigation affect only the focused
  app-owned editor. Undo is per recorded edit; restored text is committed and does not resurrect a
  previously active composition. Modifiers reset on focus change, hide and suspension.
- The approved two-set Korean composer supports the tested combinations, compound vowels/finals,
  migration, component Backspace and selection edits, with composing ranges in TextFieldValue.
  This is a local composer, not an installed system IME or proof of all native Korean IME behavior.
  The phone IME is suppressed for these editors using the supported scoped Compose text-input interception.
- Gesture events are delivered without StateFlow conflation. A release candidate waits for the next
  valid hand move to confirm a click; `pinch_end` followed by `tracking.lost`, timeout, focus loss or
  screen exit cancels it. No automatic held action resumes after reconnect.
- The target registry uses measured/clipped bounds and current enabled metadata. Help/context menus
  occlude underlying targets. Selection uses a local app text toolbar rather than granting the cursor
  permission to operate Android popups. Actual touch-selection handles/toolbar behavior remains manual.
- Narrow/short windows keep desktop key sizing and expose horizontal keyboard pan plus vertical pan
  controls rather than replacing the layout with a phone keypad. Initial clipping/reachability and
  remaining workspace size must be checked on the actual PLEOS display.

### Run the revised harness

From this repository's root in PowerShell:

```powershell
.\scripts\verify-input-first.ps1 -PythonExe '.\gesture-engine\.venv\Scripts\python.exe' -GradleExe '<installed Gradle executable>'
```

Use `-JavaHome`, `-AndroidSdk`, and `-GradleUserHome` for existing local tooling/cache paths when needed.
Android Studio's bundled JBR and installed Gradle may be used; a Gradle wrapper is not bundled in this repository.
An external Python virtual environment may be passed: the harness pins `PYTHONPATH` to this checkout's
`gesture-engine/src`, so an editable installation of older AirDesk code does not supply the tested implementation.

The stages are Python regression → unchanged canonical fixtures → Android JVM tests and `assembleDebug`.
The report counts Android tests by reading **all `TEST-*.xml` JUnit suites**, including failures/errors/skips,
rather than trusting a manually reported total. Missing XML or no tests causes failure. A skipped stage/test is
not an overall pass. Exit codes are 0 for automated pass, 1 for failure, and 2 for incomplete/skipped verification.
`-SkipAndroid` is available for an explicitly partial run. Use the original `verify-phase2.ps1` only when
intentionally testing the optional historical remote Host; revised input-first use does not need its mTLS.

The default ignored result is `out/input-first-verification/verification.json`. It stores counts, stage status,
timing and manual gates, not text, keystrokes, tokens, private keys or complete tool output. The harness does
not start a camera, WPF host, emulator, native OS input, or a production vehicle-safety mock.

## User PLEOS emulator checklist

Record each item as PASS/FAIL/NOT_RUN with the emulator version, app content size/orientation and camera device.
Do not infer success from the code/build/previous Windows-host tests. Final button labels and supported actions
must be checked against the completed app before this checklist is marked executed.

1. Open `android-client/` in Android Studio, sync, build and run AirDesk on the user's PLEOS emulator. The optional Windows Host and pairing/mTLS are not prerequisites for this local workspace.
2. In the separate PC Phase 1 camera panel (`Run-AirDesk.cmd`), select the intended webcam/model and start the local diagnostic stream. Original camera frames remain on the PC. Use the existing local debug token/settings; do not start the optional Windows Host.
3. A debug build opens **데스크톱 DEMO** by default. Press **카메라 연결**, wait for diagnostic authentication, then explicitly press **손동작 켜기**. Connection alone does not enable target actions; repeat the explicit enable after reconnect. Check that operational Work remains locked with safety UNKNOWN. Verify measured app content bounds and system insets; the cursor must not control system bars or other apps.
4. Check the **desktop keyboard dock**: recognizable QWERTY rows, number/symbol row, Esc/Tab/Caps, Shift/Ctrl/Alt, Space/Enter/Backspace/Delete, arrows/navigation and visible F1–F12. F1 should open help, while F2–F12 remain disabled. Test **키보드 접기/키보드 펼치기**, refocus the same editor by touch, and confirm keys become eligible again. On small widths use **◀ 키보드/키보드 ▶**; on short heights use **키보드 ↑/키보드 ↓** to reach all rows. Resize/rotate while preserving usable workspace and reachable supported keys.
5. With touch first, activate a local button, focus each editor, drag an eligible object and scroll a local list. Context actions must appear in an AirDesk-owned menu. Confirm that drawing or hovering the cursor alone performs no action.
6. With the camera, hold Pinch for at least 80 ms and release without moving more than the click slop. One recognized release produces one action. Rapid bounce/replayed hints, releasing outside the captured target and losing the hand must not cause phantom clicks.
7. Drag an eligible object using the existing drag state; release to finish. Remove the hand while dragging, disconnect the stream and leave the app separately. Each case must cancel capture and prevent a later unintended click or resumed drag.
8. Select **스크롤 모드**, Pinch over the project list (including its visible rows) and drag vertically. Check direction, bounded speed and bounds at the list edges; **왼쪽 클릭** must restore normal click behavior. No new scroll gesture is assumed from an unapproved hand pose.
9. Select **우클릭 1회**, then Pinch-click an eligible editor. A local menu should open once and the mode should return to left click. The editor's **메뉴** button is the touch fallback. Check cancel/unsupported target behavior and prevent right-click mode leaking into unrelated text or another app. Long-press/select editor text and check the app-owned selection menu, native selection handles/popups and clipboard behavior without assuming headless tests covered them.
10. Focus a demo editor by touch and then by the gesture cursor. In Korean mode, enter `gksrmf` on the corresponding two-set keys and check `한글`; test `값`, compound vowels, final-consonant migration and Backspace within composition. Switch to English, enter mixed numbers/symbols, move/select the cursor and replace the selection. Check composing-region synchronization rather than append-only text.
11. Test Shift/Caps and meaningful local Ctrl+A/C/V/X/Z shortcuts with synthetic text. Ctrl+C/X/V use normal app/Android clipboard actions on the focused eligible editor; no background clipboard monitoring. Check arrow/Shift+arrow, logical Home/End/eight-line Page navigation, newline Enter and Delete/Backspace. Check per-edit undo/redo and single-activation behavior without automatic repeat. Unsupported F/global/Alt shortcuts must stay disabled or explicitly unavailable.
12. Hide the keyboard, change editor focus, background or unfocus the app, switch away and reconnect separately. Latched modifiers, pending clicks, capture and composition must reset/commit/cancel as documented; no hidden held key or old gesture may resume an action. Confirm the pointer remains visible above help/context overlays while enabled and cannot activate targets underneath them.
13. Re-run the original Phase 1 diagnostic cursor screen. Check camera choice, hand tracking and disconnect handling to establish human regression evidence. Record observed errors/latency; no FPS, delay or recognition-accuracy value has yet been measured for revised Phase 2.

## Remaining release gates

Manual PLEOS keyboard layout/gesture/IME evaluation, user calibration of thresholds and ergonomics,
and official parked/UX freshness/camera feasibility remain independent gates. Actual vehicle Work input
stays blocked until its evidence is verified. Stay+ integration and any global/remote input require a
separate approved task. The working tree used for revised development is isolated from the stopped legacy
Windows transport changes; no uncommitted concurrent work should be overwritten when delivering these files.
