import unittest
import sys
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from airdesk_gesture.air_server import fresh_camera_sample
from airdesk_gesture.camera import CameraSample


class AirSourceFreshnessTests(unittest.TestCase):
    def test_panel_keeps_legacy_camera_available_when_air_handedness_is_unsupported(self):
        from airdesk_gesture.desktop_runtime import DesktopRuntime
        def empty_stream(*args, **kwargs):
            if False:
                yield
        for version, expected in [("0.10.35", 2), ("0.10.4", 1), (None, 1)]:
            with self.subTest(version=version), patch.dict(sys.modules, {"mediapipe": SimpleNamespace(__version__=version)}), patch(
                "airdesk_gesture.desktop_runtime.landmark_frames", side_effect=empty_stream,
            ) as frames:
                runtime = DesktopRuntime(Path("synthetic-project"), lambda *_: None)
                runtime._camera(0, Path("synthetic.task"))
                self.assertEqual(frames.call_args.kwargs["max_hands"], expected)

    def test_fresh_sample_preserves_observation_but_duplicate_releases(self):
        sample = CameraSample(1000, [(0.2, 0.3)] * 21, 1.0)
        self.assertIs(fresh_camera_sample(sample, 1100, 999), sample)
        rejected = fresh_camera_sample(sample, 1100, 1000)
        self.assertIsNone(rejected.landmarks)
        self.assertEqual(rejected.hands, ())
        self.assertEqual(rejected.loss_reason, "tracking_timeout")

    def test_old_future_regressing_and_boolean_capture_clocks_cannot_refresh_held_state(self):
        for timestamp, now, previous in [(1000, 1251, 999), (1001, 1000, 999), (999, 1010, 1000), (True, 1010, 0)]:
            with self.subTest(timestamp=timestamp):
                rejected = fresh_camera_sample(CameraSample(timestamp, [(0.2, 0.3)] * 21), now, previous)
                self.assertIsNone(rejected.landmarks)
                self.assertEqual(rejected.timestamp_ms, now)
                self.assertEqual(rejected.loss_reason, "tracking_timeout")
