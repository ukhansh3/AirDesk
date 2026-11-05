# AirDesk session state machine (draft)

States: DISCONNECTED → PAIRING → WAITING_APPROVAL → CONNECTED → LOCKED / ERROR → DISCONNECTED.
CONNECTED is an authenticated transport state, not equivalent to remote input permission.
Input permission is derived independently from:
- verified parked and not UX-restricted,
- host user approved currently bound session,
- authenticated peer,
- foreground client,
- heartbeat fresh and valid sequence,
- input capability allowed by chosen transport.

Any unsafe vehicle or auth change → LOCKED immediately. Do not silently resume input on reconnect, and release held buttons/keys even if the network is lost.
Default startup is DISCONNECTED + safety UNKNOWN.
