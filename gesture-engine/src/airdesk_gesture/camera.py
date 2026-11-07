"""Optional Windows webcam reader for official MediaPipe Tasks Hand Landmarker."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from time import monotonic
from typing import Callable, Iterator
from threading import Event


class CameraError(RuntimeError):
    reason = "capture_failed"


class CameraDisconnectedError(CameraError):
    reason = "camera_disconnected"


class ModelError(CameraError):
    reason = "model_failed"


class CameraSelectionCancelled(RuntimeError):
    pass


def select_camera(device: int = 0, max_devices: int = 6) -> int:
    """User-operated local preview picker. Does not save images or use MediaPipe."""
    try:
        import cv2
    except ImportError as exc:
        raise CameraError('Install webcam dependencies: pip install -e ".[camera]"') from exc
    if max_devices < 1 or not 0 <= device < max_devices:
        raise ValueError("camera index must be within the selection range")
    window = "AirDesk Camera Selection - N/P switch, ENTER select, Q cancel"
    capture = None
    selected = device

    def open_from(start, direction):
        for step in range(max_devices):
            index = (start + step * direction) % max_devices
            candidate = cv2.VideoCapture(index)
            try:
                ok, frame = candidate.read() if candidate.isOpened() else (False, None)
            except Exception:
                candidate.release()
                continue
            if ok:
                return index, candidate, frame
            candidate.release()
        raise CameraDisconnectedError("No readable camera found; close other camera apps or change the index range")

    try:
        selected, capture, frame = open_from(device, 1)
        while True:
            display = cv2.flip(frame, 1)
            cv2.putText(display, f"Camera {selected} | N/P: switch | ENTER: select | Q: cancel",
                        (12, 28), cv2.FONT_HERSHEY_SIMPLEX, .55, (80, 230, 120), 2)
            cv2.imshow(window, display)
            key = cv2.waitKey(20) & 0xFF
            if key in (10, 13):
                return selected
            if key in (ord("q"), 27) or cv2.getWindowProperty(window, cv2.WND_PROP_VISIBLE) < 1:
                raise CameraSelectionCancelled("Camera selection cancelled")
            if key in (ord("n"), ord("p")):
                direction = 1 if key == ord("n") else -1
                capture.release()
                capture = None
                selected, capture, frame = open_from(selected + direction, direction)
            else:
                ok, frame = capture.read()
                if not ok:
                    raise CameraDisconnectedError(f"Camera {selected} stopped while selecting")
    finally:
        if capture is not None:
            capture.release()
        cv2.destroyAllWindows()


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
    frame_callback: Callable[[object], None] | None = None,
    stop_event: Event | None = None,
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
            while stop_event is None or not stop_event.is_set():
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
                    if preview or frame_callback is not None:
                        height, width = frame.shape[:2]
                        for x, y in points:
                            cv2.circle(frame, (int(x * width), int(y * height)), 2, (80, 220, 120), -1)
                        for index, color in ((8, (255, 140, 60)), (4, (180, 80, 255))):
                            x, y = points[index]
                            cv2.circle(frame, (int(x * width), int(y * height)), 7, color, 2)
                    sample = CameraSample(now, points, confidence)
                else:
                    sample = CameraSample(now, None)
                if frame_callback is not None:
                    frame_callback(frame)
                yield sample
                frame_count += 1
                if preview:
                    window = "AirDesk Hand Landmarker - Q/ESC to stop"
                    cv2.putText(frame, f"Camera {device} | Local preview | Q/ESC: stop",
                                (12, 28), cv2.FONT_HERSHEY_SIMPLEX, .55, (80, 230, 120), 2)
                    cv2.imshow(window, frame)
                    if cv2.waitKey(1) & 0xFF in (ord("q"), 27) or cv2.getWindowProperty(window, cv2.WND_PROP_VISIBLE) < 1:
                        break
                if max_frames is not None and frame_count >= max_frames:
                    break
    finally:
        capture.release()
        if preview:
            cv2.destroyAllWindows()

