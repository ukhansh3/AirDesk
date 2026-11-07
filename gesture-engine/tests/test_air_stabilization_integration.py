"""Synthetic raw-observation -> AIR payload -> PC preview stabilization checks.

No webcam, Tk window or recognition-accuracy measurement. Classification inputs
remain raw; these tests verify that stabilization reaches transmitted coordinates.
"""
from math import sin, cos, nan
import unittest
from unittest.mock import patch

from airdesk_gesture.air_typing import AirTypingEngine, FINGERS
from airdesk_gesture.camera import CameraHand, CameraSample
from airdesk_gesture.keyboard_preview import (
    WIDTH, HEIGHT, KeyboardGeometry, KeyboardPreviewModel, KeyboardPreviewWindow,
)
from test_air_typing import observed


class AirStabilizationIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.engine = AirTypingEngine()
        self.now = 1000
        self.preview = KeyboardPreviewModel(lambda: self.now / 1000.)

    def frame(self, hands=None, advance=40):
        self.now += advance
        value = self.engine.process(CameraSample(self.now, None, hands=tuple(hands) if hands is not None else
                                                (observed("left"), observed("right"))))
        self.preview.update(value)
        return value

    def arm(self):
        # Deterministic manual fallback creates a separable baseline; guided
        # automatic capture has separate model tests and uses the same features.
        poses = [()] + [(side, finger) for side in ("left", "right") for finger in FINGERS]
        for pose in poses:
            self.engine.command("capture", self.now)
            hands = tuple(observed(side, (pose[1],) if pose and pose[0] == side else ()) for side in ("left", "right"))
            self.frame(hands, advance=1500)
            for _ in range(14): value = self.frame(hands)
        self.assertEqual(value["stage"], "READY")
        self.engine.command("arm", self.now)
        for _ in range(8): value = self.frame()
        self.assertTrue(value["armed"])

    @staticmethod
    def finger(value, side="left", finger="index"):
        return next(f for f in value["fingers"] if f["hand"] == side and f["finger"] == finger)

    def test_stationary_raw_xy_noise_is_reduced_in_payload_and_not_reexpanded_by_preview(self):
        self.arm()
        raw, transmitted, shown = [], [], []
        for index in range(160):
            dx, dy = .002 * sin(index * 1.7), .002 * cos(index * 1.3)
            hands = tuple(CameraHand(side, tuple((x + dx, y + dy, z) for x, y, z in observed(side).landmarks), .99)
                          for side in ("left", "right"))
            value = self.frame(hands)
            raw.append(hands[0].landmarks[8][0])
            transmitted.append(self.finger(value)["x"])
            shown.append(next(f.x for f in self.preview.state.fingers if f.hand == "left" and f.finger == "index"))
            self.assertFalse(any(f["pressed"] for f in value["fingers"]))
            self.assertFalse(self.preview.state.pressed_keys)
        self.assertGreater(max(raw) - min(raw), .003)
        self.assertLess(max(transmitted) - min(transmitted), (max(raw) - min(raw)) * .5)
        self.assertEqual(transmitted, shown)

        # Exercise the real drawing adapter under mocked Tk. The estimate moves
        # by one design-space projection, not another gain or display-only filter.
        with patch("airdesk_gesture.keyboard_preview.tk.Toplevel") as top, patch("airdesk_gesture.keyboard_preview.tk.Canvas") as factory:
            top.return_value.winfo_exists.return_value = True
            canvas = factory.return_value
            canvas.winfo_width.return_value = 1200; canvas.winfo_height.return_value = 500
            view = KeyboardPreviewWindow(object())
            view.update(value)
            circles = canvas.create_oval.call_args_list
            self.assertEqual(len(circles), 10)
            fit = KeyboardGeometry.fit(1176, 400, 12, 88)
            for call, finger in zip(circles, view.model.state.fingers):
                left, top_y, right, bottom = call.args
                expected_x, expected_y = fit.point(finger.x * WIDTH, finger.y * HEIGHT)
                self.assertAlmostEqual((left + right) / 2, expected_x)
                self.assertAlmostEqual((top_y + bottom) / 2, expected_y)
            view.close()

    def test_small_independent_joint_noise_does_not_create_downs_in_armed_state(self):
        self.arm()
        for index in range(180):
            hands = []
            for ordinal, side in enumerate(("left", "right")):
                points = tuple((x + .0015 * sin(index * 1.2 + joint + ordinal),
                                y + .0015 * cos(index * .9 + joint),
                                z + .0005 * sin(index + joint * .7))
                               for joint, (x, y, z) in enumerate(observed(side).landmarks))
                hands.append(CameraHand(side, points, .99))
            value = self.frame(hands)
            self.assertEqual(len(value["fingers"]), 10)
            self.assertFalse(any(f["pressed"] for f in value["fingers"]))
            self.assertFalse(self.preview.state.pressed_keys)

    def test_raw_invalid_loss_and_epoch_reset_cannot_keep_old_filtered_dots(self):
        self.arm()
        value = self.frame()
        self.assertEqual(len(self.preview.state.fingers), 10)
        bad = list(observed("left").landmarks); bad[8] = (nan, .3, 0.)
        value = self.frame((CameraHand("left", tuple(bad), .99), observed("right")))
        self.assertFalse(any(f["hand"] == "left" for f in value["fingers"]))
        self.assertFalse(any(f.hand == "left" for f in self.preview.state.fingers))
        self.frame(())
        self.assertFalse(self.preview.state.fingers)
        self.assertFalse(self.preview.state.pressed_keys)
        previous_epoch = value["epoch"]
        self.engine.command("reset", self.now)
        moved = observed("left", wrist_x=.35)
        value = self.frame((moved, observed("right")))
        self.assertNotEqual(value["epoch"], previous_epoch)
        self.assertFalse(value["armed"])
        self.assertAlmostEqual(self.finger(value)["x"], moved.landmarks[8][0])
        self.assertFalse(self.preview.state.pressed_keys)


if __name__ == "__main__":
    unittest.main()
