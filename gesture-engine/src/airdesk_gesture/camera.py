"""Optional Windows webcam reader for official MediaPipe Tasks Hand Landmarker."""
from __future__ import annotations

from pathlib import Path
from time import monotonic
from typing import Iterator


def landmark_frames(model_path: str, device: int = 0) -> Iterator[tuple[int, list[tuple[float, float]] | None]]:
    # Lazy imports keep pure engine tests independent from native dependencies.
    try:
        import cv2
        import mediapipe as mp
    except ImportError as exc:
        raise RuntimeError('Install webcam dependencies: pip install -e ".[camera]"') from exc
    if not Path(model_path).is_file():
        raise FileNotFoundError(f"MediaPipe model missing: {model_path}")
    options = mp.tasks.vision.HandLandmarkerOptions(
        base_options=mp.tasks.BaseOptions(model_asset_path=model_path),
        running_mode=mp.tasks.vision.RunningMode.VIDEO,
        num_hands=1,
        min_hand_detection_confidence=0.5,
        min_hand_presence_confidence=0.5,
    )
    capture = cv2.VideoCapture(device)
    if not capture.isOpened():
        raise RuntimeError(f"Could not open webcam index {device}")
    try:
        with mp.tasks.vision.HandLandmarker.create_from_options(options) as detector:
            last_ms = -1
            while True:
                ok, frame = capture.read()
                if not ok:
                    yield (max(last_ms + 1, int(monotonic() * 1000)), None)
                    break
                now = max(last_ms + 1, int(monotonic() * 1000))
                last_ms = now
                rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                result = detector.detect_for_video(
                    mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb), now
                )
                if result.hand_landmarks:
                    hand = result.hand_landmarks[0]
                    yield now, [(point.x, point.y) for point in hand]
                else:
                    yield now, None
    finally:
        capture.release()
