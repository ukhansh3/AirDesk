"""Real isolated-loopback AIR handshake/command tests; no webcam, GUI or OS input."""
from __future__ import annotations

import asyncio
from contextlib import suppress
import json
from pathlib import Path
import socket
from time import monotonic, sleep
import unittest

from jsonschema import Draft202012Validator
from airdesk_gesture.camera import CameraSample
from airdesk_gesture.ws_server import serve

try:
    from websockets.asyncio.client import connect
    from websockets.exceptions import ConnectionClosed
except ImportError:
    connect = None


TOKEN = "synthetic-air-server-test-token-000001"
ROOT = Path(__file__).resolve().parents[2]


def hello(token=TOKEN, version="0.2"):
    return {"protocolVersion": version, "type": "auth.hello", "sessionId": "synthetic-air-client",
            "sequence": 0, "timestampMs": int(monotonic() * 1000), "payload": {"token": token}}


@unittest.skipIf(connect is None, "Install [ws] extra to run loopback AIR tests")
class AirServerIntegrationTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            self.port = probe.getsockname()[1]
        self.url = f"ws://127.0.0.1:{self.port}"
        self.validator = Draft202012Validator(json.loads(
            (ROOT / "contracts/air/air-messages.schema.json").read_text(encoding="utf-8")))
        self.server_task = None
        await self.start_server()

    async def start_server(self, **options):
        if self.server_task is not None:
            self.server_task.cancel()
            with suppress(asyncio.CancelledError):
                await self.server_task
        self.server_task = asyncio.create_task(serve(source=options.pop("source", "synthetic"),
            port=self.port, dev_token=TOKEN, **options))
        # Probe this test's own ephemeral port; never contact a user's local dev server.
        for _ in range(50):
            if self.server_task.done():
                await self.server_task
            try:
                async with connect(self.url + "/air") as ws:
                    await ws.send(json.dumps(hello("synthetic-wrong-air-token-000001")))
                    reply = json.loads(await asyncio.wait_for(ws.recv(), 2))
                    self.assertEqual(reply["type"], "auth.rejected")
                return
            except OSError:
                await asyncio.sleep(.025)
        self.fail("Isolated AIR loopback listener did not start")

    async def asyncTearDown(self):
        if self.server_task is not None:
            self.server_task.cancel()
            with suppress(asyncio.CancelledError):
                await self.server_task

    async def authenticate(self, ws):
        await ws.send(json.dumps(hello()))
        reply = json.loads(await asyncio.wait_for(ws.recv(), 2))
        self.validator.validate(reply)
        self.assertEqual(reply["type"], "auth.accepted")
        self.assertEqual(reply["payload"], {"mode": "air_typing_demo"})
        return reply

    async def test_local_preview_only_receives_authenticated_estimates_and_disconnect_clear(self):
        updates = []
        await self.start_server(on_air_frame=updates.append)
        self.assertEqual(updates, [])  # Readiness rejection must not publish or clear another profile.
        async with connect(self.url + "/air") as ws:
            await self.authenticate(ws)
            await self.frame_until(ws)
            self.assertTrue(any(isinstance(item, dict) for item in updates))
            self.assertEqual(set(updates[-1]), {"epoch", "stage", "status", "armed", "fingers", "mouse"})
            self.assertNotIn("token", json.dumps(updates))
            async with connect(self.url + "/air") as denied:
                await denied.send(json.dumps(hello("synthetic-wrong-air-token-000001")))
                self.assertEqual(json.loads(await denied.recv())["type"], "auth.rejected")
            self.assertNotIn(None, updates)
        for _ in range(30):
            if updates and updates[-1] is None:
                break
            await asyncio.sleep(.02)
        self.assertIsNone(updates[-1])

    async def test_local_preview_exception_cannot_break_authenticated_stream(self):
        def broken(_):
            raise RuntimeError("synthetic display failure")
        await self.start_server(on_air_frame=broken)
        async with connect(self.url + "/air") as ws:
            await self.authenticate(ws)
            event = await self.frame_until(ws)
            self.assertFalse(event["payload"]["armed"])

    async def frame_until(self, ws, predicate=lambda _: True, timeout=3):
        deadline = monotonic() + timeout
        while True:
            remaining = deadline - monotonic()
            self.assertGreater(remaining, 0, "Expected AIR state was not observed")
            event = json.loads(await asyncio.wait_for(ws.recv(), remaining))
            self.validator.validate(event)
            self.assertEqual(event["type"], "air.frame")
            if predicate(event["payload"]):
                return event

    async def send_command(self, ws, session, sequence, action, **changes):
        event = {"protocolVersion": "0.2", "type": "air.command", "sessionId": session,
                 "sequence": sequence, "timestampMs": int(monotonic() * 1000), "payload": {"action": action}}
        event.update(changes)
        await ws.send(json.dumps(event))

    async def assert_closed(self, ws, code=1008, allow_frames=True):
        async def consume():
            while True:
                raw = await ws.recv()
                if not allow_frames:
                    self.fail("Invalid hello received an application response")
                event = json.loads(raw)
                self.assertEqual(event["type"], "air.frame")
                self.assertFalse(event["payload"]["armed"])
        try:
            await asyncio.wait_for(consume(), 3)
        except ConnectionClosed as closed:
            self.assertEqual(closed.rcvd.code, code)
        else:
            self.fail("Invalid AIR request did not close its connection")

    async def test_v02_authentication_streams_schema_valid_complete_disarmed_frames(self):
        async with connect(self.url + "/air") as ws:
            accepted = await self.authenticate(ws)
            first = await self.frame_until(ws)
            second = await self.frame_until(ws)
            self.assertEqual(first["sessionId"], accepted["sessionId"])
            self.assertEqual(first["protocolVersion"], "0.2")
            self.assertLess(accepted["sequence"], first["sequence"])
            self.assertLess(first["sequence"], second["sequence"])
            self.assertLessEqual(first["timestampMs"], second["timestampMs"])
            self.assertEqual(first["payload"]["stage"], "NEUTRAL")
            self.assertFalse(first["payload"]["armed"])
            self.assertEqual(first["payload"]["fingers"], [])
            self.assertFalse(first["payload"]["mouse"]["pressed"])

    async def test_wrong_token_receives_rejection_and_policy_close(self):
        async with connect(self.url + "/air") as ws:
            await ws.send(json.dumps(hello("synthetic-unrecognized-token-000001")))
            reply = json.loads(await asyncio.wait_for(ws.recv(), 2))
            self.validator.validate(reply)
            self.assertEqual(reply["type"], "auth.rejected")
            self.assertEqual(reply["payload"], {"errorCode": "UNAUTHENTICATED"})
            await self.assert_closed(ws, allow_frames=False)

    async def test_old_version_extra_fields_duplicate_json_and_binary_hello_are_rejected(self):
        extra = hello(); extra["payload"]["nativeInput"] = True
        duplicate = json.dumps(hello()).replace('"sequence": 0', '"sequence": 0, "sequence": 1')
        messages = [json.dumps(hello(version="0.1")), json.dumps(extra), duplicate, json.dumps(hello()).encode()]
        for index, message in enumerate(messages):
            with self.subTest(case=index):
                async with connect(self.url + "/air") as ws:
                    await ws.send(message)
                    await self.assert_closed(ws, allow_frames=False)

    async def test_commands_require_current_session_order_and_fresh_clock(self):
        for fault in ("replay", "wrong-session", "stale", "future", "regressing-time", "extra-payload"):
            with self.subTest(fault=fault):
                async with connect(self.url + "/air") as ws:
                    accepted = await self.authenticate(ws)
                    session = accepted["sessionId"]
                    now = int(monotonic() * 1000)
                    await self.send_command(ws, session, 1, "disarm", timestampMs=now)
                    changes = {}
                    sequence = 2
                    if fault == "replay": sequence = 1
                    if fault == "wrong-session": changes["sessionId"] = "synthetic-wrong-source"
                    if fault == "stale": changes["timestampMs"] = max(0, now - 5000)
                    if fault == "future": changes["timestampMs"] = now + 5000
                    if fault == "regressing-time": changes["timestampMs"] = now - 1
                    if fault == "extra-payload": changes["payload"] = {"action": "arm", "text": "synthetic"}
                    await self.send_command(ws, session, sequence, "arm", **changes)
                    await self.assert_closed(ws)

    async def test_calibration_commands_cannot_arm_without_actual_samples(self):
        async with connect(self.url + "/air") as ws:
            accepted = await self.authenticate(ws)
            session = accepted["sessionId"]
            initial = await self.frame_until(ws)
            await self.send_command(ws, session, 1, "arm")
            failed = await self.frame_until(ws, lambda p: p["status"] == "FAILED")
            self.assertFalse(failed["payload"]["armed"])
            await self.send_command(ws, session, 2, "start_calibration")
            started = await self.frame_until(ws, lambda p: p["epoch"] != initial["payload"]["epoch"])
            self.assertEqual(started["payload"]["stage"], "NEUTRAL")
            self.assertFalse(started["payload"]["armed"])
            await self.send_command(ws, session, 3, "capture")
            countdown = await self.frame_until(ws, lambda p: p["status"] == "COUNTDOWN")
            self.assertFalse(countdown["payload"]["armed"])
            capture_failed = await self.frame_until(ws, lambda p: p["status"] == "FAILED", timeout=3)
            self.assertEqual(capture_failed["payload"]["fingers"], [])
            await self.send_command(ws, session, 4, "reset")
            reset = await self.frame_until(ws, lambda p: p["epoch"] != started["payload"]["epoch"])
            self.assertEqual(reset["payload"]["status"], "WAITING")
            self.assertFalse(reset["payload"]["armed"])

    async def test_automatic_calibration_start_and_cancel_never_arm_without_samples(self):
        async with connect(self.url + "/air") as ws:
            accepted = await self.authenticate(ws)
            session = accepted["sessionId"]
            initial = await self.frame_until(ws)
            await self.send_command(ws, session, 1, "start_calibration")
            started = await self.frame_until(ws, lambda p: p["epoch"] != initial["payload"]["epoch"])
            self.assertEqual(started["payload"]["stage"], "NEUTRAL")
            self.assertEqual(started["payload"]["status"], "COUNTDOWN")
            self.assertFalse(started["payload"]["armed"])
            for _ in range(3):
                waiting = await self.frame_until(ws)
                self.assertEqual(waiting["payload"]["stage"], "NEUTRAL")
                self.assertFalse(waiting["payload"]["armed"])
                self.assertEqual(waiting["payload"]["fingers"], [])
            await self.send_command(ws, session, 2, "disarm")
            cancelled = await self.frame_until(ws, lambda p: p["status"] == "WAITING")
            self.assertEqual(cancelled["payload"]["epoch"], started["payload"]["epoch"])
            for _ in range(3):
                stopped = await self.frame_until(ws)
                self.assertEqual(stopped["payload"]["status"], "WAITING")
                self.assertFalse(stopped["payload"]["armed"])
            await self.send_command(ws, session, 3, "arm")
            denied = await self.frame_until(ws, lambda p: p["status"] == "FAILED")
            self.assertFalse(denied["payload"]["armed"])

    async def test_reconnect_has_new_session_epoch_and_never_restores_arm(self):
        async with connect(self.url + "/air") as ws:
            first_auth = await self.authenticate(ws)
            first = await self.frame_until(ws)
            await self.send_command(ws, first_auth["sessionId"], 1, "arm")
            await self.frame_until(ws, lambda p: p["status"] == "FAILED")
        async with connect(self.url + "/air") as ws:
            second_auth = await self.authenticate(ws)
            second = await self.frame_until(ws)
            self.assertNotEqual(first_auth["sessionId"], second_auth["sessionId"])
            self.assertNotEqual(first["payload"]["epoch"], second["payload"]["epoch"])
            self.assertEqual(second["payload"]["status"], "WAITING")
            self.assertFalse(second["payload"]["armed"])

    async def test_legacy_v01_route_remains_separate_while_air_is_connected(self):
        async with connect(self.url + "/air") as air:
            await self.authenticate(air)
            async with connect(self.url) as legacy:
                await legacy.send(json.dumps(hello(version="0.1")))
                reply = json.loads(await asyncio.wait_for(legacy.recv(), 2))
                self.assertEqual(reply["protocolVersion"], "0.1")
                self.assertEqual(reply["payload"], {"mode": "diagnostic_only"})
                event = json.loads(await asyncio.wait_for(legacy.recv(), 2))
                self.assertEqual(event["type"], "gesture.move")
                self.assertEqual((await self.frame_until(air))["protocolVersion"], "0.2")

    async def test_air_camera_owner_excludes_legacy_and_second_air_then_releases(self):
        closed = []
        statuses = []
        def fake_frames():
            try:
                while True:
                    sleep(.015)
                    yield CameraSample(int(monotonic() * 1000), None)
            finally:
                closed.append(True)
        await self.start_server(source="camera", model="synthetic-not-a-real-model.task",
                                frame_factory=fake_frames, on_status=statuses.append)
        async with connect(self.url + "/air") as owner:
            await self.authenticate(owner)
            await self.frame_until(owner)
            for path, version in (("", "0.1"), ("/air", "0.2")):
                with self.subTest(blocked=path):
                    async with connect(self.url + path) as blocked:
                        await blocked.send(json.dumps(hello(version=version)))
                        await self.assert_closed(blocked, code=1013, allow_frames=False)
        for _ in range(50):
            if closed and "disconnected" in statuses: break
            await asyncio.sleep(.02)
        self.assertTrue(closed, "Fake camera generator was not released")
        self.assertIn("disconnected", statuses)
        async with connect(self.url + "/air") as replacement:
            await self.authenticate(replacement)
            self.assertFalse((await self.frame_until(replacement))["payload"]["armed"])

    async def test_old_future_and_repeated_capture_timestamps_cannot_be_reenveloped_as_fresh(self):
        for fault in ("old", "future", "repeated"):
            with self.subTest(fault=fault):
                def fake_frames():
                    now = int(monotonic() * 1000)
                    if fault == "old": yield CameraSample(now - 5000, None)
                    elif fault == "future": yield CameraSample(now + 5000, None)
                    else:
                        yield CameraSample(now, None)
                        yield CameraSample(now, None)
                await self.start_server(source="camera", model="synthetic-not-a-real-model.task", frame_factory=fake_frames)
                async with connect(self.url + "/air") as ws:
                    await self.authenticate(ws)
                    if fault == "repeated": await self.frame_until(ws)
                    # Capture age/order must be checked before giving cached landmarks a
                    # new source timestamp/sequence, which would hide staleness from Android.
                    await self.assert_closed(ws, code=1011, allow_frames=False)


if __name__ == "__main__":
    unittest.main()
