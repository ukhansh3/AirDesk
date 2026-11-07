# AirDesk — Root Agent Rules

## Goal
**Current product requirement (ADR-004):** hands-free pointer and virtual keyboard interactions **inside AirDesk-owned PLEOS/Android Compose screens**. Phase 1 camera input is currently a Windows developer PC webcam, not a verified vehicle camera. The earlier Windows remote desktop/host/pairing/mTLS architecture is optional historical research, not a dependency of revised Phase 2. Preserve code, contracts and test evidence; do not rebrand historical Phase 2 as the new feature.

## Source of truth
Explicit user decisions > ADR-004 current product scope > root AGENTS.md > approved ADRs > docs/features and docs/api > contracts > module-level AGENTS.md. Review conflicts with the user instead of silently changing behavior.

## Module ownership
- gesture-engine/**: Python/OpenCV/MediaPipe
- windows-host/**: .NET/C# WPF host and (later) authorized input
- android-client/**: Kotlin/Compose, safety, connection UI
- streaming/**: Moonlight/Sunshine and WebRTC experiments
- contracts/** and docs/api/**: supervisor-owned protocol
- integration-tests/** and docs/testing/**: integration/QA
Do not modify another agent's area or shared schemas without coordination. Every PR reports feature IDs, tests, contract impact and unresolved risks.

## Desktop-workspace interaction clarification
- User requires **desktop-like full-size QWERTY on-screen keyboard**, NOT a mobile phone keypad. Required groups: function row, Ctrl/Alt/Shift, Esc/Tab, navigation and arrow keys, desktop shortcuts inside supported app-owned editors, plus Korean/English composition.
- User requires **computer-mouse-like** cursor with left/right click, drag and scroll on registered app-owned targets. Never infer that pointer visualization or candidate messages are already working UI actions.
- Do not invent camera gestures for right click or scroll; present options and request approval. Use explicit demo/touch fallback controls if needed.
- Keep the keyboard dock/responsive Work workspace layout in scope. Do not fake unsupported F1–F12/global shortcuts. No Windows remote input or OS-wide Android event injection.

## Input-first implementation rules
- Read ADR-004, docs/features/GESTURE_UI_INTERACTION_SPEC.md, docs/features/VIRTUAL_KEYBOARD_SPEC.md and docs/testing/PHASE2_REVISED_HANDOFF.md before new implementation.
- No global Android taps, Accessibility automation, external-app event injection or Windows SendInput for revised Phase 2. Dispatch only registered app-owned Compose actions and focused text-edit operations.
- The Phase 1 diagnostic WebSocket supplies *hints*, not trusted production input authority; explicitly label local interactive previews DEMO.
- Production Work input must fail closed while official PLEOS parked/UX signal or freshness is UNKNOWN; mocks cannot impersonate official vehicle evidence.
- Maintain original Windows Host as an isolated optional legacy subsystem without removing security checks. Never replace ADR-003 historical test findings or silently change canonical wire schemas.
- Do not claim Korean IME, scroll gesture, PLEOS camera, or revised Phase 2 completed until code and actual tests prove them.

## Absolute safety rules
- FAIL CLOSED: UNKNOWN, MOVING, UX_RESTRICTED, STALE or permission failure means no remote screen and no input.
- Block the Android UI/input immediately on unsafe transition, BEFORE awaiting any network response. Host independently releases held keys/buttons on timeout.
- No hidden vehicle APIs, root, reverse engineering safety restrictions, CAN injection, Accessibility abuse, OS privilege bypass or unapproved vehicle controls.
- No actual Windows SendInput without verified peer authentication, explicit host user consent and active vehicle safety grant.
- Initial ws:// localhost is synthetic/diagnostic only: never bind public 0.0.0.0 or relay real input through it.
- Webcam frames remain local by default. Do not store video, keystrokes or real location traces, and do not commit API keys, pairing tokens or secrets.

## Protocol
Shared schemas in contracts/ are canonical. Changes need schema tests and version migration. Coordinate domain [0,1], strictly increasing sequence, stale/out-of-order drop, explicit tracking.lost and input release.
Keep gesture JSON, real-time input, streaming video and REST management as SEPARATE channels. Do not repurpose the other Pleos Stay+ Realtime file transfer API.

## Engineering/CI
- Write pure deterministic state machine tests and contract fixtures before integration.
- Validate loss-of-tracking, stuck drag, unknown vehicle state, replay and connection failure.
- Build and run only tools available in the current environment. Do not claim a webcam/PLEOS/Windows build without actually running it.
- No dependency/version changes without review; no public deployment, database, external service or new Android permissions without approval.
- Keep mocks conspicuously labeled DEMO. A working simulator is not proof of production readiness.
- Git: main reviewed baseline; dev/feature branches for work. Never force push, hard reset or alter Pleos Stay+ without a separate request.

## Agent report
State task ID, changed paths, schema changes, exact tests/results, hardware not tested, safety impact, blockers, and proposed next step.
