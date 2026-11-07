import unittest
from pathlib import Path

from airdesk_gesture.desktop_runtime import DesktopRuntime


class AirPreviewRuntimeTests(unittest.TestCase):
    def test_only_latest_detached_estimate_is_retained(self):
        runtime = DesktopRuntime(Path("synthetic-project"), lambda *_: None)
        for number in range(100):
            payload = {"epoch": f"synthetic-{number}", "fingers": [{"x": .25}]}
            runtime.publish_air_frame(payload)
        payload["fingers"][0]["x"] = .75
        self.assertEqual(runtime.air_frames.qsize(), 1)
        latest = runtime.air_frames.get_nowait()
        self.assertEqual(latest["epoch"], "synthetic-99")
        self.assertEqual(latest["fingers"][0]["x"], .25)
        self.assertFalse(runtime.busy)

    def test_stop_clears_preview_and_rejects_late_estimates(self):
        runtime = DesktopRuntime(Path("synthetic-project"), lambda *_: None)
        runtime.publish_air_frame({"armed": True})
        runtime.stop()
        runtime.publish_air_frame({"armed": True})
        self.assertEqual(runtime.air_frames.qsize(), 1)
        self.assertIsNone(runtime.air_frames.get_nowait())
        runtime.stop()
        self.assertIsNone(runtime.air_frames.get_nowait())
