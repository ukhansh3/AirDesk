# Experimental ten-finger midair typing — handoff

Date: 2026-11-07. Scope: isolated AirDesk-owned DEBUG DEMO only.
Status: **IMPLEMENTED (EXPERIMENTAL) + AUTOMATED PASS; actual webcam typing accuracy and PLEOS behavior NOT_RUN; operational vehicle input BLOCKED**.

## What is implemented

The user approved experimental calibration of both hands and all ten fingers held forward in midair,
with a slightly elevated frontal camera, flex/downstroke and release inference, and a side mouse region.
This is different from the preserved single-index cursor with Pinch-selected screen keys.
The PC keyboard, its navigation/function/modifier groups and the side mouse area fit one surface;
small windows scale the entire surface instead of requiring keyboard paging.

The approved recognition/profile design is documented in
[AIR_TYPING_SPEC.md](../features/AIR_TYPING_SPEC.md). Complete landmark-derived frames are
experimental hints, not proof of physical key contact or trustworthy vehicle safety. No Windows or
global Android input, another app's text, video transmission or camera recording is authorized.

## Channel and contract boundary

- Existing Phase 1 v0.1 diagnostic gestures and their schemas remain separate and compatible.
- Experimental AIR uses protocol `0.2`, the loopback-only `/air` WebSocket route, and the separate
  `contracts/air/air-messages.schema.json` contract. It uses the existing local development-token
  arrangement, not production device pairing or a remote-input grant.
- The envelope contains `protocolVersion`, `type`, `sessionId`, `sequence`, `timestampMs` and `payload`.
- A complete `air.frame` carries calibration epoch, stage/status/armed state, up to ten distinct
  `(hand, finger)` estimates and a separate mouse state. An omitted/invalid finger cannot retain an
  old press. Frames contain positions and estimated states, never typed text or camera pixels.
- Schema validity alone is insufficient: current authenticated session, ordering, freshness,
  calibration epoch, unique identities, consistent state and local explicit DEMO enablement are
  consumer checks. A stale/replayed frame or an invalid new frame must not resurrect held inputs.
- The source rejects stale, future or repeated camera-sample timestamps before re-enveloping a
  cached estimate as a fresh network frame. The client cancels/disarms on rejected wire data and
  independently releases input if accepted frames stop arriving for 250 ms. These transport checks
  are stricter than the recognizer's internal stale-hand window; they are not measured hardware latency.
  The sample timestamp is PC monotonic time taken before the camera read, not a verified sensor
  exposure timestamp. Its age includes read/inference/queue delay; the guard cannot prove sensor freshness.
- Disarm/reset, loss or ambiguity, calibration changes, disconnect, app background/window-focus loss,
  unsafe state and stale data clear owned keys/buttons. Reconnection does not automatically resume.

## Calibration and first human test

The following controls were checked against the implementation source. Their rendering and use on
the actual PLEOS emulator remain NOT_RUN. Do not substitute old v0.1 streaming for the AIR profile.

1. Run the rebuilt DEBUG AirDesk app on the user's PLEOS emulator. Open **데스크톱 DEMO**, then select
   **메모 입력** or **입력 시작**. Confirm **여기에 입력됩니다** and the complete PC keyboard plus side
   mouse area are visible together. Press the screen's **한 / 영**, then A; English mode should insert
   `a`. Use synthetic text. This touch check needs no camera connection.
2. On the PC, double-click `Run-AirDesk.cmd`; choose **카메라 번호** and the `.task` model if needed,
   then press **시작 · 카메라 + 앱 연결**. On a supported MediaPipe version it tracks up to two hands and exposes both
   diagnostic profiles. It automatically reads existing local development settings. Keep the preview
   visible, both hands in the neutral ghost-hands pose, and the intended webcam/model selected.
   Original frames stay on the PC. Only one camera profile may consume the stream at once.
   Two-hand AIR requires a known MediaPipe Tasks version at least 0.10.5 because the handedness
   convention differs. The development environment has 0.10.35. On unknown/older versions the PC panel
   warns and falls back to the preserved single-hand path; that fallback cannot satisfy AIR calibration.
   This is a checked version boundary, not proof of anatomical labeling on hardware.
3. In the app, select **양손 열손가락 → 연결 → 보정**. Press **자세 확인** for the neutral stage, return both
   hands to view during the **1.5-second delay**, then hold the requested pose during the following
   **0.5-second sample window**. Follow each on-screen prompt and repeat **자세 확인** for left thumb,
   index, middle, ring, little, then right thumb, index, middle, ring, little: eleven successful captures
   including neutral. **자세 확인** captures an in-memory landmark-feature sample, not saved video.
   Failed separation stays disarmed; retry that prompted pose. Missing/unstable samples cannot arm typing.
4. In preview, confirm ten distinct markers follow the intended fingers and hovered keys. Hover and
   holding a flexed finger before arming must not type. Reposition or recalibrate if markers drift.
5. When **보정 완료** appears, select **메모 입력 → 타건 켜기**. Return fingers to the neutral pose,
   then flex and release one finger at a time over a hovered key.
   Each deliberate downstroke should produce one local key activation, with no hold-repeat. Test
   thumb Space, letters, Backspace and Korean/English editing with synthetic text.
6. Test each finger separately and alternating hands. Test Shift plus a letter, Ctrl+A/C/V/X/Z only
   inside the selected editor, two fingers targeting the same key and simultaneous ordinary keys.
   Held modifier ownership must release when its owning finger disappears or the mode changes.
7. Move the right wrist into the camera image's right-side mouse region (the same normalized region
   drawn beside the keyboard). Its typing ownership must release before mouse
   input, and transition requires neutral rearm. Test approved index/Pinch mouse behavior and explicit
   **왼쪽 클릭 / 우클릭 1회 / 스크롤 모드** controls. Keep Pinch released during the 300 ms mouse-entry
   dwell, then test click/drag. Return to keyboard region and verify neutral rearm before typing.
8. Remove one finger/hand, obscure or cross hands, then separately disconnect, background/unfocus the
   app, disarm and reset calibration. Check no stuck key/button, delayed phantom press or automatic
   resume. A real hand-order swap must not make the other hand inherit held ownership.
9. Press **타건 끄기**, resize/rotate and rerun **검지·Pinch** and **Phase 1 진단** separately. Preserve
   their old cursor and failure cleanup. Changing profile/disconnecting cannot restore previous arm.
   Check safety UNKNOWN continues to block operational Work input.

## Record actual measurements

For every hardware check record PASS/FAIL/NOT_RUN, camera/model/version, emulator version, app
content size/orientation, posture, lighting and calibration attempt count. Measure false/missed
presses per finger, simultaneous-key recognition, text accuracy, latency and fatigue. Do not store
actual typed text, passwords, frames or identifiable hand recordings in the repository.

Ring/little-finger coupling, thumb geometry, occlusion, hand identity ambiguity and monocular depth
can prevent comfortable or reliable typing. Automatic synthetic tests cannot establish real typing
accuracy, pressure/contact detection or production readiness. Official PLEOS vehicle safety/camera
capabilities remain unverified; operational input remains blocked when safety is UNKNOWN.

## Automated evidence

The new synthetic contract catalog is
`integration-tests/fixtures/air-typing-wire.json`; its harness is
`integration-tests/test_air_typing_contracts.py`. On 2026-11-07, the QA agent executed
`python -m unittest discover -s integration-tests -p test_air_typing_contracts.py -v` using the existing
development environment: **7 tests / 56 synthetic fixtures PASS, exit 0, 0.086 seconds**. It validates
positive/negative shapes, strict JSON, v0.1/v0.2 isolation and the distinction between schema acceptance
and semantic authorization. It does not execute classifier/consumer state machines or prove hardware typing.

The QA agent also executed `python -m unittest discover -s gesture-engine/tests -p test_air_server.py -v`
with `PYTHONPATH` pinned to this checkout's `gesture-engine/src`: the final targeted rerun passed
**9 loopback integration tests, exit 0, 13.043 seconds**. These exercise real WebSocket authentication/denial, strict hello/commands,
current-session/order/freshness checks, failed calibration without samples, reset/reconnect, v0.1 route
compatibility and exclusive camera-profile ownership/release using a fake generator. All listeners use
isolated ephemeral loopback ports; none contacts the user's running server or opens a real webcam.
The added ninth test verifies stale, future and repeated capture timestamps close the source connection
before the estimate can receive a fresh wire envelope.

`scripts/verify-input-first.ps1` preserves its previous stages and adds a separate experimental AIR
contract stage; it records actual stage/test counts and leaves camera-typing accuracy as a manual gate.

### Final complete harness result

The final ordered harness exited **0**. Its sanitized report is
`out/input-first-verification/verification.json`, generated **2026-11-07T05:36:45.7219015Z**
(2026-11-07 14:36:45 KST). These results include the final capture-freshness, invalid-wire cancellation,
cross-hand ownership and camera-version-boundary corrections.

| Stage | Actual result | Duration and limits |
| --- | --- | --- |
| Python regression and AIR models | **89 PASS** | 18.924 s; preserved 50-test base plus 39 new tests. Includes 19 AIR recognizer tests, camera/version/mirror models, source-freshness and nine real isolated-loopback tests. Camera suite now has 18 tests. No real webcam is opened. |
| Original canonical wire fixtures | **5 PASS / 17 fixtures** | 0.432 s; v0.1 compatibility and strict synthetic contract checks. |
| Experimental AIR wire fixtures | **7 PASS / 56 fixtures** | 0.375 s; new v0.2 shape/strict JSON/version isolation and explicit semantic-guard distinctions. |
| Android JVM suite and `assembleDebug` | **143 tests / 17 suites PASS; build PASS** | 48.203 s; zero failures, errors or skipped tests. These are model/build results, not rendered PLEOS UI/IME evidence. |

The earlier 80-test Python integration run was intermediate. The 89-test final run supersedes it;
tests were retained rather than removed to obtain a pass. Contract shape acceptance still does not
prove real finger recognition or authorize operational/global input.

No actual camera, emulator, GUI or OS input is launched by the automated harness. Historical Windows
Host/mTLS results remain separate; their previous failures are not converted to passes by AIR tests.
