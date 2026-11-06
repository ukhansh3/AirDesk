"""Authenticated localhost-only diagnostic WS; NO Windows input forwarding."""
from __future__ import annotations

import argparse
import asyncio
import hmac
import json
import os
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from time import monotonic
from uuid import uuid4

from .engine import GestureEngine
from .synthetic import sample_frames
from .protocol import DiagnosticProtocolError, parse_debug_hello

async def serve(
    source: str = "synthetic",
    model: str | None = None,
    *,
    device: int = 0,
    mirror: bool = True,
    host: str = "127.0.0.1",
    port: int = 8765,
):
    try:
        from websockets.asyncio.server import serve as ws_serve
    except ImportError as exc:
        raise RuntimeError('Install websocket dependency: pip install -e ".[ws]"') from exc

    token = os.environ.get("AIRDESK_DEV_TOKEN", "")
    if not 24 <= len(token) <= 512:
        raise RuntimeError("Set fresh AIRDESK_DEV_TOKEN of at least 24 chars")
    if source == "camera" and not model:
        raise ValueError("--model required for camera source")
    if host not in {"127.0.0.1", "::1"}:
        raise ValueError("Diagnostic server must remain loopback-only")
    if not 1 <= port <= 65535:
        raise ValueError("invalid port")

    camera_lock = asyncio.Lock()
    from jsonschema import Draft202012Validator
    contract_root = Path(__file__).resolve().parents[3] / "contracts"
    validators = {
        family: Draft202012Validator(json.loads((contract_root / path).read_text(encoding="utf-8")))
        for family, path in {
            "control": "control/messages.schema.json",
            "gesture": "gesture/gesture-event.schema.json",
        }.items()
    }

    async def send_event(ws, event):
        family = "gesture" if event["type"].startswith(("gesture.", "tracking.")) else "control"
        validators[family].validate(event)
        await asyncio.wait_for(ws.send(json.dumps(event, allow_nan=False)), timeout=0.75)

    async def handler(ws):
        source_session = f"{source}-demo-{uuid4().hex}"
        owns_camera = False
        try:
            raw = await asyncio.wait_for(ws.recv(), timeout=5)
            _client_session, submitted = parse_debug_hello(raw)
            validators["control"].validate(json.loads(raw))
            if not hmac.compare_digest(submitted, token):
                await send_event(ws, {
                    "protocolVersion":"0.1","type":"auth.rejected",
                    "sessionId":source_session,"sequence":0,
                    "timestampMs":int(monotonic()*1000),
                    "payload":{"errorCode":"UNAUTHENTICATED"}
                })
                await ws.close(code=1008, reason="not authorized")
                return
            if source == "camera":
                if camera_lock.locked():
                    await ws.close(code=1013, reason="camera diagnostic already active")
                    return
                await camera_lock.acquire()
                owns_camera = True
            await send_event(ws, {
                "protocolVersion":"0.1","type":"auth.accepted","sessionId":source_session,
                "sequence":0,"timestampMs":int(monotonic()*1000),"payload":{"mode":"diagnostic_only"}
            })
            # sequence=0 was used by auth.accepted; gesture events continue at 1.
            engine = GestureEngine(session_id=source_session, initial_sequence=1)
            if source == "synthetic":
                for ts, points in sample_frames(120):
                    for event in engine.process(points, int(monotonic() * 1000)):
                        await send_event(ws, event)
                    await asyncio.sleep(0.034)
            else:
                from .camera import CameraError, landmark_frames
                frames = landmark_frames(model, device, mirror=mirror)
                worker = ThreadPoolExecutor(max_workers=1, thread_name_prefix="airdesk-camera")
                loop = asyncio.get_running_loop()
                closed = asyncio.create_task(ws.wait_closed())
                # Do not allow StopIteration to cross asyncio Future boundaries.
                def next_frame():
                    return next(frames, None)
                try:
                    while True:
                        pending = loop.run_in_executor(worker, next_frame)
                        done, _ = await asyncio.wait({pending, closed}, return_when=asyncio.FIRST_COMPLETED)
                        if closed in done:
                            break
                        sample = pending.result()
                        if sample is None:
                            break
                        if sample.landmarks is None and sample.loss_reason != "hand_not_detected":
                            events = engine.end_tracking(sample.timestamp_ms, sample.loss_reason)
                        else:
                            events = engine.process(
                                sample.landmarks,
                                sample.timestamp_ms,
                                sample.confidence,
                                sample.loss_reason,
                            )
                        for event in events:
                            await send_event(ws, event)
                except CameraError as exc:
                    for event in engine.end_tracking(int(monotonic() * 1000), exc.reason):
                        await send_event(ws, event)
                finally:
                    closed.cancel()
                    await loop.run_in_executor(worker, frames.close)
                    worker.shutdown(wait=False)
        except (DiagnosticProtocolError, asyncio.TimeoutError, TypeError):
            await ws.close(code=1008, reason="invalid handshake")
        except Exception:
            # Avoid dumping request headers / secrets into logs.
            await ws.close(code=1011, reason="diagnostic error")
        finally:
            if owns_camera:
                camera_lock.release()

    async with ws_serve(
        handler, host, port, max_size=8192, max_queue=4,
        ping_interval=10, ping_timeout=10,
    ):
        print(f"AirDesk local diagnostic {host}:{port} (no remote OS input)")
        await asyncio.Future()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", choices=["synthetic", "camera"], default="synthetic")
    parser.add_argument("--model")
    parser.add_argument("--device", type=int, default=0)
    parser.add_argument("--no-mirror", action="store_true")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    asyncio.run(serve(
        args.source, args.model, device=args.device,
        mirror=not args.no_mirror, port=args.port,
    ))

if __name__ == "__main__":
    main()

