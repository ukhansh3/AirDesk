"""Headless PC panel tests. No real Tk windows, cameras or emulator launches."""
import asyncio
import threading
import unittest
from pathlib import Path
from unittest.mock import patch

from airdesk_gesture.camera import CameraSample, ModelError
from airdesk_gesture.desktop_runtime import DesktopRuntime, LatestSamples, SetupError, find_model, read_app_token


class SettingsTests(unittest.TestCase):
    def test_existing_app_token_loaded_with_last_property_winning(self):
        current = "b" * 32
        content = "sdk.dir=C:/sdk\n# AIRDESK_DEV_TOKEN=ignored\nAIRDESK_DEV_TOKEN=" + "a" * 32 + "\nAIRDESK_DEV_TOKEN=" + current
        with patch.object(Path, "read_text", return_value=content):
            self.assertEqual(read_app_token(Path("project")), current)

    def test_missing_token_never_generates_one(self):
        with patch.object(Path, "read_text", return_value="sdk.dir=C:/sdk\n"):
            with self.assertRaises(SetupError):
                read_app_token(Path("project"))

    def test_secret_not_exposed_in_error(self):
        secret = "SHORT_SECRET"
        with patch.object(Path, "read_text", return_value="AIRDESK_DEV_TOKEN=" + secret):
            with self.assertRaises(SetupError) as caught:
                read_app_token(Path("project"))
        self.assertNotIn(secret, str(caught.exception))

    def test_missing_properties_explains_configuration_problem(self):
        with patch.object(Path, "read_text", side_effect=FileNotFoundError):
            with self.assertRaises(SetupError):
                read_app_token(Path("project"))

    def test_model_missing_requires_user_selection(self):
        with patch.object(Path, "is_file", return_value=False):
            with self.assertRaises(SetupError):
                find_model(Path("project"))


class LatestSamplesTests(unittest.TestCase):
    def test_backlog_is_replaced_by_latest_sample(self):
        source = LatestSamples()
        source.publish(CameraSample(1, None))
        source.publish(CameraSample(2, None))
        samples = source.samples()
        self.assertEqual(next(samples).timestamp_ms, 2)
        source.close()
        with self.assertRaises(StopIteration):
            next(samples)

    def test_close_unblocks_waiting_receiver(self):
        source = LatestSamples()
        ready = threading.Event()
        finished = threading.Event()
        def consume():
            ready.set()
            list(source.samples())
            finished.set()
        thread = threading.Thread(target=consume, daemon=True)
        thread.start()
        try:
            self.assertTrue(ready.wait(1))
            source.close()
            self.assertTrue(finished.wait(1))
        finally:
            source.close()
            thread.join(1)

    def test_model_error_survives_subsequent_stop(self):
        source = LatestSamples()
        source.close(ModelError("fake model"))
        source.close()
        with self.assertRaises(ModelError):
            next(source.samples())


class DesktopRuntimeTests(unittest.TestCase):
    def test_construction_does_not_start_camera(self):
        runtime = DesktopRuntime(Path("project"), lambda *_: None)
        self.assertFalse(runtime.busy)
        self.assertTrue(runtime.frames.empty())

    def test_missing_settings_do_not_launch_threads(self):
        runtime = DesktopRuntime(Path("project"), lambda *_: None)
        with patch.object(Path, "is_file", return_value=True), patch.object(Path, "read_text", return_value=""), patch("threading.Thread") as thread:
            with self.assertRaises(SetupError):
                runtime.start(0, Path("model.task"))
            thread.assert_not_called()

    def test_server_gets_existing_token_without_environment_variable(self):
        runtime = DesktopRuntime(Path("project"), lambda *_: None)
        received = {}
        async def fake_server(**kwargs):
            received.update(kwargs)
            raise asyncio.CancelledError
        with patch("airdesk_gesture.ws_server.serve", side_effect=fake_server):
            runtime._server(2, Path("model.task"), "t" * 32)
        self.assertEqual(received["dev_token"], "t" * 32)
        self.assertEqual(received["device"], 2)
        self.assertEqual(received["frame_factory"], runtime._samples.samples)
        self.assertIsNone(runtime._loop)

    def test_stop_is_idempotent_and_closes_sample_stream(self):
        runtime = DesktopRuntime(Path("project"), lambda *_: None)
        runtime.stop()
        runtime.stop()
        self.assertEqual(list(runtime._samples.samples()), [])


if __name__ == "__main__":
    unittest.main()
