"""Resource/error tests with fake native modules, NOT camera hardware evidence."""
import unittest
from types import SimpleNamespace
from unittest.mock import MagicMock, patch
from airdesk_gesture.camera import landmark_frames, CameraDisconnectedError, ModelError, select_camera, CameraSelectionCancelled


class CameraTests(unittest.TestCase):
    def test_panel_frame_callback_does_not_open_opencv_windows(self):
        cv, capture, mp, _ = self.fake_modules()
        capture.read.return_value = (True, object())
        callback = MagicMock()
        with patch.dict("sys.modules", {"cv2": cv, "mediapipe": mp}), patch("pathlib.Path.is_file", return_value=True):
            list(landmark_frames("fake.task", frame_callback=callback, max_frames=1))
        callback.assert_called_once()
        cv.imshow.assert_not_called()
        capture.release.assert_called_once()

    def test_picker_selects_requested_camera_and_releases(self):
        cv = MagicMock()
        capture = cv.VideoCapture.return_value
        capture.isOpened.return_value = True
        capture.read.return_value = (True, object())
        cv.waitKey.return_value = 13
        with patch.dict("sys.modules", {"cv2": cv}):
            self.assertEqual(select_camera(2), 2)
        cv.VideoCapture.assert_called_once_with(2)
        capture.release.assert_called_once()
        cv.destroyAllWindows.assert_called_once()

    def test_picker_switches_then_selects_and_releases_both(self):
        cv = MagicMock()
        first, second = MagicMock(), MagicMock()
        for cap in (first, second):
            cap.isOpened.return_value = True
            cap.read.return_value = (True, object())
        cv.VideoCapture.side_effect = [first, second]
        cv.waitKey.side_effect = [ord("n"), 13]
        cv.getWindowProperty.return_value = 1
        with patch.dict("sys.modules", {"cv2": cv}):
            self.assertEqual(select_camera(), 1)
        first.release.assert_called_once()
        second.release.assert_called_once()

    def test_picker_cancel_releases(self):
        cv = MagicMock()
        cap = cv.VideoCapture.return_value
        cap.read.return_value = (True, object())
        cv.waitKey.return_value = ord("q")
        with patch.dict("sys.modules", {"cv2": cv}):
            with self.assertRaises(CameraSelectionCancelled):
                select_camera()
        cap.release.assert_called_once()

    def test_picker_no_cameras_releases_every_attempt(self):
        cv = MagicMock()
        cv.VideoCapture.return_value.isOpened.return_value = False
        with patch.dict("sys.modules", {"cv2": cv}):
            with self.assertRaises(CameraDisconnectedError):
                select_camera(max_devices=3)
        self.assertEqual(cv.VideoCapture.return_value.release.call_count, 3)

    def test_preview_quit_releases_capture_and_closes_window(self):
        cv, capture, mp, _ = self.fake_modules()
        capture.read.return_value = (True, object())
        cv.waitKey.return_value = ord("q")
        with patch.dict("sys.modules", {"cv2": cv, "mediapipe": mp}), patch("pathlib.Path.is_file", return_value=True):
            samples = list(landmark_frames("fake.task", preview=True))
        self.assertEqual(len(samples), 1)
        cv.imshow.assert_called_once()
        cv.destroyAllWindows.assert_called_once()
        capture.release.assert_called_once()

    def fake_modules(self):
        cv = MagicMock()
        capture = cv.VideoCapture.return_value
        capture.isOpened.return_value = True
        capture.read.return_value = (False, None)
        mp = MagicMock()
        detector = mp.tasks.vision.HandLandmarker.create_from_options.return_value.__enter__.return_value
        detector.detect_for_video.return_value = SimpleNamespace(hand_landmarks=[])
        return cv, capture, mp, detector

    def test_open_failure_releases_capture(self):
        cv, capture, mp, _ = self.fake_modules()
        capture.isOpened.return_value = False
        with patch.dict("sys.modules", {"cv2": cv, "mediapipe": mp}), patch("pathlib.Path.is_file", return_value=True):
            with self.assertRaises(CameraDisconnectedError):
                next(landmark_frames("fake.task"))
        capture.release.assert_called_once()

    def test_read_failure_has_explicit_reason_and_releases(self):
        cv, capture, mp, _ = self.fake_modules()
        with patch.dict("sys.modules", {"cv2": cv, "mediapipe": mp}), patch("pathlib.Path.is_file", return_value=True):
            samples = list(landmark_frames("fake.task"))
        self.assertEqual(samples[0].loss_reason, "capture_failed")
        capture.release.assert_called_once()

    def test_model_failure_releases(self):
        cv, capture, mp, _ = self.fake_modules()
        mp.tasks.vision.HandLandmarker.create_from_options.side_effect = ValueError("bad model")
        with patch.dict("sys.modules", {"cv2": cv, "mediapipe": mp}), patch("pathlib.Path.is_file", return_value=True):
            with self.assertRaises(ModelError):
                next(landmark_frames("fake.task"))
        capture.release.assert_called_once()

    def test_close_releases_and_mirroring_is_explicit(self):
        cv, capture, mp, _ = self.fake_modules()
        capture.read.return_value = (True, object())
        with patch.dict("sys.modules", {"cv2": cv, "mediapipe": mp}), patch("pathlib.Path.is_file", return_value=True):
            frames = landmark_frames("fake.task", mirror=True)
            next(frames)
            frames.close()
        cv.flip.assert_called_once()
        capture.release.assert_called_once()
