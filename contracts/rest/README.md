# HTTP REST endpoints — none currently approved

AirDesk Phase-0 deliberately has no Fastify service, Supabase DB, or OpenAPI HTTP routes. Local Python ↔ Android synthetic gesture diagnostics use WebSocket, and future real-time video requires a dedicated streaming protocol.

Do not invent POST /pairings, /sessions or /remote-devices merely because the product has those features. First decide:
- whether device discovery/remote signaling requires a server,
- mutual authentication and ownership,
- endpoint request/response/error schema,
- rate limits, retention, auth scopes and threat model,
- provider/runtime, deployment and maintenance costs.

Once explicitly approved: create contracts/rest/openapi.yaml with OpenAPI 3.1, tests and an ADR, then build code from the signed-off contract.
