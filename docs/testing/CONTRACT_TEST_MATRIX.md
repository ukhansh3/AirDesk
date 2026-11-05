
# AirDesk contract test matrix v0.1

## CI checks
- Every schema passes JSON Schema 2020-12 meta-validation.
- Positive fixture for every message type.
- Negative cases for missing envelope fields, unsupported version, unknown payload fields, missing required fields, invalid enum, numeric outside range.
- Invalid pairing code format, invalid HOST/VEHICLE side, missing grantId, invalid button, gesture tracking.lost without reason.
- Runtime/transport tests: diagnostic hello valid token succeeds, bad token rejected, input-event masquerading as hello rejected.
- Synthetic gestures from Python remain event hints and never cause Windows OS input.

## Future integration / hardware gates
| ID | Scenario | Expected |
| --- | --- | --- |
| CTRL-01 | valid diagnostic handshake | auth.accepted mode=diagnostic_only; visual cursor only |
| CTRL-02 | bad dev token or input on hello | rejected/closed, no control |
| CTRL-03 | out-of-order duplicate event | ignored; no duplicate press |
| PAIR-01 | malformed or expired code | reject; do not reveal whether devices exist |
| PAIR-02 | code disclosure attack | untrusted network client never receives pairing.offer code |
| PAIR-03 | forged HOST approval | reject against authenticated peer identity |
| SESS-01 | grant expired/revoked | stop stream/input; release held keys |
| INPUT-01 | held mouse during disconnect | release_all locally, host watchdog releases independently |
| INPUT-02 | wrong display or out-of-range coordinate | reject |
| SAFE-01 | unknown/moving/restricted | immediate local block before network |
| PLEOS-01 | real emulator with webcam | real frame→MediaPipe→Android read-only cursor |
| STREAM-01 | standalone/embedded client | report separately, measure real latency |

## Status vocabulary
SCHEMA_TESTED, UNIT_TESTED, PLATFORM_BUILD_VERIFIED, HARDWARE_VERIFIED, REVIEW_REQUIRED, NOT_RUN.
Green Python CI must never be treated as camera, WPF, Android or vehicle hardware proof.
