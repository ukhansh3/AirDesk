"""Pure geometry/state and mocked Tk adapter tests. Never opens a real window."""
from copy import deepcopy
import json
from pathlib import Path
import queue
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch

from airdesk_gesture.keyboard_preview import (
    WIDTH, HEIGHT, KEYS, FINGERS, KeyboardGeometry, KeyboardPreviewModel,
    KeyboardPreviewState, KeyboardPreviewWindow, calibration_text,
)


def key_point(code):
    key = next(k for k in KEYS if k.code == code)
    return ((key.bounds.x + key.bounds.width / 2) / WIDTH,
            (key.bounds.y + key.bounds.height / 2) / HEIGHT)


def payload(code="KeyQ", pressed=True):
    x, y = key_point(code)
    return {"epoch": "synthetic-preview-epoch", "stage": "READY", "status": "ARMED", "armed": True,
            "fingers": [{"hand": "left", "finger": "index", "x": x, "y": y, "pressed": pressed, "valid": True}],
            "mouse": {"active": False, "valid": False, "x": 0., "y": 0., "pressed": False, "dragging": False}}


class KeyboardPreviewGeometryTests(unittest.TestCase):
    def test_complete_key_metadata_matches_cross_display_fixture(self):
        fixture = json.loads((Path(__file__).resolve().parents[2] / "integration-tests/fixtures/pc-keyboard-geometry.json").read_text(encoding="utf-8"))
        actual = [{"code": key.code, "left": key.bounds.x, "top": key.bounds.y,
                   "width": key.bounds.width, "height": key.bounds.height} for key in KEYS]
        self.assertEqual((WIDTH, HEIGHT), (fixture["width"], fixture["height"]))
        self.assertEqual(actual, fixture["keys"])
        mouse = KeyboardGeometry.mouse
        self.assertEqual(fixture["mouse"], dict(left=mouse.x, top=mouse.y, width=mouse.width, height=mouse.height))
        self.assertEqual(len(KEYS), 81)
        self.assertEqual(len({key.code for key in KEYS}), 81)
        required = {"Escape", "Backspace", "Enter", "Tab", "CapsLock", "ShiftLeft", "ShiftRight",
                    "ControlLeft", "ControlRight", "AltLeft", "AltRight", "Space", "Lang", "Delete",
                    "Home", "End", "PageUp", "PageDown", "ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"}
        required |= {f"F{n}" for n in range(1, 13)} | {f"Digit{n}" for n in range(10)} | {"Key" + c for c in "ABCDEFGHIJKLMNOPQRSTUVWXYZ"}
        self.assertTrue(required <= {key.code for key in KEYS})

    def test_key_and_mouse_rectangles_never_overlap(self):
        bounds = [key.bounds for key in KEYS] + [KeyboardGeometry.mouse]
        for index, first in enumerate(bounds):
            self.assertTrue(0 <= first.x < first.right <= WIDTH)
            self.assertTrue(0 <= first.y < first.bottom <= HEIGHT)
            for second in bounds[index + 1:]:
                self.assertFalse(first.x < second.right and first.right > second.x and
                                 first.y < second.bottom and first.bottom > second.y)

    def test_resizing_fits_all_geometry_in_one_visible_rectangle(self):
        for width, height in ((1200, 500), (600, 260), (1920, 700), (340, 140)):
            with self.subTest(size=(width, height)):
                fit = KeyboardGeometry.fit(width, height)
                for bounds in [key.bounds for key in KEYS] + [KeyboardGeometry.mouse]:
                    left, top, right, bottom = fit.rect(bounds)
                    self.assertGreaterEqual(left, -.001); self.assertGreaterEqual(top, -.001)
                    self.assertLessEqual(right, width + .001); self.assertLessEqual(bottom, height + .001)

    def test_every_key_center_maps_back_to_it_and_gaps_do_not(self):
        for key in KEYS:
            self.assertEqual(KeyboardGeometry.hit(*key_point(key.code)), key.code)
        self.assertIsNone(KeyboardGeometry.hit(0, 0))
        self.assertIsNone(KeyboardGeometry.hit(786 / WIDTH, 220 / HEIGHT))
        self.assertIsNone(KeyboardGeometry.hit(.93, .5))
        for x, y in ((float("nan"), .5), (.5, float("inf")), (True, .5), (-.1, .5)):
            self.assertIsNone(KeyboardGeometry.hit(x, y))


class KeyboardPreviewStateTests(unittest.TestCase):
    def test_two_hands_and_same_key_are_estimates_without_text(self):
        value = payload()
        value["fingers"] = [dict(value["fingers"][0], hand=hand, finger=finger)
                            for hand in ("left", "right") for finger in FINGERS]
        state = KeyboardPreviewState.from_payload(value)
        self.assertEqual(len(state.fingers), 10)
        self.assertEqual(state.pressed_keys, {"KeyQ"})
        self.assertFalse(hasattr(state, "text"))
        self.assertFalse(hasattr(state, "token"))

    def test_disarmed_state_shows_hover_but_never_pressed_highlight(self):
        value = payload(pressed=False)
        value.update(armed=False, status="READY")
        state = KeyboardPreviewState.from_payload(value)
        self.assertEqual(len(state.fingers), 1)
        self.assertFalse(state.pressed_keys)
        self.assertFalse(state.armed)

    def test_invalid_frames_clear_observations_and_pressed_state(self):
        mutations = [
            lambda p: p["fingers"].append(deepcopy(p["fingers"][0])),
            lambda p: p["fingers"][0].update(x=float("nan")),
            lambda p: p["fingers"][0].update(y=1.1),
            lambda p: p["fingers"][0].update(pressed=1),
            lambda p: p["fingers"][0].update(valid=False),
            lambda p: p.update(armed=False, status="READY"),
            lambda p: p.update(stage="LEFT_INDEX"),
            lambda p: p.update(text="must-not-be-retained"),
            lambda p: p["mouse"].update(dragging=True),
        ]
        for mutate in mutations:
            value = payload(); mutate(value)
            state = KeyboardPreviewState.from_payload(value)
            self.assertFalse(state.fingers)
            self.assertFalse(state.pressed_keys)
            self.assertIsNone(state.stage)

    def test_complete_loss_reset_and_local_watchdog_release_everything(self):
        now = [1.]
        model = KeyboardPreviewModel(lambda: now[0])
        model.update(payload())
        self.assertTrue(model.state.pressed_keys)
        loss = payload(); loss["fingers"] = []
        model.update(loss)
        self.assertFalse(model.state.fingers); self.assertFalse(model.state.pressed_keys)
        model.update(payload()); model.update(None)
        self.assertIsNone(model.updated_at); self.assertFalse(model.state.fingers)
        model.update(payload()); now[0] += .501
        self.assertTrue(model.expire())
        self.assertFalse(model.state.pressed_keys); self.assertFalse(model.state.fingers)
        model.update(payload()); now[0] -= 1
        self.assertTrue(model.expire())

    def test_mouse_is_separate_and_requires_valid_armed_ownership(self):
        value = payload(pressed=False)
        value["mouse"].update(active=True, valid=True, x=.5, y=.5, pressed=True)
        state = KeyboardPreviewState.from_payload(value)
        self.assertEqual(state.mouse, (.5, .5, True))
        self.assertFalse(state.pressed_keys)
        value["fingers"][0]["hand"] = "right"
        self.assertFalse(KeyboardPreviewState.from_payload(value).fingers)

    def test_automatic_guide_uses_current_pose_and_explicit_final_enable(self):
        value = payload(pressed=False)
        value.update(armed=False, stage="LEFT_INDEX", status="WAITING")
        value["fingers"] = [dict(value["fingers"][0], hand=hand, finger=finger)
                            for hand in ("left", "right") for finger in FINGERS]
        text = calibration_text(KeyboardPreviewState.from_payload(value))
        self.assertIn("3/11", text); self.assertIn("왼손 검지", text); self.assertIn("아래로", text)
        self.assertNotIn("자세 확인", text)
        value.update(stage="READY", status="READY")
        self.assertIn("타건 켜기", calibration_text(KeyboardPreviewState.from_payload(value)))


class KeyboardPreviewAdapterTests(unittest.TestCase):
    def test_window_local_timer_clears_pressed_dots_without_network_callback(self):
        with patch("airdesk_gesture.keyboard_preview.tk.Toplevel") as top, patch("airdesk_gesture.keyboard_preview.tk.Canvas") as factory:
            top.return_value.winfo_exists.return_value = True
            canvas = factory.return_value
            canvas.winfo_width.return_value = 1200; canvas.winfo_height.return_value = 500
            view = KeyboardPreviewWindow(object())
            now = [1.]
            view.model.clock = lambda: now[0]
            view.update(payload())
            self.assertTrue(view.model.state.pressed_keys)
            now[0] += .501
            view._tick()
            self.assertFalse(view.model.state.fingers)
            self.assertFalse(view.model.state.pressed_keys)
            self.assertIsNone(view.model.updated_at)
            view.close()

    def test_window_adapter_draws_read_only_and_cancels_timer_on_close(self):
        with patch("airdesk_gesture.keyboard_preview.tk.Toplevel") as top, patch("airdesk_gesture.keyboard_preview.tk.Canvas") as canvas_factory:
            top.return_value.winfo_exists.return_value = True
            canvas = canvas_factory.return_value
            canvas.winfo_width.return_value = 1200; canvas.winfo_height.return_value = 500
            closed = MagicMock()
            view = KeyboardPreviewWindow(object(), on_close=closed)
            canvas.reset_mock()
            view.update(payload())
            self.assertEqual(canvas.create_rectangle.call_count, len(KEYS) + 1)
            self.assertEqual(canvas.create_oval.call_count, 1)
            top.return_value.mainloop.assert_not_called()
            view.close()
            top.return_value.after_cancel.assert_called_once()
            top.return_value.destroy.assert_called_once(); closed.assert_called_once()
            view.update(payload())
            self.assertFalse(view.model.state.fingers)

    def test_panel_poll_drains_latest_clears_none_and_reopen_drops_stale_state(self):
        from airdesk_gesture.desktop import DesktopApp
        app = DesktopApp.__new__(DesktopApp)
        app.runtime = SimpleNamespace(air_frames=queue.Queue())
        app._air_display_enabled = True
        app.keyboard_view = MagicMock(); app.keyboard_view.is_open = True
        app.last_air_payload = None; app.last_air_received_at = None
        app.runtime.air_frames.put(payload()); app.runtime.air_frames.put(None)
        with patch("airdesk_gesture.desktop.time.monotonic", return_value=1.):
            app._poll_air_frames()
        app.keyboard_view.update.assert_called_once_with(None)
        app.runtime.air_frames.put(payload())
        with patch("airdesk_gesture.desktop.time.monotonic", return_value=1.): app._poll_air_frames()
        self.assertIsNotNone(app.last_air_payload)
        with patch("airdesk_gesture.desktop.time.monotonic", return_value=1.501): app._poll_air_frames()
        self.assertIsNone(app.last_air_payload)
        app.keyboard_view = None; app.root = object()
        app.last_air_payload = payload(); app.last_air_received_at = 0.
        with patch("airdesk_gesture.desktop.time.monotonic", return_value=2.), patch("airdesk_gesture.keyboard_preview.KeyboardPreviewWindow") as window:
            app.open_keyboard_window()
            window.return_value.update.assert_called_once_with(None)


if __name__ == "__main__":
    unittest.main()
