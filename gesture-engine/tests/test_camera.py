"""Resource/error tests with fake native modules, NOT camera hardware evidence."""
import unittest
from types import SimpleNamespace
from unittest.mock import MagicMock, patch
from airdesk_gesture.camera import landmark_frames, CameraDisconnectedError, ModelError, select_camera, CameraSelectionCancelled, supports_air_handedness


class CameraTests(unittest.TestCase):
    def test_shared_camera_legacy_pointer_stays_on_bound_hand_through_reorder_and_loss(self):
        cv, capture, mp, detector = self.fake_modules()
        capture.read.return_value = (True, object())
        def result(sides):
            return SimpleNamespace(hand_landmarks=[
                [SimpleNamespace(x=.2 if side == "Left" else .7, y=.3, z=0.) for _ in range(21)] for side in sides],
                handedness=[[SimpleNamespace(category_name=side, score=.99)] for side in sides])
        detector.detect_for_video.side_effect = [result(["Left", "Right"]), result(["Right", "Left"]),
                                                result(["Right"]), result(["Right", "Left"]), result(["Left", "Left"])]
        with patch.dict("sys.modules", {"cv2": cv, "mediapipe": mp}), patch("pathlib.Path.is_file", return_value=True):
            samples = list(landmark_frames("fake.task", max_hands=2, mirror=False, max_frames=5))
        self.assertEqual([s.landmarks[0][0] if s.landmarks else None for s in samples], [.2, .2, None, .2, None])
        self.assertEqual(samples[2].confidence, 0.)
        self.assertEqual(samples[2].hands[0].handedness, "right")
        self.assertEqual(len(samples[3].hands), 2)
        capture.release.assert_called_once()

    def test_air_handedness_helper_is_pure_and_fails_closed_for_unknown_version(self):
        for version in ("0.10.5", "0.10.35", "0.10.35+local", "1.2.2"):
            self.assertTrue(supports_air_handedness(version), version)
        for version in ("0.10.4", "0.9.3", "unknown", "0.10", None, True, MagicMock()):
            self.assertFalse(supports_air_handedness(version), str(version))

    def test_current_tasks_unmirrored_labels_remain_subject_left_and_right(self):
        cv, capture, mp, detector = self.fake_modules()
        capture.read.return_value = (True, object())
        points = [[SimpleNamespace(x=.2+side*.4, y=.3, z=0.) for _ in range(21)] for side in range(2)]
        detector.detect_for_video.return_value = SimpleNamespace(hand_landmarks=points, handedness=[
            [SimpleNamespace(category_name="Left", score=.98)], [SimpleNamespace(category_name="Right", score=.97)]])
        with patch.dict("sys.modules", {"cv2": cv, "mediapipe": mp}), patch("pathlib.Path.is_file", return_value=True):
            sample = next(landmark_frames("fake.task", max_hands=2, mirror=False, max_frames=1))
        self.assertEqual([h.handedness for h in sample.hands], ["left", "right"])
        cv.flip.assert_not_called()

    def test_two_hand_version_guard_blocks_old_or_unknown_before_opening_camera(self):
        for version in ("0.10.4", "0.9.3", "unknown", None, MagicMock()):
            with self.subTest(version=str(version)):
                cv, _, mp, _ = self.fake_modules()
                mp.__version__ = version
                with patch.dict("sys.modules", {"cv2": cv, "mediapipe": mp}):
                    with self.assertRaisesRegex(ModelError, ">= 0.10.5"):
                        next(landmark_frames("fake.task", max_hands=2))
                cv.VideoCapture.assert_not_called()

    def test_two_hand_guard_accepts_boundary_version_and_unknown_legacy_still_runs(self):
        for version, count in (("0.10.5", 2), ("0.10.4", 1), (None, 1)):
            cv, capture, mp, _ = self.fake_modules()
            mp.__version__ = version
            with patch.dict("sys.modules", {"cv2": cv, "mediapipe": mp}), patch("pathlib.Path.is_file", return_value=True):
                sample = next(landmark_frames("fake.task", max_hands=count))
            self.assertEqual(sample.loss_reason, "capture_failed")
            capture.release.assert_called_once()

    def test_two_hand_observations_are_labelled_xyz_and_legacy_first_xy_is_unchanged(self):
        cv, capture, mp, detector = self.fake_modules()
        frame = SimpleNamespace(shape=(480, 640, 3))
        capture.read.return_value = (True, frame)
        cv.flip.return_value = frame
        points = [[SimpleNamespace(x=.2+side*.4, y=.3, z=-.05) for _ in range(21)] for side in range(2)]
        detector.detect_for_video.return_value = SimpleNamespace(hand_landmarks=points, handedness=[
            [SimpleNamespace(category_name="Left", score=.98)], [SimpleNamespace(category_name="Right", score=.97)]])
        with patch.dict("sys.modules", {"cv2": cv, "mediapipe": mp}), patch("pathlib.Path.is_file", return_value=True):
            sample = next(landmark_frames("fake.task", max_hands=2, mirror=True, max_frames=1, frame_callback=lambda _: None))
        self.assertEqual(mp.tasks.vision.HandLandmarkerOptions.call_args.kwargs["num_hands"], 2)
        self.assertEqual(sample.landmarks, [(.2, .3)]*21)
        self.assertEqual([h.handedness for h in sample.hands], ["right", "left"])
        self.assertEqual(sample.hands[0].landmarks[0], (.2, .3, -.05))
        self.assertEqual(sample.hands[0].y_scale, .75)
        self.assertEqual(cv.circle.call_count, 52)

    def test_missing_handedness_is_unknown_not_result_index_identity(self):
        cv, capture, mp, detector = self.fake_modules()
        capture.read.return_value = (True, object())
        detector.detect_for_video.return_value = SimpleNamespace(hand_landmarks=[
            [SimpleNamespace(x=.2, y=.3) for _ in range(21)]])
        with patch.dict("sys.modules", {"cv2": cv, "mediapipe": mp}), patch("pathlib.Path.is_file", return_value=True):
            sample = next(landmark_frames("fake.task", mirror=False, max_frames=1))
        self.assertEqual(sample.hands[0].handedness, "unknown")
        self.assertEqual(sample.hands[0].score, 0.)
        self.assertEqual(sample.hands[0].landmarks[0][2], 0.)
        self.assertEqual(mp.tasks.vision.HandLandmarkerOptions.call_args.kwargs["num_hands"], 1)

    def test_invalid_hand_count_is_rejected_before_opening_camera(self):
        for count in (0, 3, True, 1.5):
            with self.assertRaises(ValueError):
                next(landmark_frames("fake.task", max_hands=count))

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
        mp.__version__ = "0.10.35"
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
