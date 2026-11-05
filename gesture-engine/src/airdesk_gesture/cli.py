"""Print JSON events from synthetic or local real webcam."""
from __future__ import annotations

import argparse
import json

from .engine import GestureEngine
from .synthetic import sample_frames

def main():
    parser = argparse.ArgumentParser(description="AirDesk gesture diagnostics (NO OS INPUT)")
    sub = parser.add_subparsers(dest="mode", required=True)
    synthetic = sub.add_parser("synthetic")
    synthetic.add_argument("--count", type=int, default=20)
    camera = sub.add_parser("camera")
    camera.add_argument("--model", required=True)
    camera.add_argument("--device", type=int, default=0)
    args = parser.parse_args()
    engine = GestureEngine()
    if args.mode == "synthetic":
        frames = sample_frames(args.count)
    else:
        from .camera import landmark_frames
        frames = landmark_frames(args.model, args.device)
    for timestamp, points in frames:
        for event in engine.process(points, timestamp):
            print(json.dumps(event, ensure_ascii=False, separators=(",", ":")), flush=True)

if __name__ == "__main__":
    main()
