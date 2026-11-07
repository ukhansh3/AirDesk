"""Headless camera-marker stabilization checks; no webcam or GUI is opened."""
from math import sin
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch

from airdesk_gesture.camera import CameraHand, landmark_frames
from airdesk_gesture.camera_overlay import CameraOverlayStabilizer


def hand(side="left", x=.3, y=.4):
    return CameraHand(side, tuple((x + index * .001, y + index * .001, 0.) for index in range(21)), .99)


class CameraOverlayTests(unittest.TestCase):
    def test_stationary_noise_is_suppressed_without_mutating_observations(self):
        overlay = CameraOverlayStabilizer()
        raw, drawn = [], []
        for i in range(100):
            observation = hand(x=.3 + .006 * sin(i * 2.1))
            raw.append(observation.landmarks[8][0])
            drawn.append(overlay.update((observation,), i * 33)[0].points[8][0])
            self.assertEqual(observation.landmarks[8][0], raw[-1])
        self.assertLess(max(drawn[20:]) - min(drawn[20:]), (max(raw[20:]) - min(raw[20:])) * .7)

    def test_missing_invalid_and_duplicate_hands_drop_markers_immediately(self):
        overlay = CameraOverlayStabilizer()
        self.assertTrue(overlay.update((hand(),), 0))
        self.assertFalse(overlay.update((), 40))
        self.assertEqual(overlay.update((hand(x=.7),), 80)[0].points[0], (.7, .4))
        invalid = CameraHand("left", ((float("nan"), .4, 0.),) * 21, .99)
        self.assertFalse(overlay.update((invalid,), 120))
        self.assertFalse(overlay.update((hand(), hand()), 160))
        self.assertEqual(overlay.update((hand(x=.2),), 200)[0].points[0], (.2, .4))

    def test_reordered_hands_do_not_share_filter_state(self):
        overlay = CameraOverlayStabilizer()
        left, right = hand("left", .2), hand("right", .7)
        overlay.update((left, right), 0)
        values = {h.handedness: h.points[0] for h in overlay.update((right, left), 40)}
        self.assertEqual(values, {"left": (.2, .4), "right": (.7, .4)})

    def test_raw_wrist_jump_is_not_hidden_by_old_smoothed_coordinates(self):
        overlay = CameraOverlayStabilizer()
        overlay.update((hand(x=.2),), 0)
        self.assertFalse(overlay.update((hand(x=.7),), 40))
        self.assertEqual(overlay.update((hand(x=.7),), 80)[0].points[0], (.7, .4))

    def test_clock_or_gap_reset_never_trails_an_old_hand(self):
        overlay = CameraOverlayStabilizer()
        overlay.update((hand(x=.2),), 100)
        self.assertFalse(overlay.update((hand(x=.2),), 99))
        self.assertEqual(overlay.update((hand(x=.6),), 110)[0].points[0], (.6, .4))
        self.assertEqual(overlay.update((hand(x=.3),), 1000)[0].points[0], (.3, .4))

    def test_camera_draws_filtered_points_but_yields_original_legacy_and_air_samples(self):
        cv = MagicMock(); mp = MagicMock(); mp.__version__ = "0.10.35"
        capture = cv.VideoCapture.return_value; capture.isOpened.return_value = True
        capture.read.return_value = (True, SimpleNamespace(shape=(1000, 1000, 3)))
        detector = mp.tasks.vision.HandLandmarker.create_from_options.return_value.__enter__.return_value
        observations = [hand(x=.3 + .006 * sin(i * 2.1)) for i in range(60)]
        detector.detect_for_video.side_effect = [SimpleNamespace(hand_landmarks=[
            [SimpleNamespace(x=x, y=y, z=z) for x, y, z in observation.landmarks]], handedness=[])
            for observation in observations]
        with patch.dict("sys.modules", {"cv2": cv, "mediapipe": mp}), patch("pathlib.Path.is_file", return_value=True), patch(
            "airdesk_gesture.camera.monotonic", side_effect=[1 + i * .033 for i in range(60)],
        ):
            samples = list(landmark_frames("synthetic.task", mirror=False, max_frames=60, frame_callback=lambda _: None))
        self.assertEqual(len(samples), 60)
        for sample, observation in zip(samples, observations):
            self.assertEqual(sample.landmarks, [(x, y) for x, y, _ in observation.landmarks])
            self.assertEqual(sample.hands[0].landmarks, observation.landmarks)
        drawn = [cv.circle.call_args_list[i * 26 + 8].args[1][0] for i in range(20, 60)]
        raw = [observation.landmarks[8][0] * 1000 for observation in observations[20:]]
        self.assertLess(max(drawn) - min(drawn), (max(raw) - min(raw)) * .7)
        cv.imshow.assert_not_called()
        capture.release.assert_called_once()
