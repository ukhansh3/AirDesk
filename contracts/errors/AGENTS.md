
# Shared Error Contract Agent

Scope: contracts/errors/**. Maintain approved error codes, safe user-facing messages, correlation IDs and retry policy.
Never include tokens, raw server stack traces, webcam images, personal locations, or keyboard input in error payloads.
Reject unexpected properties. Ensure client does not retry non-idempotent input blindly after errors.
Update contract tests and versioning before changing codes.
