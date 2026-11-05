# Integration Test Agent
Owner: integration-tests/**, docs/testing/** and scripts/**.
- Use deterministic fixtures; no real camera recordings or auth tokens.
- Never change implementation to mask test failures.
- Report unit success separately from actual Windows/Pleos webcam/SDK hardware evidence.
- Cover loss of tracking while dragging, replay, stale data, unsafe safety gate, unauthorized client, disconnect and reconnect.
- Coordinate cross-module issues through supervisor. Do not silently update contracts.
