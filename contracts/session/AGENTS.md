# Session Contract Agent
- Session status is a state projection, not a source of vehicle safety truth.
- Default UNKNOWN / DISCONNECTED; explicit host approval and fresh auth required.
- Revoked/expired/unsafe sessions cannot issue OS input.
- Never persist raw credentials or long-lived pairing code.
