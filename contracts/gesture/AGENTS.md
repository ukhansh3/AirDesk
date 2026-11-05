# Gesture Contract Agent
Own this contract with Gesture Engine agent and supervisor approval.
- gesture event is a POINTER HINT, not an authenticated Windows input command.
- x/y/confidence normalized [0,1]; reject non-finite, missing fields or unknown events.
- Preserve tracking.lost and pinch_end semantics to release held input.
- Coordinate version bump and fixture/tests with Android and Python consumers.
