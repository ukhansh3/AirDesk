
"""Real loopback WebSocket handshake tests; no Windows OS control."""
import asyncio
import contextlib
import json
import os
import unittest
from unittest.mock import patch

from airdesk_gesture.ws_server import serve

try:
    from websockets.asyncio.client import connect
except ImportError:
    connect = None

TOKEN = "local-only-integration-test-token-12345"


def hello(token=TOKEN):
    return json.dumps({
        "protocolVersion": "0.1", "type": "auth.hello",
        "sessionId": "diagnostic-test", "sequence": 0, "timestampMs": 123,
        "payload": {"token": token},
    })


@unittest.skipIf(connect is None, 'Install [ws] extra to run integration tests')
class DiagnosticWebSocketTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.env = patch.dict(os.environ, {"AIRDESK_DEV_TOKEN": TOKEN})
        self.env.start()
        self.server_task = asyncio.create_task(serve(source="synthetic"))
        # The test server binds ONLY loopback on the fixed local diagnostic port.
        for _ in range(40):
            if self.server_task.done():
                await self.server_task
            try:
                reader, writer = await asyncio.open_connection("127.0.0.1", 8765)
                writer.close()
                await writer.wait_closed()
                break
            except OSError:
                await asyncio.sleep(0.05)
        else:
            raise RuntimeError("debug WebSocket did not start")

    async def asyncTearDown(self):
        self.server_task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await self.server_task
        self.env.stop()

    async def test_valid_hello_only_grants_visual_diagnostics(self):
        async with connect("ws://127.0.0.1:8765") as websocket:
            await websocket.send(hello())
            reply = json.loads(await asyncio.wait_for(websocket.recv(), timeout=3))
            self.assertEqual(reply["type"], "auth.accepted")
            self.assertEqual(reply["payload"]["mode"], "diagnostic_only")
            event = json.loads(await asyncio.wait_for(websocket.recv(), timeout=3))
            self.assertEqual(event["type"], "gesture.move")
            self.assertTrue(0 <= event["payload"]["x"] <= 1)

    async def test_wrong_token_rejected(self):
        async with connect("ws://127.0.0.1:8765") as websocket:
            await websocket.send(hello("not-the-correct-token-123456789"))
            reply = json.loads(await asyncio.wait_for(websocket.recv(), timeout=3))
            self.assertEqual(reply["type"], "auth.rejected")
            self.assertEqual(reply["payload"]["errorCode"], "UNAUTHENTICATED")

    async def test_unexpected_remote_input_not_accepted_as_hello(self):
        bad = json.loads(hello())
        bad["type"] = "pointer.down"
        async with connect("ws://127.0.0.1:8765") as websocket:
            await websocket.send(json.dumps(bad))
            with self.assertRaises(Exception):
                await asyncio.wait_for(websocket.recv(), timeout=3)


if __name__ == "__main__":
    unittest.main()
