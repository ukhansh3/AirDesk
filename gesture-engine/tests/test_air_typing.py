"""Synthetic AIR typing specification. No camera, physical contact or accuracy proof.

The model must calibrate all ten fingers, reject inseparable poses, keep each
finger's Down until release without repeat, and require neutral after loss.
Only the side pad may create mouse state; typing and mouse ownership are disjoint.
"""
import unittest

from airdesk_gesture.air_typing import AirTypingEngine, FINGERS
from airdesk_gesture.camera import CameraHand, CameraSample


def observed(side, bent=(), wrist_x=None, x_scale=1.):
    center = (.26 if side == "left" else .65) if wrist_x is None else wrist_x
    p = [(center, .82, 0.0)] * 21
    p[0] = (center, .82, 0.0)
    p[1], p[2], p[3], p[4] = ((center-.06, .72, 0.0), (center-.1, .64, 0.0),
                              (center-.16, .6, 0.0), (center-.22, .56, 0.0))
    for name, base, offset in zip(FINGERS[1:], (5, 9, 13, 17), (-.08, 0, .07, .13)):
        for joint in range(4):
            p[base+joint] = (center+offset, .55-joint*.105, 0.0)
        if name in bent:
            p[base+1] = (center+offset, .45, 0.0)
            p[base+2] = (center+offset, .51, .10)
            p[base+3] = (center+offset, .58, .16)
    if "thumb" in bent:
        p[4] = (center-.08, .67, .06)
    p = [(center+(x-center)*x_scale, y, z) for x, y, z in p]
    return CameraHand(side, tuple(p), .99)


class AirTypingTests(unittest.TestCase):
    def setUp(self):
        self.engine = AirTypingEngine()
        self.now = 1000

    def frame(self, left=(), right=(), hands=None, advance=40):
        self.now += advance
        sample = CameraSample(self.now, None, hands=tuple(hands) if hands is not None else
                              (observed("left", left), observed("right", right)))
        return self.engine.process(sample)

    def capture(self, left=(), right=(), count=14):
        self.engine.command("capture", self.now)
        self.frame(left, right, advance=1500)
        for _ in range(count):
            value = self.frame(left, right)
        return value

    def calibrate(self):
        self.engine.command("start_calibration", self.now)
        self.capture()
        for hand in ("left", "right"):
            for finger in FINGERS:
                self.capture((finger,) if hand == "left" else (), (finger,) if hand == "right" else ())
        self.assertEqual(self.engine.process(CameraSample(self.now+1, None))["stage"], "READY")
        self.now += 1

    def arm(self):
        self.calibrate()
        self.engine.command("arm", self.now)
        for _ in range(8):
            value = self.frame()
        return value

    @staticmethod
    def finger(payload, hand, name):
        return next(f for f in payload["fingers"] if f["hand"] == hand and f["finger"] == name)

    def test_uncalibrated_arm_is_rejected_and_payload_is_explicit(self):
        self.engine.command("arm", self.now)
        value = self.frame()
        self.assertFalse(value["armed"])
        self.assertEqual(set(value), {"epoch", "stage", "status", "armed", "fingers", "mouse"})
        self.assertEqual(len(value["fingers"]), 10)
        self.assertTrue(all(not f["pressed"] for f in value["fingers"]))

    def test_capture_countdown_then_neutral_and_all_ten_stages(self):
        self.engine.command("start_calibration", self.now)
        self.engine.command("capture", self.now)
        self.assertEqual(self.frame()["status"], "COUNTDOWN")
        self.frame(advance=1460)
        self.assertEqual(self.frame()["status"], "CAPTURING")
        for _ in range(14): value = self.frame()
        self.assertEqual(value["stage"], "LEFT_THUMB")
        self.assertEqual(value["status"], "WAITING")

    def test_inseparable_flex_fails_same_stage_then_can_retry(self):
        self.capture()
        value = self.capture()
        self.assertEqual(value["stage"], "LEFT_THUMB")
        self.assertEqual(value["status"], "FAILED")
        self.assertEqual(self.capture(left=("thumb",))["stage"], "LEFT_INDEX")

    def test_other_finger_motion_during_calibration_fails_separability(self):
        self.capture()
        value = self.capture(left=("thumb", "index"))
        self.assertEqual(value["stage"], "LEFT_THUMB")
        self.assertEqual(value["status"], "FAILED")

    def test_cannot_arm_half_calibrated_and_must_release_after_arm(self):
        self.capture()
        self.engine.command("arm", self.now)
        self.assertFalse(self.frame()["armed"])
        self.calibrate()
        self.engine.command("arm", self.now)
        for _ in range(8): value = self.frame(left=("index",))
        self.assertFalse(self.finger(value, "left", "index")["pressed"])
        for _ in range(8): self.frame()
        for _ in range(4): value = self.frame(left=("index",))
        self.assertTrue(self.finger(value, "left", "index")["pressed"])

    def test_too_few_or_missing_samples_cannot_calibrate(self):
        self.engine.command("capture", self.now)
        self.frame(advance=1500)
        self.frame(hands=(), advance=510)
        self.assertEqual(self.frame()["status"], "FAILED")
        self.assertEqual(self.frame()["stage"], "NEUTRAL")

    def test_thumb_and_index_features_are_independently_separable(self):
        base = self.engine.features(observed("left"))
        thumb = self.engine.features(observed("left", ("thumb",)))
        index = self.engine.features(observed("left", ("index",)))
        self.assertGreater(thumb["thumb"]-base["thumb"], .08)
        self.assertEqual(thumb["index"], base["index"])
        self.assertGreater(index["index"]-base["index"], .08)
        self.assertEqual(index["thumb"], base["thumb"])

    def test_both_hands_can_press_multiple_fingers_without_global_debounce(self):
        self.arm()
        for _ in range(4): value = self.frame(left=("index", "middle"), right=("ring",))
        for side, name in (("left", "index"), ("left", "middle"), ("right", "ring")):
            self.assertTrue(self.finger(value, side, name)["pressed"])
        held = [self.frame(left=("index", "middle"), right=("ring",)) for _ in range(8)]
        self.assertTrue(all(self.finger(f, "left", "index")["pressed"] for f in held))
        for _ in range(4): value = self.frame()
        self.assertTrue(all(not f["pressed"] for f in value["fingers"]))

    def test_one_hand_loss_releases_it_while_other_pressed_hand_remains(self):
        self.arm()
        for _ in range(4): self.frame(left=("index",), right=("index",))
        value = self.frame(hands=(observed("right", ("index",)),))
        self.assertTrue(self.finger(value, "right", "index")["pressed"])
        self.assertFalse(any(f["hand"] == "left" and f["pressed"] for f in value["fingers"]))
        for _ in range(6): value = self.frame(left=("index",), right=("index",))
        self.assertFalse(self.finger(value, "left", "index")["pressed"])
        for _ in range(8): self.frame()
        for _ in range(4): value = self.frame(left=("index",))
        self.assertTrue(self.finger(value, "left", "index")["pressed"])

    def test_short_bounce_does_not_press_and_captured_hover_stays_bound(self):
        self.arm()
        self.frame(left=("index",))
        value = self.frame()
        self.assertFalse(self.finger(value, "left", "index")["pressed"])
        hover = self.finger(value, "left", "index")
        for _ in range(4): value = self.frame(left=("index",))
        captured = self.finger(value, "left", "index")
        self.assertEqual((captured["x"], captured["y"]), (hover["x"], hover["y"]))
        moved = observed("left", ("index",), wrist_x=.28)
        value = self.frame(hands=(moved, observed("right")))
        self.assertEqual(self.finger(value, "left", "index")["x"], captured["x"])

    def test_excessive_hold_releases_and_does_not_repeat_while_flexed(self):
        self.arm()
        for _ in range(4): self.frame(left=("index",))
        for _ in range(23): value = self.frame(left=("index",), advance=250)
        self.assertFalse(self.finger(value, "left", "index")["pressed"])
        for _ in range(8): value = self.frame(left=("index",))
        self.assertFalse(self.finger(value, "left", "index")["pressed"])

    def test_duplicate_handedness_and_crossing_cancel_without_new_identity(self):
        self.arm()
        for _ in range(4): self.frame(left=("index",))
        value = self.frame(hands=(observed("left"), observed("left", wrist_x=.65)))
        self.assertFalse(any(f["pressed"] for f in value["fingers"]))
        value = self.frame(hands=(observed("left", wrist_x=.65), observed("right", wrist_x=.26)))
        self.assertFalse(any(f["valid"] for f in value["fingers"]))

    def test_stale_gap_disarms_finger_capture_and_requires_neutral(self):
        self.arm()
        for _ in range(4): self.frame(left=("index",))
        value = self.frame(left=("index",), advance=900)
        self.assertFalse(self.finger(value, "left", "index")["pressed"])
        for _ in range(4): value = self.frame(left=("index",))
        self.assertFalse(self.finger(value, "left", "index")["pressed"])

    def test_disarm_reset_and_recalibration_release_and_change_epoch(self):
        value = self.arm()
        epoch = value["epoch"]
        self.engine.command("disarm", self.now)
        self.assertFalse(self.frame()["armed"])
        self.engine.command("reset", self.now)
        value = self.frame()
        self.assertNotEqual(epoch, value["epoch"])
        self.assertEqual(value["stage"], "NEUTRAL")

    def test_invalid_landmarks_and_ambiguous_score_cannot_press(self):
        self.arm()
        bad = CameraHand("left", ((float("nan"), 0., 0.),)*21, .99)
        value = self.frame(hands=(bad, observed("right")))
        self.assertFalse(any(f["hand"] == "left" and f["valid"] for f in value["fingers"]))
        weak = observed("left")
        value = self.frame(hands=(CameraHand("left", weak.landmarks, .4),))
        self.assertFalse(any(f["valid"] for f in value["fingers"]))

    def test_old_frame_never_creates_press(self):
        self.arm()
        stale = CameraSample(self.now-1, None, hands=(observed("left", ("index",)), observed("right")))
        self.assertFalse(any(f["pressed"] for f in self.engine.process(stale)["fingers"]))

    def test_mouse_zone_requires_neutral_dwell_and_excludes_right_typing(self):
        self.arm()
        value = self.frame(hands=(observed("left"), observed("right", wrist_x=.91, x_scale=.4)))
        self.assertFalse(value["mouse"]["active"])
        for _ in range(9): value = self.frame(hands=(observed("left"), observed("right", wrist_x=.91, x_scale=.4)))
        self.assertTrue(value["mouse"]["active"])
        self.assertFalse(any(f["hand"] == "right" and f["pressed"] for f in value["fingers"]))
        value = self.frame()
        self.assertFalse(value["mouse"]["active"])
        self.assertFalse(value["mouse"]["pressed"])

    def test_mouse_open_entry_pinch_release_and_outside_pad_rearm(self):
        self.arm()
        mouse = observed("right", wrist_x=.91, x_scale=.4)
        def mouse_frame(pinched=False, outside=False):
            points = list(mouse.landmarks)
            if outside:
                points[8] = (.84, .3, 0.)
            if pinched:
                points[4] = (points[8][0]+.015, points[8][1], points[8][2])
            return self.frame(hands=(observed("left"), CameraHand("right", tuple(points), .99)))
        for _ in range(12): value = mouse_frame()
        self.assertTrue(value["mouse"]["valid"])
        self.assertFalse(value["mouse"]["pressed"])
        for _ in range(4): value = mouse_frame(pinched=True)
        self.assertTrue(value["mouse"]["pressed"])
        value = mouse_frame(pinched=True, outside=True)
        self.assertFalse(value["mouse"]["valid"])
        self.assertFalse(value["mouse"]["pressed"])
        for _ in range(5): value = mouse_frame(pinched=True)
        self.assertFalse(value["mouse"]["pressed"])
        mouse_frame()
        for _ in range(4): value = mouse_frame(pinched=True)
        self.assertTrue(value["mouse"]["pressed"])
        value = mouse_frame()
        self.assertFalse(value["mouse"]["pressed"])

    def test_unknown_commands_and_invalid_times_are_explicit_errors(self):
        for action, time in (("send_input", self.now), ("arm", -1), ("reset", True)):
            with self.assertRaises(ValueError):
                self.engine.command(action, time)
