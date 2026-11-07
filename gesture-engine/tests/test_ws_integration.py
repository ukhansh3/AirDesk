
"""Real loopback WebSocket handshake tests; no Windows OS control."""
import asyncio
import contextlib
import json
import os
import socket
from time import monotonic
import unittest
from unittest.mock import patch

from airdesk_gesture.ws_server import serve
from airdesk_gesture.camera import CameraSample
from airdesk_gesture.synthetic import hand

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
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            self.port = probe.getsockname()[1]
        self.url = f"ws://127.0.0.1:{self.port}"
        self.server_task = asyncio.create_task(serve(source="synthetic", port=self.port))
        # Use an isolated loopback port; never contact a user's running dev server.
        for _ in range(40):
            if self.server_task.done():
                await self.server_task
            try:
                async with connect(self.url) as websocket:
                    await websocket.send(hello("incorrect-readiness-token-12345"))
                    await websocket.recv()
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
        async with connect(self.url) as websocket:
            await websocket.send(hello())
            reply = json.loads(await asyncio.wait_for(websocket.recv(), timeout=3))
            self.assertEqual(reply["type"], "auth.accepted")
            self.assertEqual(reply["payload"]["mode"], "diagnostic_only")
            event = json.loads(await asyncio.wait_for(websocket.recv(), timeout=3))
            self.assertEqual(event["type"], "gesture.move")
            self.assertGreater(event["sequence"], reply["sequence"])
            self.assertTrue(0 <= event["payload"]["x"] <= 1)

    async def test_server_refuses_non_loopback_bind(self):
        with self.assertRaises(ValueError):
            await serve(source="synthetic", host="0.0.0.0", port=9876)

    async def test_wrong_token_rejected(self):
        async with connect(self.url) as websocket:
            await websocket.send(hello("not-the-correct-token-123456789"))
            reply = json.loads(await asyncio.wait_for(websocket.recv(), timeout=3))
            self.assertEqual(reply["type"], "auth.rejected")
            self.assertEqual(reply["payload"]["errorCode"], "UNAUTHENTICATED")

    async def test_no_environment_token_uses_existing_app_settings(self):
        self.server_task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await self.server_task
        with patch.dict(os.environ, {"AIRDESK_DEV_TOKEN": ""}), patch(
            "airdesk_gesture.desktop_runtime.read_app_token", return_value=TOKEN,
        ) as load_token:
            self.server_task = asyncio.create_task(serve(source="synthetic", port=self.port))
            for _ in range(40):
                try:
                    async with connect(self.url) as ws:
                        await ws.send(hello())
                        reply = json.loads(await asyncio.wait_for(ws.recv(), 3))
                        self.assertEqual(reply["type"], "auth.accepted")
                    break
                except OSError:
                    await asyncio.sleep(.05)
            else:
                self.fail("server did not start using app configuration")
            load_token.assert_called_once()

    async def test_unexpected_remote_input_not_accepted_as_hello(self):
        bad = json.loads(hello())
        bad["type"] = "pointer.down"
        async with connect(self.url) as websocket:
            await websocket.send(json.dumps(bad))
            with self.assertRaises(Exception):
                await asyncio.wait_for(websocket.recv(), timeout=3)

    async def test_camera_preview_options_and_quit_release_session(self):
        self.server_task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await self.server_task
        def fake_frames(*args, **kwargs):
            now = int(monotonic() * 1000)
            yield CameraSample(now, hand(.3, .4, True), 1.0)
            yield CameraSample(now + 70, hand(.3, .4, True), 1.0)
            yield CameraSample(now + 140, hand(.6, .4, True), 1.0)

        with patch("airdesk_gesture.camera.landmark_frames", side_effect=fake_frames) as factory:
            self.server_task = asyncio.create_task(serve(
                source="camera", model="fake.task", device=2, preview=True, port=self.port,
            ))
            for _ in range(40):
                try:
                    async with connect(self.url) as ws:
                        await ws.send(hello("incorrect-readiness-token-12345"))
                        await ws.recv()
                    break
                except OSError:
                    await asyncio.sleep(.05)
            async with connect(self.url) as ws:
                await ws.send(hello())
                events = []
                while not events or events[-1]["type"] != "session.closed":
                    events.append(json.loads(await asyncio.wait_for(ws.recv(), 3)))
            factory.assert_called_once_with("fake.task", 2, mirror=True, preview=True)
            kinds = [e["type"] for e in events]
            self.assertIn("gesture.drag_start", kinds)
            self.assertEqual(kinds[-3:], ["gesture.pinch_end", "tracking.lost", "session.closed"])
            self.assertEqual(events[-1]["payload"]["reason"], "USER_REQUEST")
            self.assertEqual([e["sequence"] for e in events], list(range(len(events))))
            self.assertEqual([e["timestampMs"] for e in events], sorted(e["timestampMs"] for e in events))


if __name__ == "__main__":
    unittest.main()

