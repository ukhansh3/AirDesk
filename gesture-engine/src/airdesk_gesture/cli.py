"""Print JSON events from synthetic or local real webcam."""
from __future__ import annotations

import argparse
import json
from time import monotonic

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
    camera.add_argument("--preview", action="store_true")
    camera.add_argument("--no-mirror", action="store_true")
    camera.add_argument("--max-frames", type=int)
    picker = sub.add_parser("select-camera", help="Preview cameras without a MediaPipe model")
    picker.add_argument("--device", type=int, default=0)
    picker.add_argument("--max-devices", type=int, default=6)
    args = parser.parse_args()
    if args.mode == "select-camera":
        from .camera import CameraError, CameraSelectionCancelled, select_camera
        try:
            device = select_camera(args.device, args.max_devices)
        except (CameraError, CameraSelectionCancelled) as exc:
            parser.exit(1, f"{exc}\n")
        print(f"Selected camera index: {device}. Use --device {device} with the camera server.")
        return
    engine = GestureEngine()
    if args.mode == "synthetic":
        frames = sample_frames(args.count)
    else:
        from .camera import landmark_frames
        frames = landmark_frames(
            args.model, args.device, preview=args.preview,
            mirror=not args.no_mirror, max_frames=args.max_frames,
        )
    def output(events):
        for event in events:
            print(json.dumps(event, ensure_ascii=False, separators=(",", ":")), flush=True)
    try:
        for sample in frames:
            if args.mode == "synthetic":
                timestamp, points = sample
                confidence, reason = 1.0, "hand_not_detected"
            else:
                timestamp, points = sample.timestamp_ms, sample.landmarks
                confidence, reason = sample.confidence, sample.loss_reason
            if points is None and reason != "hand_not_detected":
                output(engine.end_tracking(timestamp, reason))
            else:
                output(engine.process(points, timestamp, confidence, reason))
    except KeyboardInterrupt:
        pass
    except RuntimeError as exc:
        output(engine.end_tracking(int(monotonic() * 1000), getattr(exc, "reason", "capture_failed")))
        raise SystemExit(str(exc)) from None
    finally:
        frames.close()
        if args.mode == "camera":
            output(engine.end_tracking(int(monotonic() * 1000), "camera_disconnected"))

if __name__ == "__main__":
    main()

