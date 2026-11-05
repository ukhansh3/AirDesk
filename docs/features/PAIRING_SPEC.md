# Device pairing and security (DRAFT)

PAIR-01 start attempt; PAIR-02 fresh short-lived code; PAIR-03 host confirmation; PAIR-04 vehicle confirmation; PAIR-05 mutually authenticated encrypted session; PAIR-06 revoke; PAIR-07 rate limit and expiry; PAIR-08 reconnect reauthorization.
The synthetic debug token supplied via AIRDESK_DEV_TOKEN is NOT pairing or proof of identity.
No shared permanent code, anonymous cross-user access, cloud session state or unauthenticated host input.
Produce cryptographic design/ADR and threat review before implementing pairing; do not reuse existing Pleos Stay+ Pickup QR or file transfer bearer credentials.
