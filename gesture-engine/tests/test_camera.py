"""Resource/error tests with fake native modules, NOT camera hardware evidence."""
import unittest
from types import SimpleNamespace
from unittest.mock import MagicMock, patch
from airdesk_gesture.camera import landmark_frames, CameraDisconnectedError, ModelError


class CameraTests(unittest.TestCase):
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
