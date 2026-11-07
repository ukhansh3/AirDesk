"""Explicit v0.2 /air diagnostic profile. No key text or operating-system input."""
from __future__ import annotations

import asyncio
from concurrent.futures import ThreadPoolExecutor
from contextlib import suppress
import hmac
import json
from pathlib import Path
from time import monotonic
from uuid import uuid4

from jsonschema import Draft202012Validator
from .camera import CameraSample, landmark_frames


def strict_message(raw, validator):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate JSON")
            result[key] = value
        return result
    def invalid(_):
        raise ValueError("non-finite JSON")
    if not isinstance(raw, str) or len(raw.encode("utf-8")) > 8192:
        raise ValueError("invalid frame size")
    event = json.loads(raw, object_pairs_hook=unique, parse_constant=invalid)
    validator.validate(event)
    return event


def fresh_camera_sample(sample, now_ms, previous_ms):
    """Never give a cached held payload a fresh wire timestamp."""
    if (type(sample.timestamp_ms) is not int or sample.timestamp_ms <= previous_ms or
            not 0 <= now_ms - sample.timestamp_ms <= 250):
        return CameraSample(now_ms, None, loss_reason="tracking_timeout")
    return sample


async def handle_air(ws, *, source, model, device, mirror, preview, token, frame_factory, camera_lock, on_status, on_air_frame=None):
    from .air_typing import AirTypingEngine
    schema = Path(__file__).resolve().parents[3] / "contracts/air/air-messages.schema.json"
    validator = Draft202012Validator(json.loads(schema.read_text(encoding="utf-8")))
    session = f"air-demo-{uuid4().hex}"
    sequence = 0
    owns_camera = False
    accepted = False
    commands_task = None
    frames = None
    worker = None

    async def send(kind, payload):
        nonlocal sequence
        event = dict(protocolVersion="0.2", type=kind, sessionId=session, sequence=sequence,
                     timestampMs=int(monotonic() * 1000), payload=payload)
        validator.validate(event)
        sequence += 1
        await asyncio.wait_for(ws.send(json.dumps(event, allow_nan=False)), .75)
        if kind == "air.frame" and on_air_frame is not None:
            # A read-only local visualization must never become an input dependency.
            with suppress(Exception):
                on_air_frame(payload)

    try:
        hello = strict_message(await asyncio.wait_for(ws.recv(), 5), validator)
        if hello["type"] != "auth.hello" or hello["sequence"] != 0:
            raise ValueError("expected AIR hello")
        hello_at = int(monotonic() * 1000)
        if not hmac.compare_digest(hello["payload"]["token"], token):
            await send("auth.rejected", {"errorCode": "UNAUTHENTICATED"})
            await ws.close(code=1008, reason="not authorized")
            return
        if source == "camera":
            if camera_lock.locked():
                await ws.close(code=1013, reason="camera profile already active")
                return
            await camera_lock.acquire()
            owns_camera = True
        engine = AirTypingEngine()
        await send("auth.accepted", {"mode": "air_typing_demo"})
        accepted = True
        if on_status:
            on_status("connected")

        async def commands():
            last_seq = 0
            last_time = hello["timestampMs"]
            async for raw in ws:
                event = strict_message(raw, validator)
                now = int(monotonic() * 1000)
                if (event["type"] != "air.command" or event["sessionId"] != session or
                        event["sequence"] <= last_seq or event["timestampMs"] < last_time or
                        abs((event["timestampMs"] - hello["timestampMs"]) - (now - hello_at)) > 1000):
                    raise ValueError("invalid AIR command ownership/order/freshness")
                last_seq, last_time = event["sequence"], event["timestampMs"]
                engine.command(event["payload"]["action"], now)

        commands_task = asyncio.create_task(commands())
        loop = asyncio.get_running_loop()
        if source == "camera":
            frames = frame_factory() if frame_factory else landmark_frames(
                model, device, mirror=mirror, preview=preview, max_hands=2)
            worker = ThreadPoolExecutor(max_workers=1, thread_name_prefix="airdesk-air-camera")
        pending = None
        previous_sample_ms = -1
        while not commands_task.done():
            if frames is None:
                await send("air.frame", engine.process(CameraSample(int(monotonic() * 1000), None)))
                await asyncio.sleep(.034)
                continue
            if pending is None:
                pending = loop.run_in_executor(worker, lambda: next(frames, None))
            done, _ = await asyncio.wait({pending, commands_task}, timeout=.25, return_when=asyncio.FIRST_COMPLETED)
            if commands_task in done:
                break
            if pending not in done:
                sample = CameraSample(int(monotonic() * 1000), None, loss_reason="tracking_timeout")
            else:
                sample = pending.result()
                pending = None
                if sample is None:
                    await send("air.frame", engine.process(CameraSample(int(monotonic() * 1000), None,
                                                                          loss_reason="camera_disconnected")))
                    break
                checked = fresh_camera_sample(sample, int(monotonic() * 1000), previous_sample_ms)
                if checked is not sample:
                    # Closing is a cancellation boundary, not a fresh replay of cached held state.
                    engine.command("disarm", int(monotonic() * 1000))
                    await ws.close(code=1011, reason="stale AIR capture")
                    break
                previous_sample_ms = sample.timestamp_ms
            await send("air.frame", engine.process(sample))
        if commands_task.done():
            commands_task.result()
    except Exception:
        # No request body, token, landmarks or typed text is written to logs.
        with suppress(Exception):
            await ws.close(code=1008, reason="AIR profile ended")
    finally:
        if commands_task:
            commands_task.cancel()
            with suppress(asyncio.CancelledError, Exception):
                await commands_task
        if frames is not None and worker is not None:
            # Serialize close after any in-flight camera read; never concurrently close a generator.
            with suppress(Exception):
                await asyncio.get_running_loop().run_in_executor(worker, frames.close)
            worker.shutdown(wait=False)
        if owns_camera:
            camera_lock.release()
        if accepted and on_status:
            on_status("disconnected")
        if accepted and on_air_frame is not None:
            with suppress(Exception):
                on_air_frame(None)
        with suppress(Exception):
            await ws.close(code=1000, reason="AIR diagnostic ended")
