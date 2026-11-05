# Pairing feature policy — DRAFT, NOT IMPLEMENTED

PAIR-01 to 08 correspond to message types in contracts/pairing/pairing-messages.schema.json. The exact backend/IPC transport is undecided.
1. Host owner explicitly requests a one-time 8-digit code with expiry; no background auto-pair.
2. Host displays the code **locally only**; it must not be sent to an unauthenticated remote client. Android user transcribes the code, shows human-readable device identity and waits.
3. Host and Android user must both approve named devices. Validate the authenticated sender identity; never trust a supplied `side` field by itself. One approval is insufficient.
4. Connection identity is established only after both approvals; it does not grant pointer/keyboard privileges.
5. Session grant is a separately approved, scoped, expiring authorization.
6. Revoke invalidates connection/session across peer and releases held inputs.
7. Enforce max attempts, expiry, single active claimant and defensive recovery from concurrent claims.
8. Reconnecting must not silently restore previous input privileges.

Never log token or pair code. Use security-reviewed, standard cryptography for the actual transport. Develop Phase-0 diagnostics without pretending the dev token is a pairing mechanism.
Test cancel/revoke/expired/wrong code/attempt flood/duplicated approval/offline peer/restart/new device.
