# Control channel — proposed lifecycle

CTRL-01 connect, CTRL-02 authenticate peer, CTRL-03 start session,
CTRL-04 host approval, CTRL-05 heartbeat and state, CTRL-06 revoke,
CTRL-07 release all input, CTRL-08 disconnect.

Initial debug path only checks a long local token against AIRDESK_DEV_TOKEN.
Its auth.accepted means diagnostic cursor messages are permitted. It does not prove vehicle safety or authorize PC control.
The separate Phase 2 mutual TLS control channel binds peer identity, session expiry, host approval,
sequence/replay checks and an independent fail-closed watchdog. ADR-003 specifies the approved
design. The native input authority remains unavailable until actual vehicle evidence is verified;
the separate `/mock-input` simulation never supplies that authority. See PHASE2_HANDOFF for tests.
