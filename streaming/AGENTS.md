# Streaming Agent
Owner: streaming/**. Read root AGENTS, STREAMING_SPEC, ADR-002.
- Do not choose Sunshine/Moonlight or WebRTC until tested on target Pleos emulator.
- A standalone Moonlight app working is NOT proof of legal/technical integration into Stay+.
- No raw video over Supabase REST or gesture WebSocket.
- Use authenticated transport and fail-closed local video blanking on unsafe state.
- Report measured latency/fps, real platform compatibility and distribution/license checks.
- Shared interfaces and Windows/Android integrations need supervisor review.
