"""PC panel runtime. Local video only; reuse the canonical diagnostic server."""
from __future__ import annotations

import asyncio
import queue
import re
import threading
from math import isfinite
from copy import deepcopy
from pathlib import Path
from typing import Callable, Iterator

from .camera import CameraSample, landmark_frames, supports_air_handedness


class SetupError(ValueError):
    pass


def read_app_token(project: Path) -> str:
    """Read the EXISTING app token, never generate/rotate it or print its value."""
    properties = project / "android-client" / "local.properties"
    try:
        lines = properties.read_text(encoding="utf-8-sig").splitlines()
    except OSError:
        raise SetupError("Android 앱의 local.properties 설정 파일을 찾을 수 없습니다.") from None
    token = ""
    for line in lines:
        match = re.match(r"^\s*AIRDESK_DEV_TOKEN\s*[=:]\s*(.*?)\s*$", line)
        if match:
            token = match[1]
    if not 24 <= len(token) <= 512 or not token.isascii():
        raise SetupError("앱에 설정된 개발 토큰이 없습니다. 먼저 Android 앱의 개발 토큰을 설정해 주세요.")
    return token


def find_model(project: Path) -> Path:
    for path in (
        project / "models" / "hand_landmarker.task",
        project / "gesture-engine" / ".venv" / "models" / "hand_landmarker.task",
    ):
        if path.is_file():
            return path
    raise SetupError("손 인식 모델을 찾을 수 없습니다. '모델 파일 선택'에서 .task 파일을 골라 주세요.")


class LatestSamples:
    """Single latest sample: no backlog, frame recording or camera pixels on WS."""
    def __init__(self):
        self._condition = threading.Condition()
        self._generation = 0
        self._sample: CameraSample | None = None
        self._closed = False
        self._error: Exception | None = None

    def publish(self, sample: CameraSample):
        with self._condition:
            if self._closed:
                return
            self._sample = sample
            self._generation += 1
            self._condition.notify_all()

    def close(self, error: Exception | None = None):
        with self._condition:
            self._closed = True
            if error is not None:
                self._error = error
            self._condition.notify_all()

    def samples(self) -> Iterator[CameraSample]:
        generation = 0
        while True:
            with self._condition:
                self._condition.wait_for(lambda: self._closed or self._generation != generation)
                if self._closed:
                    if self._error:
                        raise self._error
                    return
                generation = self._generation
                sample = self._sample
            if sample is not None:
                yield sample


class DesktopRuntime:
    def __init__(self, project: Path, notify: Callable[[str, str], None]):
        self.project = project
        self.notify = notify
        self.frames: queue.Queue[bytes] = queue.Queue(maxsize=1)
        self.air_frames: queue.Queue[dict | None] = queue.Queue(maxsize=1)
        self._air_lock = threading.Lock()
        self._preview_lock = threading.Lock()
        self._camera_keyboard_lock = threading.Lock()
        self._camera_keyboard_enabled = True
        self._camera_keyboard_opacity = .4
        self._camera_keyboard_layout = "tkl"
        self._camera_keyboard = None
        self._camera_keyboard_failed = False
        self._camera_keyboard_announced = False
        self._preview_bounds = (860, 480)
        self._stop = threading.Event()
        self._samples = LatestSamples()
        self._camera_thread: threading.Thread | None = None
        self._server_thread: threading.Thread | None = None
        self._loop: asyncio.AbstractEventLoop | None = None
        self._server_task: asyncio.Task | None = None
        self._stopping = False

    @property
    def busy(self) -> bool:
        return any(thread is not None and thread.is_alive() for thread in (self._camera_thread, self._server_thread))

    def start(self, device: int, model: Path):
        if self.busy:
            raise SetupError("이미 실행 중입니다. 중지 후 카메라를 바꿔 주세요.")
        if not 0 <= device <= 31:
            raise SetupError("카메라 번호는 0~31 사이로 선택해 주세요.")
        if not model.is_file():
            raise SetupError("선택한 손 인식 모델 파일이 없습니다.")
        token = read_app_token(self.project)
        self._stop = threading.Event()
        self._samples = LatestSamples()
        self._stopping = False
        self._loop = None
        self._server_task = None
        self._camera_keyboard_failed = False
        self._camera_keyboard_announced = False
        self.publish_air_frame(None)
        while not self.frames.empty():
            self.frames.get_nowait()
        self._camera_thread = threading.Thread(target=self._camera, args=(device, model), daemon=True)
        self._server_thread = threading.Thread(target=self._server, args=(device, model, token), daemon=True)
        self._camera_thread.start()
        self._server_thread.start()

    def publish_air_frame(self, payload: dict | None):
        """One latest estimate for a user-opened local keyboard view, never video/text."""
        if payload is not None and self._stop.is_set():
            return
        value = deepcopy(payload) if payload is not None else None
        with self._air_lock:
            try:
                self.air_frames.get_nowait()
            except queue.Empty:
                pass
            self.air_frames.put_nowait(value)

    @property
    def camera_keyboard_settings(self) -> tuple[bool, float]:
        with self._camera_keyboard_lock:
            return self._camera_keyboard_enabled, self._camera_keyboard_opacity

    @property
    def camera_keyboard_layout(self) -> str:
        with self._camera_keyboard_lock:
            return self._camera_keyboard_layout

    def set_camera_keyboard(self, *, enabled: bool | None = None, opacity: float | None = None,
                            layout: str | None = None):
        """Display preferences only; never alter detector samples or input authority."""
        if enabled is not None and type(enabled) is not bool:
            raise ValueError("invalid camera keyboard visibility")
        if opacity is not None and (type(opacity) not in (int, float) or not isfinite(opacity) or not 0 <= opacity <= 1):
            raise ValueError("invalid camera keyboard opacity")
        if layout is not None and (type(layout) is not str or layout not in {"tkl", "legacy"}):
            raise ValueError("invalid camera keyboard layout")
        with self._camera_keyboard_lock:
            if layout is not None and layout != self._camera_keyboard_layout:
                self._camera_keyboard_layout = layout
                self._camera_keyboard_failed = False
                self._camera_keyboard_announced = False
            if enabled is not None:
                if enabled and not self._camera_keyboard_enabled:
                    self._camera_keyboard_failed = False
                self._camera_keyboard_enabled = enabled
            if opacity is not None:
                self._camera_keyboard_opacity = float(opacity)

    def set_preview_bounds(self, width: int, height: int):
        """Fit the complete image/keyboard into the actual Tk preview allocation."""
        if type(width) is not int or type(height) is not int or width <= 0 or height <= 0:
            raise ValueError("invalid preview allocation")
        with self._camera_keyboard_lock:
            self._preview_bounds = (min(width, 860), min(height, 480))

    def _preview(self, frame):
        if self._stop.is_set():
            return
        import cv2
        height, width = frame.shape[:2]
        with self._camera_keyboard_lock:
            max_width, max_height = self._preview_bounds
        scale = min(max_width / width, max_height / height, 1.0)
        if scale < 1:
            frame = cv2.resize(frame, (max(1, int(width * scale)), max(1, int(height * scale))))
        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        height, width = rgb.shape[:2]
        pixels = rgb.tobytes()
        with self._camera_keyboard_lock:
            enabled, opacity, layout = self._camera_keyboard_enabled, self._camera_keyboard_opacity, self._camera_keyboard_layout
        if enabled and not self._camera_keyboard_failed:
            try:
                if self._camera_keyboard is None or self._camera_keyboard.layout != layout:
                    from .camera_keyboard import CameraKeyboardOverlay
                    self._camera_keyboard = CameraKeyboardOverlay(layout=layout, include_mouse=layout == "legacy")
                composited = self._camera_keyboard.render_rgb(width, height, pixels, opacity=opacity)
                if not isinstance(composited, bytes) or len(composited) != width * height * 3:
                    raise ValueError("invalid optional preview output")
                pixels = composited
                if not self._camera_keyboard_announced:
                    self.notify("camera_keyboard", "TKL 87키 · 비율 고정 · 배치 참고용 · 기존 타건은 '기존 AIR 입력 정렬' 선택" if layout == "tkl"
                                else "기존 AIR 입력 정렬 · 81키 · 입력 결과는 Android 앱에서 확인")
                    self._camera_keyboard_announced = True
            except Exception:
                # Optional visualization cannot stop capture or change recognition.
                # Do not expose exception strings, local paths or camera pixels.
                self._camera_keyboard_failed = True
                self._camera_keyboard = None
                self.notify("camera_keyboard", "키보드 겹쳐보기 준비 실패 · 카메라는 계속 표시됩니다 · Pillow와 한글 글꼴을 확인하세요")
        ppm = f"P6\n{width} {height}\n255\n".encode("ascii") + pixels
        with self._preview_lock:
            if self._stop.is_set():
                return
            try:
                self.frames.get_nowait()
            except queue.Empty:
                pass
            self.frames.put_nowait(ppm)

    def _camera(self, device: int, model: Path):
        error = None
        detected = None
        quality = None
        def show_quality(diagnostic):
            nonlocal quality
            if diagnostic != quality:
                quality = diagnostic
                self.notify("camera_quality", f"미리보기 감지 {diagnostic.detected_hands} · 관절 표시 {diagnostic.displayed_hands} · "
                            + diagnostic.lines()[1] + " · 타건 유효 여부는 Android 앱에서 확인")
        try:
            import mediapipe as mp
            air_supported = supports_air_handedness(getattr(mp, "__version__", None))
            if not air_supported:
                self.notify("server", "양손 타건은 MediaPipe Tasks 0.10.5 이상이 필요합니다. 현재는 Phase 1만 사용 가능합니다.")
            frames = landmark_frames(str(model), device, frame_callback=self._preview, stop_event=self._stop,
                                     max_hands=2 if air_supported else 1,
                                     draw_preview_diagnostics=False, diagnostics_callback=show_quality)
            try:
                for sample in frames:
                    if self._stop.is_set():
                        break
                    self._samples.publish(sample)
                    tracking = len(sample.hands) if sample.hands else int(sample.landmarks is not None)
                    if tracking != detected:
                        self.notify("tracking", f"카메라 손 {tracking}개 감지 · 타건 유효 여부는 앱에서 확인" if tracking else "카메라 손 감지 없음 · 양손을 카메라 앞에 보여 주세요")
                        detected = tracking
                    if sample.loss_reason != "hand_not_detected":
                        self.notify("error", "카메라 영상 수신이 중단됐습니다. 다른 앱의 카메라 점유 상태를 확인해 주세요.")
            finally:
                frames.close()
        except Exception as exc:
            error = exc
            self.notify("error", "카메라 또는 손 인식 모델을 시작하지 못했습니다. 카메라 번호와 모델 파일을 확인해 주세요.")
        finally:
            self._samples.close(error)
            self.stop()

    def _server(self, device: int, model: Path, token: str):
        from .ws_server import serve

        async def run():
            self._loop = asyncio.get_running_loop()
            if self._stop.is_set():
                return
            self._server_task = asyncio.create_task(serve(
                source="camera", model=str(model), device=device, dev_token=token,
                frame_factory=self._samples.samples, on_status=lambda status: self.notify("server", status),
                on_air_frame=self.publish_air_frame,
            ))
            if self._stop.is_set():
                self._server_task.cancel()
            await self._server_task
        try:
            asyncio.run(run())
        except asyncio.CancelledError:
            pass
        except OSError:
            self.notify("error", "서버 포트 8765를 사용할 수 없습니다. 이전 터미널 서버를 Ctrl+C로 종료한 뒤 다시 시작해 주세요.")
            self.stop()
        except Exception:
            self.notify("error", "진단 서버를 시작하지 못했습니다. 설치된 Python 의존성을 확인해 주세요.")
            self.stop()
        finally:
            self._loop = None
            self._server_task = None

    def stop(self):
        self._stopping = True
        self._stop.set()
        with self._preview_lock:
            try:
                self.frames.get_nowait()
            except queue.Empty:
                pass
        self.publish_air_frame(None)
        self._samples.close()
        loop, task = self._loop, self._server_task
        if loop and task and not loop.is_closed():
            try:
                loop.call_soon_threadsafe(task.cancel)
            except RuntimeError:
                pass
