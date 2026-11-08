# Experimental ten-finger midair typing — handoff

Updated: 2026-11-08. Scope: isolated AirDesk-owned DEBUG DEMO only.
Status: **GREEN PC CAMERA REFERENCE IMPLEMENTED (EXPERIMENTAL) + AUTOMATED PASS; synthetic runtime preview visually reviewed; actual webcam readability/behavior and PLEOS/PC rendering NOT_RUN; operational vehicle input BLOCKED**.
This handoff describes repairs to the existing v0.2 prototype. The newly clarified fixed midair/spatial
key-location interaction is a separate design under review, not implemented functionality in this repair.

## Current delivery — approved single-camera reference

The user selected one RGB webcam as the first research path and requested a green keyboard over the
live PC camera image, with English/Hangul labels and clear interiors. The display uses all 81 existing
key rectangles and the mouse region; all 26 letter keys show both scripts. Green outline/glyph alpha
defaults to 0.4, with a default-ON checkbox and a 15–80% PC opacity control. Key/panel backgrounds
remain zero-alpha; there is no filled tinted keyboard plate over the hands.

This is composition into the resized PC preview after MediaPipe has received its input. It does not
alter raw landmarks, calibration/press features, the Android editor, or v0.1/v0.2 packets. No camera
frame is saved or sent over WebSocket. The existing absolute normalized image-space mapping remains;
the overlay does not deliver a world-space plane/contact classifier, HOME mapping or v0.3 profile.

Pillow `>=10,<14` in the camera/all optional extras is the reviewed Unicode/RGBA dependency. The
development environment already has Pillow 13.0.0. Windows' installed Malgun font is read locally,
not redistributed. Missing dependency/font support disables this optional display with a bounded
explanation and leaves the original recognition path available. See
[ADR-007](../decisions/ADR-007-SINGLE-WEBCAM-KEYBOARD-OVERLAY.md) for rationale and alternatives.

The final checks and synthetic visual review are recorded below. A synthetic image/alpha test is
distinct from actual webcam legibility, performance or spatial typing accuracy.

## What is implemented

The existing v0.2 prototype was built against the earlier approval for experimental calibration of
both hands and all ten fingers held forward in midair,
with a slightly elevated frontal camera, flex/downstroke and release inference, and a side mouse region.
This is different from the preserved single-index cursor with Pinch-selected screen keys.
The PC keyboard, its navigation/function/modifier groups and the side mouse area fit one surface;
small windows scale the entire surface instead of requiring keyboard paging.

The current update removes the need to click a capture button eleven times. **연결 → 자동 보정 시작**
starts one guided automatic sequence: five seconds to prepare, stable neutral sampling, then automatic
pose detection/sampling for ten prompted fingers. The user still performs the instructed motions;
timers alone do not manufacture calibration. Successful completion remains disarmed until the user
explicitly selects **타건 켜기**. The old `capture` command remains a compatibility/manual fallback,
not the default app workflow.
Calibrated features now include palm-relative MCP downstroke as well as finger folding, and neutral
rearm is per finger so one held modifier does not prevent another finger from becoming ready.
Synthetic regression tests cover these distinctions; actual comfortable typing remains unverified.

The user selected a **dedicated large keyboard window on the PC monitor for development testing**.
The PC camera panel now has **큰 키보드 창**. It opens a resizable 1200×500 read-only Tk window with
the complete 81-key layout, navigation/function/modifier groups, side mouse region, ten labeled colored
fingertip markers and estimated pressed-key highlights. Geometry matches the Android 1120×360 design
through a shared synthetic parity fixture; no layout or key IDs change between displays.

The headline states **DEMO · 누름 추정 / 실제 입력 상태는 Android 앱에서 확인**. These colors show
recognizer estimates, not confirmation that Android accepted a key or inserted text. This window
receives only bounded latest AIR state, no editor text, keystroke history, tokens or camera pixels.
It opens only when the user presses the button; the automatic test harness does not open it.
Disconnect/stop clears the display; an independent 500 ms local display watchdog also removes stale
dots/highlights. Closing/reopening does not restore an expired held estimate.

## Joint-dot and hover stabilization update

The user reported excessive movement of the camera's 21 joint dots while holding a hand nearly
still. This update stabilizes the displayed joint positions and outgoing AIR hover coordinates.
It does not change MediaPipe input/inference or raw `CameraSample`/`CameraHand` landmarks,
calibration feature/noise measurements, or press/release thresholds. A smoother outline does not
prove a more accurate hand estimate or a successful typing calibration.

- After raw coordinate/identity validity checks, each valid joint uses the shared bounded
  `StablePointFilter`: median window 3, elapsed-time EMA with a 60 ms time constant, and a normalized
  radial soft deadzone of 0.004. A sustained median jump greater than 0.25 restarts positional
  history; a gap greater than 750 ms also reseeds it. These are model settings, not measured latency.
- The PC webcam overlay applies that algorithm only when drawing dots. Existing image mirroring,
  resizing and annotations remain; detector inputs and yielded landmark samples are unchanged.
  The panel labels this **관절 표시: 떨림 안정화** rather than claiming raw inference was corrected.
- AIR applies the same coordinate algorithm after raw validation, including while unarmed and
  calibrating. Its payload carries stabilized fingertip hover positions to Android and the large PC
  keyboard. The PC keyboard performs its existing geometry projection without another filter or
  motion gain. Camera-overlay and AIR histories start separately, so transient outputs need not match.
- Pressed-key coordinates remain captured at the pre-stroke hover until release. The visible camera
  fingertip can therefore move while the pressed-key marker stays anchored. Classification features,
  calibration stability rejection and the existing side-mouse recognizer stay on their prior raw path.
- Missing/invalid/ambiguous hands, source loss and lifecycle/reset boundaries clear the affected
  coordinate history immediately. Filtering cannot repair a rejected raw hand or keep its old dots alive.

No schema, SDK, library or permission changes accompany this positional update. Hardware tuning and
before/after jitter, response delay and false/missed typing measurements remain unexecuted.

## Temporary source delay and calibration progress

The user reported automatic calibration moving from **2/11 back to 1/11**. The current recovery update
separates a temporary camera/inference delay from an invalid capture or a real connection change.
The **250 ms input-freshness limit is unchanged**.

- An otherwise valid, increasing-timestamp camera sample that arrives more than 250 ms late is
  dropped. Pending reads that exceed that window also pause input. The same authenticated socket,
  source session, calibration epoch and completed pose stages remain; a fresh empty/disarmed state
  replaces all fingertip/button/key ownership. Old coordinates are never re-enveloped as live input.
- Only the incomplete current sample window and neutral/held/XY state are cleared. If the user was
  already in a confirmed unarmed automatic calibration, fresh neutral observations let the same
  stage continue automatically. For example, a paused **LEFT_THUMB / 2/11** stays at that stage,
  then can advance to **LEFT_INDEX / 3/11** after a valid thumb sample. Typing never rearms automatically.
- Android releases local input on timeout or a stale-but-otherwise-valid frame. It retains automatic
  calibration intent/progress only for the confirmed unarmed calibration in the same session/epoch.
  A retained progress caption contains no cached fingertips, pressed keys, arm grant or local input consent.
- Malformed messages, invalid/future/nonincreasing source timestamps, explicit cancel/disarm/reset,
  actual disconnect, changed authenticated session/epoch, background and safety lock retain their
  fail-closed cancellation/reset behavior. A new connection does not inherit the old calibration or arm.
- A progress caption may show the last confirmed stage while live data is absent. Read it as paused
  progress, not current hand recognition. Finger/key/mouse displays still clear immediately or on
  their existing watchdog. Fresh data must satisfy all original gates before any local action.

No wire/schema, SDK or permission change is involved. This preserves calibration work during a soft
delay; it does not accept stale input or weaken authentication, ordering, foreground or vehicle gates.

## Interpreting detected hands and valid input

The user also reported that two hands appeared detected while calibration or typing rejected them.
The PC tracking caption now says **카메라 손 N개 감지 · 타건 유효 여부는 앱에서 확인**. Read the
counts at their own pipeline stage:

The Run-AirDesk panel now puts **미리보기 감지 N · 관절 표시 N · Guard: ...** outside the camera image,
with **타건 유효 여부는 Android 앱에서 확인**. Read Guard when the counts differ. The standalone OpenCV
preview retains its embedded **Preview only | detected N | drawn N** captions. **OK for drawing only**
confirms only the overlay's eligibility checks, not accepted typing.

| Indicator | Meaning |
| --- | --- |
| Camera raw hand detections | MediaPipe returned that many hand observations. This does not establish stable left/right identity, usable geometry or input readiness. |
| Locally displayed overlay hands / rejection reason | The PC drawing layer applied its existing identity/coordinate checks and stabilization. Its raw/displayed counts and reason captions are local diagnostics, not a new wire field or an input permit. |
| AIR valid fingertips / app status | The AIR source and app applied their own raw-shape, calibration, freshness, mode and local-focus gates. The app's **손끝 / 누름 / 입력 키 · 상세** shows these later stages. |

Possible rejection gates include unknown/duplicate handedness, low handedness-classification score,
invalid/out-of-range points, a raw wrist jump, ambiguous hand overlap/order, or unusable finger/palm
geometry. The handedness score is not a landmark-accuracy rating. These are possible diagnostics;
the actual cause for the user's webcam has not been established by a hardware test. No thresholds
were relaxed to turn a raw count of two into a claim of valid typing.

The current key-selection mapping is still **absolute camera-image position → displayed key**.
The user reported fatigue from moving fingertips to search for keys. The earlier HOME-relative and
hand-follow proposal was approved, then superseded by the clarification that key locations should
stay fixed in midair, using a laser-projection keyboard image only as a visual reference. **The vehicle
has no flat typing surface. A desk/paper keyboard, surface projection and moving HOME keyboard are
not the selected solution.** The intended fixed keyboard does not follow moving hands.

The user has now selected a visibly experimental **single-RGB-webcam estimate first**. A measured
depth/tracking design is only a future fallback if measurements show the webcam approach is inadequate.
See [SPATIAL_AIR_KEYBOARD_DESIGN.md](../features/SPATIAL_AIR_KEYBOARD_DESIGN.md) for that selected
experiment, its current image-space green reference and the still-unimplemented fixed-world sensing
goals. Neither HOME/hand-follow nor measured fixed-plane press sensing is implemented here.

The v0.3 app-mode proposal was approved but remains deferred until the coordinate/input model is concrete.
This delivery adds no v0.3 profile or schema and keeps the current v0.2 mapping. Repeating calibration
does not replace the current absolute mapping or provide the newly requested fixed key space.

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
- The source drops delayed increasing-timestamp samples and emits only empty/disarmed pause state;
  malformed, future, repeated or regressing timestamps close the source. The client releases input on
  rejected/absent data, preserves only eligible same-session unarmed calibration metadata on transient
  timeout/stale-valid frames, and cancels calibration on malformed messages or real disconnect.
  Input is independently released if accepted frames stop arriving for 250 ms. These transport checks
  are stricter than the recognizer's internal stale-hand window; they are not measured hardware latency.
  The sample timestamp is PC monotonic time taken after a successful camera read/mirror and before
  inference, not a verified sensor exposure timestamp. Its age covers subsequent inference/queue delay;
  it does not measure blocking time inside the camera read or prove sensor freshness.
- Disarm/reset, loss or ambiguity, calibration changes, disconnect, app background/window-focus loss,
  unsafe state and stale data clear owned keys/buttons. A temporary unarmed same-session pause may
  retain completed calibration poses; reconnection does not automatically resume calibration or typing.

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
   Press **큰 키보드 창** and enlarge/arrange this PC window **before** connecting or starting calibration
   in Android. It can be opened before the camera starts; **AIR 데이터 대기** is expected until the AIR
   client connects. Leave the PC window visible and keep the Android app foreground during the
   hands-free calibration/typing flow. Actual host/emulator window-focus behavior remains unverified.
3. In the app, select **양손 열손가락 → 연결 → 자동 보정 시작** once. Put both hands in view during the
   five-second preparation period, then keep the comfortable neutral pose stable. The recognizer
   requires at least six valid frames spanning 0.5 seconds; motion, missing hands or ambiguous identity
   must not let a blind timer advance the sequence.
4. Follow the visible **1/11 … 11/11** guide. After neutral, the order is left thumb, index, middle,
   ring, little, then right thumb, index, middle, ring, little. Before each finger sample, return both
   hands to the neutral pose for 700 ms. When prompted, move only the named finger downward like
   pressing a key and hold briefly; a separable stable 0.5-second pose is sampled automatically. There
   is no per-stage button. An unstable/coupled pose stays on that stage for correction. **보정 취소**
   stops calibration and remains disarmed. Samples are feature vectors held in memory, not saved video.
   The large PC window follows the current prompt automatically; do not click or switch to it for each pose.
   If **카메라 신호 일시 대기** appears, keep the hands visible and return them to neutral. A temporary
   same-connection pause must retain the completed stage rather than restarting at 1/11; all old dots
   and press highlights must disappear while data is missing. A real reconnect still requires new calibration.
5. In preview, confirm ten distinct markers follow the intended fingers and hovered keys. Hover and
   holding a flexed finger before arming must not type. Reposition or recalibrate if markers drift.
6. When **보정 완료 · 11/11** appears, select **메모 입력 → 타건 켜기**. Return fingers to the neutral pose,
   then flex and release one finger at a time over a hovered key.
   Each deliberate downstroke should produce one local key activation, with no hold-repeat. Test
   thumb Space, letters, Backspace and Korean/English editing with synthetic text.
7. Test each finger separately and alternating hands. Test Shift plus a letter, Ctrl+A/C/V/X/Z only
   inside the selected editor, two fingers targeting the same key and simultaneous ordinary keys.
   Held modifier ownership must release when its owning finger disappears or the mode changes.
8. Move the right wrist into the camera image's right-side mouse region (the same normalized region
   drawn beside the keyboard). Its typing ownership must release before mouse
   input, and transition requires neutral rearm. Test approved index/Pinch mouse behavior and explicit
   **왼쪽 클릭 / 우클릭 1회 / 스크롤 모드** controls. Keep Pinch released during the 300 ms mouse-entry
   dwell, then test click/drag. Return to keyboard region and verify neutral rearm before typing.
9. Remove one finger/hand, obscure or cross hands, then separately disconnect, background/unfocus the
   app, disarm and reset calibration. Check no stuck key/button, delayed phantom press or automatic
   typing resume. Separately distinguish temporary unarmed calibration pause from actual reconnect.
   A real hand-order swap must not make the other hand inherit held ownership.
10. Press **타건 끄기**, resize/rotate and rerun **검지·Pinch** and **Phase 1 진단** separately. Preserve
   their old cursor and failure cleanup. Changing profile/disconnecting cannot restore previous arm.
   Check safety UNKNOWN continues to block operational Work input.

### If no text appears

After calibration the app displays **손끝 / 누름 / 입력 키 · 상세**. Select **상세** to inspect each
finger's local state. **손끝** counts valid observations, **누름** counts recognizer press estimates,
and **입력 키** counts keys currently owned by the local editor controller. None measures typing accuracy.

- **입력 꺼짐**: explicitly select **타건 켜기**. Completed calibration alone does not arm text entry.
- Missing fingertips: bring both hands fully into view; calibration waits for valid distinct observations.
- **펴기**: return that finger to its calibrated neutral pose before another downstroke.
- **키 밖**: move its visible marker over an actual key, rather than a gap or panel margin.
- **미지원**: choose a supported key; F2–F12 are intentionally unavailable.
- **누름 0** while fingertips move: a deliberate stroke has not been recognized. Recheck the guided
  pose and recalibrate; visible landmarks do not prove the classifier recognizes typing.
- Press estimates with no character: check the selected editor and local status. Shift/Ctrl and
  navigation keys can change state without inserting a printable character.

## Record actual measurements

For every hardware check record PASS/FAIL/NOT_RUN, camera/model/version, emulator version, app
content size/orientation, posture, lighting and calibration attempt count. Measure false/missed
presses per finger, simultaneous-key recognition, text accuracy, latency and fatigue. Do not store
actual typed text, passwords, frames or identifiable hand recordings in the repository.
Also verify manual open/close, resizing, key/marker alignment, local stale clearing and multi-window
focus with the large PC display. Its mock-Tk tests are not proof of actual Windows font/DPI rendering.
For the stabilization update, first watch the camera dots with typing disarmed: hold still, move
slowly and then deliberately move farther. Separately check the AIR keyboard markers after connection,
including loss/reacquisition and a pressed-key capture. Record residual jitter and lag independently
from raw calibration success and actual key recognition; no measured improvement is claimed yet.

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
with `PYTHONPATH` pinned to this checkout's `gesture-engine/src`: a previous manual-calibration targeted run passed
**9 loopback integration tests, exit 0, 13.043 seconds**. These exercise real WebSocket authentication/denial, strict hello/commands,
current-session/order/freshness checks, failed calibration without samples, reset/reconnect, v0.1 route
compatibility and exclusive camera-profile ownership/release using a fake generator. All listeners use
isolated ephemeral loopback ports; none contacts the user's running server or opens a real webcam.
That historical ninth test originally closed all out-of-window captures. Its old-age assertion is
superseded by the current split: delayed increasing samples pause without stale input; future,
repeated, regressing or malformed timestamps still close before an invalid estimate is published.

`scripts/verify-input-first.ps1` preserves its previous stages and adds a separate experimental AIR
contract stage; it records actual stage/test counts and leaves camera-typing accuracy as a manual gate.

### Previous automatic-calibration/PC-window baseline — historical result

The prior ordered harness for the automatic-calibration and PC-window update exited **0**. The
sanitized report is `out/input-first-verification/verification.json`, generated
**2026-11-07T06:07:23.3594993Z** (2026-11-07 15:07:23 KST). All code was frozen for this run.

| Stage | Actual result | Duration and limits |
| --- | --- | --- |
| Python regression and AIR models | **118 PASS** | 25.133 s; preserved Phase 1 tests plus automatic calibration, MCP stroke/per-finger rearm, server/camera boundaries, 13 headless PC preview tests, two latest-state runtime queue tests and source callback tests. No real webcam is opened. |
| Original canonical wire fixtures | **5 PASS / 17 fixtures** | 0.527 s; unchanged v0.1 compatibility and strict synthetic contract checks. |
| Experimental AIR wire fixtures | **7 PASS / 56 fixtures** | 0.513 s; existing v0.2 conformance and explicit semantic-guard distinctions. |
| Android JVM suite and `assembleDebug` | **156 tests / 19 suites PASS; build PASS** | 39.020 s; zero failures, errors or skipped tests. Includes two parity tests for all 81 key rectangles and the side mouse rectangle against the PC fixture. |

This is code/model/transport/build evidence. The headless mocked-Tk adapter tests do not prove actual
Windows window/font/DPI rendering; JVM tests do not prove PLEOS rendering/IME behavior. The large
PC window, camera recognition/typing accuracy, latency, comfort and multi-window focus require the
manual checks above. Actual keyboard text is verified in the Android editor, not inferred from PC colors.

### Previous manual-calibration baseline — historical result

The previous ordered harness exited **0**, with report generated **2026-11-07T05:36:45.7219015Z**
(2026-11-07 14:36:45 KST). These results covered the manual-calibration implementation, capture
freshness, invalid-wire cancellation, cross-hand ownership and camera-version boundaries. They are
not verification of the new automatic-calibration, MCP-stroke or per-finger-rearm edits made afterward.

| Stage | Actual result | Duration and limits |
| --- | --- | --- |
| Python regression and AIR models | **89 PASS** | 18.924 s; preserved 50-test base plus 39 new tests. Includes 19 AIR recognizer tests, camera/version/mirror models, source-freshness and nine real isolated-loopback tests. Camera suite now has 18 tests. No real webcam is opened. |
| Original canonical wire fixtures | **5 PASS / 17 fixtures** | 0.432 s; v0.1 compatibility and strict synthetic contract checks. |
| Experimental AIR wire fixtures | **7 PASS / 56 fixtures** | 0.375 s; new v0.2 shape/strict JSON/version isolation and explicit semantic-guard distinctions. |
| Android JVM suite and `assembleDebug` | **143 tests / 17 suites PASS; build PASS** | 48.203 s; zero failures, errors or skipped tests. These are model/build results, not rendered PLEOS UI/IME evidence. |

The earlier 80-test Python integration run was intermediate. The 89-test baseline superseded it;
tests were retained rather than removed to obtain a pass. Contract shape acceptance still does not
prove real finger recognition or authorize operational/global input.

### Automatic calibration and PC window — verified model coverage

The targeted real-loopback rerun of `test_air_server.py` passed **10 tests, exit 0, 15.793 seconds**
after adding automatic-start/cancel coverage. The added test observes a new disarmed COUNTDOWN epoch,
no advancement with missing hands, persistent WAITING after cancel, and rejection of arm before a
valid calibration. This is source/transport evidence, not actual pose-recognition accuracy.

The previous complete harness above covers one-start automatic progression, no advancement without
stable correct poses, cancel/disarm without automatic resume, manual-command compatibility,
MCP downstroke sensitivity and independent finger rearm/chords.
The versioned contracts remain unchanged by this automatic-calibration update.

The selected PC companion window is implemented in `keyboard_preview.py`, with the explicit button
hook in `desktop.py`. Its targeted headless suite passed **13 tests, exit 0, 0.046 seconds**: full
81-key metadata/parity, non-overlap and fit, normalized fingertip hit mapping, both-hand estimates,
invalid/disarmed/lost-state clearing, 500 ms expiry, automatic guide text and mocked Tk lifecycle/polling.
The shared fixture is `integration-tests/fixtures/pc-keyboard-geometry.json`; Android parity tests use
the same rectangles. No real Tk window, camera or emulator was launched by these tests.
The final complete run includes the PC callback/UI integration, including disconnect clearing and
display-callback isolation. Display estimates do not grant local editor, vehicle or OS input authority.

### Previous XY stabilization verification — historical result

The independent targeted integration module `test_air_stabilization_integration.py` passed **3 tests,
exit 0, 0.318 seconds**. Synthetic stationary XY noise was reduced in the actual AIR payload and passed
unchanged through the PC state model; mocked Canvas circles matched one design-space projection.
Small independent joint noise produced no Down in the calibrated armed fixture. Invalid raw observations,
loss and a new calibration epoch cleared/reseeded displayed state. These checks exercise source output
and the preview adapter, not a cosmetic display-only substitute, and do not measure hardware accuracy.

The final ordered harness for this positional update exited **0**, with sanitized report
`out/input-first-verification/verification.json` generated **2026-11-07T06:43:21.0927710Z**
(2026-11-07 15:43:21 KST). All stabilization code was frozen for this run.

| Stage | Current actual result | Duration and limits |
| --- | --- | --- |
| Python regression and AIR models | **136 tests newly executed, PASS** | 24.301 s; includes 8 shared filter tests, 31 AIR recognizer tests, 6 camera-overlay tests and 3 independent cross-pipeline tests. This retains the 118-test prior base and adds 18 regressions. No real webcam is opened. |
| Original canonical wire fixtures | **5 tests / 17 fixtures newly executed, PASS** | 0.390 s; unchanged v0.1 compatibility and strict synthetic contract checks. |
| Experimental AIR wire fixtures | **7 tests / 56 fixtures newly executed, PASS** | 0.364 s; unchanged v0.2 schema/profile conformance. |
| Unchanged Android JVM/build check | **Gradle UP-TO-DATE; prior 156 PASS / 19 suites retained** | 13.179 s; all 45 Gradle tasks were UP-TO-DATE. No JVM tests or APK compilation newly executed in this run. Existing JUnit records have zero failures, errors or skips; `assembleDebug` is an unchanged-artifact/build check. |

Android source/resources were unchanged by this Python/display stabilization update. Its earlier
executed 156-test result remains recorded in the historical baseline above; the current cached
Gradle result is not relabeled as 156 new test executions. Python and both contract suites did run
against the new code. Actual camera-dot improvement, visual comfort, response delay and typing accuracy
remain **NOT_RUN**. Headless overlay/Tk tests do not establish actual rendering or sensor accuracy.

### Previous temporary source delay recovery — historical verification

The updated real-loopback `test_air_server.py` suite passed **13 tests, exit 0, 33.251 seconds**.
The new regression uses the real engine, real automatic-calibration timers and synthetic two-hand
observations. It completes neutral to reach **LEFT_THUMB / 2/11**, introduces a 350 ms delay on an
otherwise increasing capture timestamp, observes empty/unarmed pause state with the same socket,
session, epoch and stage, then resumes neutral/thumb sampling to advance automatically to
**LEFT_INDEX / 3/11**. Explicit disarm then prevents the next neutral/index pose from advancing or arming.
No engine state is patched to fake progress. Hard future/repeated/regressing/malformed timestamp
rejection remains tested alongside the new soft-delay behavior.

Android source/policy changed in this recovery update, so the prior cached 156-test result remains
historical and is superseded by the executed reruns below. Hardware confirmation of the reported
2/11-to-1/11 symptom remains **NOT_RUN**. The contracts and the 250 ms input freshness limit are unchanged.

An intermediate recovery harness passed **141 Python tests, 172 Android tests / 20 suites, both
contract suites and assembleDebug**, exit 0. Further pending-arm/start/future-frame checks and local
raw-versus-overlay diagnostic captions were added afterward. That intermediate result is not the
final verification of those follow-up edits; the completed results follow.

The second complete harness then passed **145 Python tests, 5 canonical tests / 17 fixtures,
7 AIR tests / 56 fixtures, 180 Android tests / 20 suites and assembleDebug**, exit 0. Its report was
generated **2026-11-08T05:31:41.9036192Z** (2026-11-08 14:31:41 KST); the Android/build stage took
55.045 seconds. Two pending-start edge tests and their final Android correction followed that run;
only Android source/tests changed afterward, so the executed Python/contract results remain applicable.

| Current repair stage | Final actual result | Execution evidence |
| --- | --- | --- |
| Python regression and AIR models | **145 tests PASS** | 37.403 s, exit 0; complete run generated 2026-11-08T05:31:41.9036192Z. Python source unchanged after this execution. |
| Original canonical wire fixtures | **5 tests / 17 fixtures PASS** | 0.424 s, exit 0; same complete run. |
| Experimental AIR wire fixtures | **7 tests / 56 fixtures PASS** | 0.399 s, exit 0; same complete run. No schema changes. |
| Final Android JVM rerun and `assembleDebug` | **182 tests / 20 suites PASS; build PASS** | Approximately 45 s, exit 0, BUILD SUCCESSFUL; 8 tasks executed and 37 UP-TO-DATE. Zero failures, errors or skips. Last JUnit update 2026-11-08T05:37:44.408777Z (14:37:44 KST). |

The final Android-only rerun verifies the last pending-start correction; it is not a claim that all
145 Python tests were rerun at that later time. The local sanitized combined artifact is
`outputs/calibration-recovery-verification.json` outside Git, preserving each run's provenance.
During the Android rerun, access to a Kotlin daemon marker under AppData was denied. Gradle's automatic
non-daemon compiler fallback completed successfully; this warning did not fail the build or tests.

Those results concern the previous v0.2 recovery/diagnostics repair. They do not verify the current
green camera-reference update or the deferred
fixed-midair-keyboard/v0.3 design, actual webcam typing accuracy or new sensing hardware. No new
typing layout, v0.3 schema/route or OS-input authority is included.

No actual camera, emulator, GUI or OS input is launched by the automated harness. Historical Windows
Host/mTLS results remain separate; their previous failures are not converted to passes by AIR tests.

## Green camera reference — manual checks and final evidence

1. Close the old PC camera panel, then restart **Run-AirDesk.cmd** so the updated renderer/UI is loaded.
   Keep existing webcam/model/token settings. Start the camera and leave **카메라 키보드 표시** enabled.
2. Confirm green outlines for all 81 keys and the mouse area appear on the live image. Read English
   and Hangul on all 26 letter keys; check that letters are not empty boxes. F1–F12 and navigation/
   modifier groups must remain visible. The green drawing is a reference, not accepted key input.
3. Inspect the hands through the key interiors. Only border/glyph pixels should tint the image;
   no opaque or translucent filled background should cover whole keys or the keyboard panel.
4. Adjust **선 농도** from its default 40% toward 15% and 80%; toggle display off/on. Camera
   tracking, calibration and the local app input gate must keep their existing behavior. Adjust/arrange
   PC windows before hands-free calibration and keep the Android app foreground while testing input.
5. Resize the PC panel and verify the complete image fits its allocated preview (maximum 860×480),
   rather than cropping keys after controls take space. Quality/guard captions must remain visible
   **outside the video**, with function keys unobstructed. Record readability and draw cost/lag. Do not
   infer metric depth/contact or spatial-key accuracy from a pleasing overlay.
6. Check camera stop/restart, disconnect and panel exit; no delayed old preview should reappear.
   Separately rerun Android AIR and Phase 1 cursor checks to establish human regression evidence.
   Do not report those checks PASS until the user actually performs them.
7. If Pillow/font support was unavailable, fix that dependency/font, then toggle **카메라 키보드 표시**
   OFF and ON to retry. Moving **선 농도** alone must not repeatedly retry a failed font load. Existing
   camera recognition must keep running when the optional display is unavailable. The standalone
   OpenCV `--preview` retains its previous captions and has no new green camera-keyboard layer.

### Final current delivery results

The complete harness exited **0**, report generated **2026-11-08T06:10:11.4790962Z**
(2026-11-08 15:10:11 KST), after source freeze.

| Stage | Actual result | Duration and execution boundary |
| --- | --- | --- |
| Python regression and AIR models | **167 tests newly executed, PASS; zero skips** | 36.573 s; prior 145 tests plus 9 renderer and 13 runtime/preview integration regressions. The 22 new tests used actual local Pillow 13/Malgun/OpenCV with synthetic pixels. No camera or real Tk window opened. |
| Original canonical fixtures | **5 tests / 17 fixtures newly executed, PASS** | 0.387 s; unchanged v0.1 contracts. |
| Experimental AIR fixtures | **7 tests / 56 fixtures newly executed, PASS** | 0.372 s; unchanged v0.2 contracts. |
| Unchanged Android compile/JVM results and build check | **Prior 182 PASS / 20 suites retained; assembleDebug PASS** | 12.080 s; Android source unchanged. Compile/JVM tasks were UP-TO-DATE, so 182 tests were not newly executed. Packaging/build had 1 task executed and 44 UP-TO-DATE. Retained JUnit records have zero failures, errors or skips. |

The supervisor visually inspected the actual `DesktopRuntime._preview` synthetic artifact
`outputs/airdesk-camera-keyboard-final.png` at 860×480. All 81 keys and bilingual labels were legible,
interior backgrounds remained unchanged and guard captions no longer covered the function row.
This is a synthetic pipeline render review, not an actual webcam/Tk screenshot or typing test.

The new isolated Ubuntu headless CI job installs Noto CJK/Tk, Pillow and OpenCV-headless for real
Unicode/alpha tests and rejects skipped renderer tests. The remote GitHub job is **NOT_RUN** until
GitHub executes it after delivery; local Windows tests do not prove that remote job passed.

Actual webcam/GUI/Pillow-font readability, live resize/focus/performance and Android manual regressions
remain **NOT_RUN**. No new fixed-world press classifier, hologram or protocol profile is verified by
this display step.
