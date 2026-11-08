"""Synthetic local-display integration. No cameras, MediaPipe models or real Tk windows."""
import queue
import unittest
from dataclasses import FrozenInstanceError
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, Mock, patch

from airdesk_gesture.desktop_runtime import DesktopRuntime

try:
    import cv2
    import numpy as np
except ImportError:
    cv2 = np = None


@unittest.skipUnless(cv2 is not None and np is not None, "OpenCV/NumPy camera extras unavailable; display integration NOT_RUN")
class CameraKeyboardRuntimeTests(unittest.TestCase):
    def setUp(self):
        self.events = []
        self.runtime = DesktopRuntime(Path("synthetic-project"), lambda kind, text: self.events.append((kind, text)))

    @staticmethod
    def bgr(color=(21, 33, 65), width=640, height=360):
        image = np.empty((height, width, 3), dtype=np.uint8)
        image[:] = color
        return image

    def output(self, image):
        self.runtime._preview(image)
        ppm = self.runtime.frames.get_nowait()
        magic, dimensions, maximum, pixels = ppm.split(b"\n", 3)
        self.assertEqual(magic, b"P6")
        self.assertEqual(maximum, b"255")
        width, height = map(int, dimensions.split())
        self.assertEqual(len(pixels), width * height * 3)
        return np.frombuffer(pixels, dtype=np.uint8).reshape(height, width, 3)

    def assert_green_transparent_overlay(self, actual, base):
        changed = np.any(actual != base, axis=2)
        self.assertTrue(changed.any(), "Actual compositor must draw an overlay, not silently return the camera")
        green = changed & (actual[:, :, 1] > actual[:, :, 0]) & (actual[:, :, 1] > actual[:, :, 2])
        self.assertGreater(int(green.sum()), 30, "The displayed outlines/text must be green")
        y, x = np.nonzero(changed)
        region = changed[y.min():y.max() + 1, x.min():x.max() + 1]
        self.assertLess(float(region.mean()), .60, "Transparent key interiors must not become a filled keyboard panel")
        holes = cv2.erode((~region).astype(np.uint8), np.ones((5, 5), dtype=np.uint8))
        self.assertGreater(int(holes.sum()), max(20, region.size * .10),
                           "Substantial camera areas inside the outline region must remain untouched")

    def test_default_actual_compositor_draws_green_lines_and_preserves_camera_interiors(self):
        self.assertEqual(self.runtime.camera_keyboard_settings, (True, .4))
        frame = self.bgr()
        original = frame.tobytes()
        frame.setflags(write=False)
        actual = self.output(frame)
        base = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        self.assert_green_transparent_overlay(actual, base)
        self.assertEqual(frame.tobytes(), original, "Display composition must never modify the source BGR frame")

    def test_explicit_disable_outputs_the_original_rgb_exactly_and_never_initializes_renderer(self):
        self.runtime.set_camera_keyboard(enabled=False)
        frame = self.bgr()
        with patch("airdesk_gesture.camera_keyboard.CameraKeyboardOverlay", side_effect=AssertionError("not needed")) as renderer:
            actual = self.output(frame)
        renderer.assert_not_called()
        np.testing.assert_array_equal(actual, cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))

    def test_opacity_changes_visible_green_strength_without_changing_source_pixels(self):
        frame = self.bgr()
        original = frame.tobytes()
        base = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB).astype(np.int16)
        self.runtime.set_camera_keyboard(opacity=.2)
        low = self.output(frame).astype(np.int16)
        self.runtime.set_camera_keyboard(opacity=.8)
        high = self.output(frame).astype(np.int16)
        self.assertGreater(int(np.abs(high - base).sum()), int(np.abs(low - base).sum()) * 2)
        self.assertEqual(frame.tobytes(), original)

    def test_large_camera_frame_is_composited_after_bounded_preview_resize(self):
        frame = self.bgr(width=1280, height=720)
        original = frame.tobytes()
        actual = self.output(frame)
        self.assertEqual(actual.shape, (480, 853, 3))
        resized = cv2.resize(frame, (853, 480))
        self.assert_green_transparent_overlay(actual, cv2.cvtColor(resized, cv2.COLOR_BGR2RGB))
        self.assertEqual(frame.tobytes(), original)

    def test_actual_preview_allocation_fits_the_whole_image_preserving_its_aspect_ratio(self):
        frame = self.bgr(width=1280, height=720)
        original = frame.tobytes()
        self.runtime.set_preview_bounds(320, 200)
        fitted = self.output(frame)
        self.assertEqual(fitted.shape, (180, 320, 3))
        self.assert_green_transparent_overlay(fitted, cv2.cvtColor(cv2.resize(frame, (320, 180)), cv2.COLOR_BGR2RGB))
        self.runtime.set_preview_bounds(150, 250)
        tall = self.output(frame)
        self.assertEqual(tall.shape, (84, 150, 3))
        self.assertLessEqual(abs(tall.shape[1] * 720 - tall.shape[0] * 1280), 1280)
        self.runtime.set_preview_bounds(4096, 4096)
        self.assertEqual(self.output(frame).shape, (480, 853, 3))
        self.assertEqual(frame.tobytes(), original)

    def test_embedded_preview_moves_diagnostics_outside_image_without_changing_raw_samples(self):
        from airdesk_gesture.camera import landmark_frames
        frame = self.bgr()
        raw_rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        points = [[SimpleNamespace(x=.2 + side * .45 + i * .002, y=.3 + i * .002, z=-i * .001)
                   for i in range(21)] for side in range(2)]
        result = SimpleNamespace(hand_landmarks=points, handedness=[
            [SimpleNamespace(category_name="Left", score=.99)], [SimpleNamespace(category_name="Right", score=.99)]])
        detector = MagicMock()
        detector.__enter__.return_value = detector
        detector.detect_for_video.return_value = result
        mp = SimpleNamespace(__version__="0.10.35", ImageFormat=SimpleNamespace(SRGB=1),
            Image=lambda **kwargs: SimpleNamespace(**kwargs), tasks=SimpleNamespace(BaseOptions=MagicMock(), vision=SimpleNamespace(
                RunningMode=SimpleNamespace(VIDEO=1), HandLandmarkerOptions=MagicMock(),
                HandLandmarker=SimpleNamespace(create_from_options=Mock(return_value=detector)))))
        capture = MagicMock()
        capture.isOpened.return_value = True
        capture.read.return_value = True, frame
        order, diagnostics, samples, previews = [], [], [], []
        actual_preview = self.runtime._preview

        def preview(image):
            order.append("preview")
            actual_preview(image)
            previews.append(self.runtime.frames.get_nowait())

        def limited_frames(*args, **kwargs):
            original_callback = kwargs["diagnostics_callback"]
            def metadata(value):
                diagnostics.append(value); order.append("diagnostics")
                original_callback(value)
            kwargs["diagnostics_callback"] = metadata
            return landmark_frames(*args, max_frames=1, **kwargs)

        with patch.dict("sys.modules", {"mediapipe": mp}), patch.object(cv2, "VideoCapture", return_value=capture), \
                patch.object(cv2, "putText") as captions, patch.object(Path, "is_file", return_value=True), \
                patch("airdesk_gesture.desktop_runtime.landmark_frames", side_effect=limited_frames), \
                patch.object(self.runtime, "_preview", side_effect=preview), \
                patch.object(self.runtime._samples, "publish", side_effect=samples.append):
            self.runtime._camera(0, Path("synthetic.task"))
        captions.assert_not_called()
        self.assertEqual(order, ["diagnostics", "preview"])
        self.assertEqual(len(previews), 1)
        self.assertEqual(len(samples), 1)
        self.assertEqual(diagnostics[0].detected_hands, 2)
        with self.assertRaises(FrozenInstanceError):
            diagnostics[0].detected_hands = 0
        self.assertTrue(any(kind == "camera_quality" for kind, _ in self.events))
        self.assertEqual(samples[0].landmarks, [(p.x, p.y) for p in points[0]])
        self.assertEqual([hand.landmarks for hand in samples[0].hands],
                         [tuple((p.x, p.y, p.z) for p in hand) for hand in points])
        np.testing.assert_array_equal(detector.detect_for_video.call_args.args[0].data, raw_rgb)
        capture.release.assert_called_once()

    def test_cached_overlay_never_reuses_a_previous_camera_background_or_builds_a_backlog(self):
        first = self.bgr((10, 20, 40))
        second = self.bgr((70, 45, 15))
        self.runtime._preview(first)
        self.runtime._preview(second)
        self.assertEqual(self.runtime.frames.qsize(), 1)
        ppm = self.runtime.frames.get_nowait()
        pixels = np.frombuffer(ppm.split(b"\n", 3)[3], dtype=np.uint8).reshape(360, 640, 3)
        base = cv2.cvtColor(second, cv2.COLOR_BGR2RGB)
        self.assert_green_transparent_overlay(pixels, base)
        self.assertGreater(int(np.all(pixels == base, axis=2).sum()), 640 * 360 * .50)
        self.assertTrue(self.runtime.frames.empty())

    def test_renderer_failure_falls_back_to_live_rgb_and_warns_only_once_without_private_exception(self):
        secret = "private-path-or-exception-content"
        with patch("airdesk_gesture.camera_keyboard.CameraKeyboardOverlay.render_rgb", side_effect=RuntimeError(secret)) as render:
            for color in ((10, 20, 30), (40, 50, 60), (70, 80, 90)):
                frame = self.bgr(color)
                np.testing.assert_array_equal(self.output(frame), cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
        self.assertEqual(render.call_count, 1)
        self.assertEqual(len(self.events), 1)
        self.assertEqual(self.events[0][0], "camera_keyboard")
        self.assertNotIn(secret, self.events[0][1])
        self.assertLess(len(self.events[0][1]), 256)
        self.assertFalse(self.runtime._stop.is_set(), "Optional rendering failure must not stop the camera runtime")

    def test_renderer_unavailable_keeps_camera_display_and_off_on_explicitly_retries(self):
        frame = self.bgr()
        with patch("airdesk_gesture.camera_keyboard.CameraKeyboardOverlay", side_effect=ImportError("private module path")) as constructor:
            np.testing.assert_array_equal(self.output(frame), cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
            np.testing.assert_array_equal(self.output(frame), cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
        constructor.assert_called_once()
        self.assertEqual(len(self.events), 1)
        self.assertNotIn("private module path", self.events[0][1])
        self.runtime.set_camera_keyboard(enabled=False)
        np.testing.assert_array_equal(self.output(frame), cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
        self.runtime.set_camera_keyboard(enabled=True)
        self.assert_green_transparent_overlay(self.output(frame), cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))

    def test_stop_discards_queued_preview_and_rejects_late_camera_callbacks(self):
        self.runtime.set_camera_keyboard(enabled=False)
        self.runtime._preview(self.bgr())
        self.assertEqual(self.runtime.frames.qsize(), 1)
        self.runtime.stop()
        self.assertTrue(self.runtime.frames.empty())
        self.runtime._preview(self.bgr((20, 40, 60)))
        self.assertTrue(self.runtime.frames.empty())

    def test_stop_during_composition_cannot_publish_its_old_pixels_after_stop(self):
        def stop_mid_render(width, height, pixels, *, opacity):
            self.runtime.stop()
            return pixels
        with patch("airdesk_gesture.camera_keyboard.CameraKeyboardOverlay.render_rgb", side_effect=stop_mid_render):
            self.runtime._preview(self.bgr())
        self.assertTrue(self.runtime.frames.empty())


class CameraKeyboardPreviewControllerTests(unittest.TestCase):
    @staticmethod
    def app_stub():
        # __init__ builds Tk widgets, so exercise the real poll/stop methods on headless collaborators.
        from airdesk_gesture.desktop import DesktopApp
        app = DesktopApp.__new__(DesktopApp)
        app.runtime = SimpleNamespace(frames=queue.Queue(maxsize=1), busy=True, stop=Mock())
        app.events = queue.Queue()
        app.root = Mock()
        app.preview = Mock()
        app.stop_button = Mock()
        app.status = Mock()
        app.photo = None
        app.was_busy = True
        app.closing_at = None
        app._air_display_enabled = True
        app._preview_display_enabled = True
        app._clear_air_preview = Mock()
        app._poll_air_frames = Mock()
        return app

    def test_user_stop_blocks_late_queued_image_even_while_camera_worker_is_still_busy(self):
        app = self.app_stub()
        app.stop()
        self.assertFalse(app._preview_display_enabled)
        app.runtime.frames.put_nowait(b"P6\n1 1\n255\n\x00\x01\x02")
        with patch("airdesk_gesture.desktop.tk.PhotoImage", side_effect=AssertionError("must not open or revive an image")):
            app.poll()
        self.assertIsNone(app.photo)
        self.assertTrue(app.runtime.frames.empty())
        app.runtime.stop.assert_called_once()
        app.root.after.assert_called_once()

    def test_active_preview_can_show_a_current_image_without_creating_a_real_tk_window(self):
        app = self.app_stub()
        app.runtime.frames.put_nowait(b"P6\n1 1\n255\n\x00\x01\x02")
        photo = object()
        with patch("airdesk_gesture.desktop.tk.PhotoImage", return_value=photo) as image:
            app.poll()
        image.assert_called_once()
        self.assertIs(app.photo, photo)
        app.preview.configure.assert_called_once_with(image=photo, text="")


if __name__ == "__main__":
    unittest.main()
