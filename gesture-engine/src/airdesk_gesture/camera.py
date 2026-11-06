"""Optional Windows webcam reader for official MediaPipe Tasks Hand Landmarker."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from time import monotonic
from typing import Iterator


class CameraError(RuntimeError):
    reason = "capture_failed"


class CameraDisconnectedError(CameraError):
    reason = "camera_disconnected"


class ModelError(CameraError):
    reason = "model_failed"


@dataclass(frozen=True)
class CameraSample:
    timestamp_ms: int
    landmarks: list[tuple[float, float]] | None
    confidence: float = 0.0
    loss_reason: str = "hand_not_detected"


def landmark_frames(
    model_path: str,
    device: int = 0,
    *,
    mirror: bool = True,
    preview: bool = False,
    max_frames: int | None = None,
) -> Iterator[CameraSample]:
    # Lazy imports keep pure engine tests independent from native dependencies.
    try:
        import cv2
        import mediapipe as mp
    except ImportError as exc:
        raise CameraError('Install webcam dependencies: pip install -e ".[camera]"') from exc
    if not Path(model_path).is_file():
        raise ModelError(f"MediaPipe model missing: {model_path}")
    options = mp.tasks.vision.HandLandmarkerOptions(
        base_options=mp.tasks.BaseOptions(model_asset_path=model_path),
        running_mode=mp.tasks.vision.RunningMode.VIDEO,
        num_hands=1,
        min_hand_detection_confidence=0.5,
        min_hand_presence_confidence=0.5,
    )
    capture = cv2.VideoCapture(device)
    if not capture.isOpened():
        capture.release()
        raise CameraDisconnectedError(f"Could not open webcam index {device}")
    try:
        try:
            detector_context = mp.tasks.vision.HandLandmarker.create_from_options(options)
        except Exception as exc:
            raise ModelError("Could not initialize MediaPipe Hand Landmarker") from exc
        with detector_context as detector:
            last_ms = -1
            frame_count = 0
            while True:
                ok, frame = capture.read()
                if not ok:
                    yield CameraSample(
                        max(last_ms + 1, int(monotonic() * 1000)), None,
                        loss_reason="capture_failed",
                    )
                    break
                if mirror:
                    frame = cv2.flip(frame, 1)
                now = max(last_ms + 1, int(monotonic() * 1000))
                last_ms = now
                rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                try:
                    result = detector.detect_for_video(
                        mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb), now
                    )
                except Exception as exc:
                    raise ModelError("MediaPipe inference failed") from exc
                if result.hand_landmarks:
                    hand = result.hand_landmarks[0]
                    # Tasks exposes handedness scores, not landmark confidence.
                    # 1.0 means a sample passed configured detection/presence gates;
                    # it must not be described as a calibrated confidence score.
                    confidence = 1.0
                    points = [(point.x, point.y) for point in hand]
                    if preview:
                        height, width = frame.shape[:2]
                        for x, y in points:
                            cv2.circle(frame, (int(x * width), int(y * height)), 2, (80, 220, 120), -1)
                        for index, color in ((8, (255, 140, 60)), (4, (180, 80, 255))):
                            x, y = points[index]
                            cv2.circle(frame, (int(x * width), int(y * height)), 7, color, 2)
                    yield CameraSample(now, points, confidence)
                else:
                    yield CameraSample(now, None)
                frame_count += 1
                if preview:
                    cv2.imshow("AirDesk Hand Landmarker - Q/ESC to stop", frame)
                    if cv2.waitKey(1) & 0xFF in (ord("q"), 27):
                        break
                if max_frames is not None and frame_count >= max_frames:
                    break
    finally:
        capture.release()
        if preview:
            cv2.destroyAllWindows()

