# Vehicle safety gate

SAFE-01 supported vehicle capability; SAFE-02 verified P/speed source; SAFE-03 freshness; SAFE-04 UX restrictions; SAFE-05 foreground lifecycle;
SAFE-06 local synchronous lock; SAFE-07 best-effort host release; SAFE-08 session expiry; SAFE-09 re-verification on resume; SAFE-10 E2E rejection tests.

Internal states: PARKED_CONFIRMED, DRIVING, UNKNOWN, UX_RESTRICTED, UNSUPPORTED.
Default UNKNOWN. CanRemoteControl = parked confirmed AND platform unrestricted AND foreground AND authenticated session AND host user-approved grant.
Diagnostics may show a read-only mock cursor even when UNKNOWN, but must label it DEMO and must not invoke Windows OS input.
A successful Android build is not verification of a real PLEOS safety capability.

## Revised in-app interaction (ADR-004)
Current CanRemoteControl refers to historical remote-Host authority, not app-owned UI actions. For operational in-vehicle AirDesk work input, a separate official PLEOS parked/UX/lifecycle gate is still required and must fail closed on UNKNOWN, DRIVING, UX_RESTRICTED, UNSUPPORTED or stale signals. Isolated clearly labeled DEMO target interactions may be exercised with synthetic input while UNKNOWN, but may not invoke production Work actions or claim real parked verification. Do not assume access to a vehicle camera or verified safety API.
