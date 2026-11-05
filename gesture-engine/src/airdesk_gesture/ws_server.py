"""Authenticated localhost-only diagnostic WS; NO Windows input forwarding."""
from __future__ import annotations

import argparse
import asyncio
import hmac
import json
import os
from time import monotonic

from .engine import GestureEngine
from .synthetic import sample_frames
from .protocol import DiagnosticProtocolError, parse_debug_hello

async def serve(source: str = "synthetic", model: str | None = None):
    try:
        from websockets.asyncio.server import serve as ws_serve
    except ImportError as exc:
        raise RuntimeError('Install websocket dependency: pip install -e ".[ws]"') from exc

    token = os.environ.get("AIRDESK_DEV_TOKEN", "")
    if len(token) < 24:
        raise RuntimeError("Set fresh AIRDESK_DEV_TOKEN of at least 24 chars")
    if source == "camera" and not model:
        raise ValueError("--model required for camera source")

    async def handler(ws):
        try:
            raw = await asyncio.wait_for(ws.recv(), timeout=5)
            _client_session, submitted = parse_debug_hello(raw)
            if not hmac.compare_digest(submitted, token):
                await ws.send(json.dumps({
                    "protocolVersion":"0.1","type":"auth.rejected",
                    "sessionId":"synthetic-demo","sequence":0,
                    "timestampMs":int(monotonic()*1000),
                    "payload":{"errorCode":"UNAUTHENTICATED"}
                }, allow_nan=False))
                await ws.close(code=1008, reason="not authorized")
                return
            await ws.send(json.dumps({
                "protocolVersion":"0.1","type":"auth.accepted","sessionId":"synthetic-demo",
                "sequence":0,"timestampMs":int(monotonic()*1000),"payload":{"mode":"diagnostic_only"}
            }))
            engine = GestureEngine()
            if source == "synthetic":
                for ts, points in sample_frames(120):
                    for event in engine.process(points, ts):
                        await ws.send(json.dumps(event, allow_nan=False))
                    await asyncio.sleep(0.034)
            else:
                from .camera import landmark_frames
                frames = landmark_frames(model)
                # Do not allow StopIteration to cross asyncio Future boundaries.
                def next_frame():
                    return next(frames, None)
                try:
                    while True:
                        sample = await asyncio.to_thread(next_frame)
                        if sample is None:
                            break
                        ts, points = sample
                        for event in engine.process(points, ts):
                            await ws.send(json.dumps(event))
                finally:
                    frames.close()
        except (DiagnosticProtocolError, asyncio.TimeoutError, TypeError):
            await ws.close(code=1008, reason="invalid handshake")
        except Exception:
            # Avoid dumping request headers / secrets into logs.
            await ws.close(code=1011, reason="diagnostic error")

    async with ws_serve(handler, "127.0.0.1", 8765, max_size=8192, max_queue=4):
        print("AirDesk local diagnostic 127.0.0.1:8765 (no remote OS input)")
        await asyncio.Future()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", choices=["synthetic", "camera"], default="synthetic")
    parser.add_argument("--model")
    args = parser.parse_args()
    asyncio.run(serve(args.source, args.model))

if __name__ == "__main__":
    main()
