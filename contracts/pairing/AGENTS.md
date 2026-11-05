
# Pairing Contract Agent

Scope: contracts/pairing/**. Follow root AGENTS.md and docs/features/PAIRING_SPEC.md.

Pairing credentials must be generated with approved cryptographic randomness and shown only on the trusted local host UI, not broadcast to unauthenticated clients. Remote user enters code out-of-band. Both devices must explicitly approve.
Do not trust `side` or `approved` merely because a JSON payload claims it: bind actions to authenticated peer identity.
Do not ship transport or host input activation until code TTL, attempts/rate limits, challenge, revocation, restart, and concurrent claims are tested. This schema is a proposed wire contract, not implementation approval.
