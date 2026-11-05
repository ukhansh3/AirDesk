# Control channel — proposed lifecycle

CTRL-01 connect, CTRL-02 authenticate peer, CTRL-03 start session,
CTRL-04 host approval, CTRL-05 heartbeat and state, CTRL-06 revoke,
CTRL-07 release all input, CTRL-08 disconnect.

Initial debug path only checks a long local token against AIRDESK_DEV_TOKEN.
Its auth.accepted means diagnostic cursor messages are permitted. It does not prove vehicle safety or authorize PC control.
A future production control channel must bind peer identity, session expiry, host approval, current vehicle gate, sequence/replay window and explicit fail-close watchdog.
