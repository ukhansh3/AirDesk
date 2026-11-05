# AirDesk end-to-end test scenarios

1. Camera missing, busy, denied → no new gestures and no stuck input.
2. Pinch while hand disappears → release event and safe lock.
3. Invalid debug token → WS close, no stream.
4. Replay or late sequence → ignored; never repeated click.
5. UNKNOWN or DRIVING → immediate blocked UI without waiting for host.
6. Return to parked → require fresh user consent; do not auto-reconnect input.
7. PC host closes → all keys/mouse buttons released.
8. Long outage → stale data refused and visible error.
9. MediaPipe vs synthetic clearly labeled.
10. Integrating in Stay+ Work does not alter Parking/Waiting/Pickup/Realtime.
