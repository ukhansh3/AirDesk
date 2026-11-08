"""Synthetic AIR typing specification. No camera, physical contact or accuracy proof.

The model must calibrate all ten fingers, reject inseparable poses, keep each
finger's Down until release without repeat, and require neutral after loss.
Only the side pad may create mouse state; typing and mouse ownership are disjoint.
"""
import unittest
from math import cos, sin, dist

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


def mcp_tap(side, finger, angle=.65):
    """Rigidly depress an otherwise straight finger at its MCP; palm stays fixed."""
    hand = observed(side)
    points = list(hand.landmarks)
    base = {"thumb": 2, "index": 5, "middle": 9, "ring": 13, "little": 17}[finger]
    stop = 5 if finger == "thumb" else base+4
    origin = points[base]
    for index in range(base+1, stop):
        x, y, z = (points[index][i]-origin[i] for i in range(3))
        points[index] = (origin[0]+x, origin[1]+y*cos(angle)-z*sin(angle),
                         origin[2]+y*sin(angle)+z*cos(angle))
    return CameraHand(side, tuple(points), .99)


def rigid_move(hand, angle=.4):
    """Move the whole hand, preserving all joint angles and palm-relative shape."""
    origin = hand.landmarks[0]
    points = []
    for point in hand.landmarks:
        x, y, z = (point[i]-origin[i] for i in range(3))
        points.append((origin[0]+.01+x, origin[1]-.01+y*cos(angle)-z*sin(angle),
                       origin[2]+y*sin(angle)+z*cos(angle)))
    return CameraHand(hand.handedness, tuple(points), hand.score)


class AirTypingTests(unittest.TestCase):
    def setUp(self):
        self.engine = AirTypingEngine()
        self.now = 1000

    def frame(self, left=(), right=(), hands=None, advance=40):
        self.now += advance
        sample = CameraSample(self.now, None, hands=tuple(hands) if hands is not None else
                              (observed("left", left), observed("right", right)))
        return self.engine.process(sample)

    def capture(self, left=(), right=(), count=14, hands=None):
        self.engine.command("capture", self.now)
        self.frame(left, right, hands=hands, advance=1500)
        for _ in range(count):
            value = self.frame(left, right, hands=hands)
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

    def test_held_little_finger_after_arm_does_not_block_neutral_index_rearm(self):
        self.calibrate()
        self.engine.command("arm", self.now)
        for _ in range(8): self.frame(left=("little",))
        for _ in range(4): value = self.frame(left=("little", "index"))
        self.assertTrue(self.finger(value, "left", "index")["pressed"])
        self.assertFalse(self.finger(value, "left", "little")["pressed"])

    def test_straight_finger_mcp_downstroke_calibrates_and_presses(self):
        # This geometric movement leaves the old MCP-to-tip chain-shortening
        # ratio zero. It is still an intentional typing depression at the MCP.
        straight = observed("left").landmarks
        tapped = mcp_tap("left", "index").landmarks
        for points in (straight, tapped):
            self.assertAlmostEqual(dist(points[5], points[8]), sum(dist(points[i], points[i+1]) for i in (5, 6, 7)))
        self.capture()
        for side in ("left", "right"):
            for name in FINGERS:
                poses = tuple(observed(h, ("thumb",)) if h == side and name == "thumb" else
                              mcp_tap(h, name) if h == side else observed(h) for h in ("left", "right"))
                value = self.capture(hands=poses)
        self.assertEqual(value["stage"], "READY")
        self.engine.command("arm", self.now)
        for _ in range(8): self.frame()
        for _ in range(4): value = self.frame(hands=(mcp_tap("left", "index"), observed("right")))
        self.assertTrue(self.finger(value, "left", "index")["pressed"])
        for _ in range(4): value = self.frame()
        self.assertFalse(self.finger(value, "left", "index")["pressed"])

    def test_whole_hand_rigid_movement_is_not_a_typing_press(self):
        self.arm()
        hands = tuple(rigid_move(observed(side)) for side in ("left", "right"))
        for _ in range(10): value = self.frame(hands=hands)
        self.assertFalse(any(f["pressed"] for f in value["fingers"]))

    def test_automatic_guided_calibration_needs_one_start_then_all_ten_valid_poses(self):
        self.engine.command("start_calibration", self.now)
        self.assertEqual(self.frame()["status"], "COUNTDOWN")
        for _ in range(20): self.frame(advance=250)
        for _ in range(16): value = self.frame()
        self.assertEqual(value["stage"], "LEFT_THUMB")
        for side in ("left", "right"):
            for name in FINGERS:
                for _ in range(20): self.frame()
                for _ in range(16): value = self.frame(left=(name,) if side == "left" else (), right=(name,) if side == "right" else ())
        self.assertEqual(value["stage"], "READY")
        self.assertEqual(value["status"], "READY")
        self.assertFalse(value["armed"])
        self.engine.command("arm", self.now)
        for _ in range(8): self.frame()
        for _ in range(4): value = self.frame(left=("index",))
        self.assertTrue(self.finger(value, "left", "index")["pressed"])

    def test_auto_missing_coupled_and_bouncing_poses_do_not_advance_and_can_retry(self):
        self.engine.command("start_calibration", self.now)
        for _ in range(24): value = self.frame(hands=(), advance=250)
        self.assertEqual(value["stage"], "NEUTRAL")
        for _ in range(16): value = self.frame()
        self.assertEqual(value["stage"], "LEFT_THUMB")
        for _ in range(20): self.frame()
        for _ in range(20): value = self.frame(left=("thumb", "index"))
        self.assertEqual(value["stage"], "LEFT_THUMB")
        for _ in range(20): self.frame()
        for i in range(30): value = self.frame(left=("thumb",) if i % 2 else ())
        self.assertEqual(value["stage"], "LEFT_THUMB")
        for _ in range(20): self.frame()
        for _ in range(16): value = self.frame(left=("thumb",))
        self.assertEqual(value["stage"], "LEFT_INDEX")

    def test_disarm_cancels_automatic_flow_and_manual_capture_still_works(self):
        self.engine.command("start_calibration", self.now)
        self.engine.command("disarm", self.now)
        for _ in range(30): value = self.frame(advance=250)
        self.assertEqual(value["stage"], "NEUTRAL")
        self.assertEqual(self.capture()["stage"], "LEFT_THUMB")

    def test_auto_guidance_accepts_mcp_strokes_and_learns_either_signed_direction(self):
        self.engine.command("start_calibration", self.now)
        for _ in range(20): self.frame(advance=250)
        for _ in range(16): self.frame()
        for side in ("left", "right"):
            for name in FINGERS:
                for _ in range(20): self.frame()
                poses = tuple(observed(h, ("thumb",)) if h == side and name == "thumb" else
                              mcp_tap(h, name, angle=-.65 if h == "right" else .65) if h == side else observed(h)
                              for h in ("left", "right"))
                for _ in range(16): value = self.frame(hands=poses)
        self.assertEqual(value["stage"], "READY")
        self.engine.command("arm", self.now)
        for _ in range(8): self.frame()
        for _ in range(4): value = self.frame(hands=(observed("left"), mcp_tap("right", "index", angle=-.65)))
        self.assertTrue(self.finger(value, "right", "index")["pressed"])

    def test_auto_idle_and_unstable_neutral_do_not_capture_a_blind_timer(self):
        self.engine.command("start_calibration", self.now)
        for _ in range(20): self.frame(advance=250)
        for i in range(40): value = self.frame(left=("index",) if i % 2 else ())
        self.assertEqual(value["stage"], "NEUTRAL")
        for _ in range(16): value = self.frame()
        self.assertEqual(value["stage"], "LEFT_THUMB")
        for _ in range(20): self.frame()
        for _ in range(65): value = self.frame(advance=250)
        self.assertEqual(value["stage"], "LEFT_THUMB")
        self.assertEqual(value["status"], "WAITING")
        self.assertFalse(value["armed"])

    def test_in_plane_finger_splay_does_not_count_as_mcp_downstroke(self):
        base = observed("left")
        spread = list(base.landmarks)
        anchor = spread[5]
        for i in (6, 7, 8):
            x, y, z = (spread[i][axis]-anchor[axis] for axis in range(3))
            spread[i] = (anchor[0]+x*cos(.3)-y*sin(.3), anchor[1]+x*sin(.3)+y*cos(.3), anchor[2]+z)
        before = self.engine.features(base)["index"]
        after = self.engine.features(CameraHand("left", tuple(spread), .99))["index"]
        self.assertAlmostEqual(dist(before, after), 0., places=10)

    def test_mcp_palm_frame_feature_survives_rigid_rotation_translation_and_scale(self):
        for side in ("left", "right"):
            hand = mcp_tap(side, "index", angle=.65 if side == "left" else -.65)
            moved = rigid_move(hand)
            wrist = moved.landmarks[0]
            scaled = CameraHand(side, tuple(tuple(wrist[i]+.7*(p[i]-wrist[i]) for i in range(3))
                                            for p in moved.landmarks), .99)
            original = self.engine.features(hand)
            transformed = self.engine.features(scaled)
            for name in FINGERS:
                self.assertAlmostEqual(dist(original[name], transformed[name]), 0., places=10)

    def test_auto_hand_loss_during_capture_requires_neutral_before_retry(self):
        self.engine.command("start_calibration", self.now)
        for _ in range(20): self.frame(advance=250)
        for _ in range(16): self.frame()
        for _ in range(20): self.frame()
        for _ in range(8): self.frame(left=("thumb",))
        self.frame(hands=(observed("right"),))
        for _ in range(20): value = self.frame(left=("thumb",))
        self.assertEqual(value["stage"], "LEFT_THUMB")
        for _ in range(20): self.frame()
        for _ in range(16): value = self.frame(left=("thumb",))
        self.assertEqual(value["stage"], "LEFT_INDEX")

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
        self.assertGreater(dist(thumb["thumb"], base["thumb"]), .08)
        self.assertEqual(thumb["index"], base["index"])
        self.assertGreater(dist(index["index"], base["index"]), .08)
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

    def test_visual_smoothing_cannot_turn_raw_unstable_shape_into_valid_calibration(self):
        self.engine.command("start_calibration", self.now)
        for _ in range(20): self.frame(advance=250)
        for frame in range(60):
            left = mcp_tap("left", "index", angle=.45) if frame % 2 else observed("left")
            value = self.frame(hands=(left, observed("right")))
        # XY is stabilized for drawing/hover, while the large RAW palm-relative
        # shape oscillation still prevents a stable neutral capture.
        self.assertEqual(value["stage"], "NEUTRAL")
        self.assertFalse(value["armed"])

    def test_source_pause_keeps_two_of_eleven_and_resumes_next_valid_pose(self):
        self.engine.command("start_calibration", self.now)
        for _ in range(20): self.frame(advance=250)
        for _ in range(16): value = self.frame()
        self.assertEqual(value["stage"], "LEFT_THUMB")  # second of eleven poses
        epoch = value["epoch"]
        neutral = self.engine._neutral.copy()
        paused = self.engine.pause_source(self.now)
        self.assertEqual(paused["stage"], "LEFT_THUMB")
        self.assertEqual(paused["epoch"], epoch)
        self.assertEqual(paused["fingers"], [])
        self.assertFalse(paused["armed"])
        self.assertEqual(self.engine._neutral, neutral)
        # A held pose cannot finish after a pause: fresh neutral must rearm it.
        for _ in range(20): value = self.frame(left=("thumb",))
        self.assertEqual(value["stage"], "LEFT_THUMB")
        for _ in range(20): self.frame()
        for _ in range(16): value = self.frame(left=("thumb",))
        self.assertEqual(value["stage"], "LEFT_INDEX")  # third pose, not NEUTRAL
        saved_thresholds = self.engine._thresholds.copy()
        self.engine.pause_source(self.now)
        self.assertEqual(self.engine._thresholds, saved_thresholds)
        for _ in range(20): self.frame()
        for _ in range(16): value = self.frame(left=("index",))
        self.assertEqual(value["stage"], "LEFT_MIDDLE")
        self.assertEqual(value["epoch"], epoch)

    def test_source_pause_releases_armed_state_and_never_automatically_rearms(self):
        self.arm()
        for _ in range(4): self.frame(left=("index",))
        paused = self.engine.pause_source(self.now)
        self.assertEqual(paused["stage"], "READY")
        self.assertEqual(paused["status"], "READY")
        self.assertEqual(paused["fingers"], [])
        self.assertFalse(paused["armed"])
        self.assertFalse(paused["mouse"]["pressed"])
        for _ in range(12): value = self.frame(left=("index",))
        self.assertFalse(value["armed"])
        for _ in range(8): self.frame()
        self.engine.command("arm", self.now)
        for _ in range(8): self.frame()
        for _ in range(4): value = self.frame(left=("index",))
        self.assertTrue(self.finger(value, "left", "index")["pressed"])

    def test_source_pause_discards_partial_manual_capture_and_xy_history(self):
        self.capture()
        self.engine.command("capture", self.now)
        self.frame(left=("thumb",), advance=1500)
        for _ in range(6): self.frame(left=("thumb",))
        paused = self.engine.pause_source(self.now)
        self.assertEqual(paused["stage"], "LEFT_THUMB")
        for _ in range(16): value = self.frame(left=("thumb",))
        self.assertEqual(value["stage"], "LEFT_THUMB")
        self.assertEqual(value["status"], "WAITING")
        self.assertEqual(self.capture(left=("thumb",))["stage"], "LEFT_INDEX")
        self.engine.pause_source(self.now)
        moved = observed("left", wrist_x=.36)
        value = self.frame(hands=(moved, observed("right")))
        self.assertEqual(self.finger(value, "left", "index")["x"], moved.landmarks[8][0])

    def test_source_pause_clamps_nonnegative_backward_clock_without_cached_held_output(self):
        self.arm()
        for _ in range(4): self.frame(left=("index",))
        for clock in (self.now-100, self.now, self.now):
            paused = self.engine.pause_source(clock)
            self.assertEqual(paused["fingers"], [])
            self.assertFalse(paused["armed"])
            self.assertFalse(paused["mouse"]["pressed"])
        for clock in (-1, True, 1.0, None):
            with self.assertRaises(ValueError):
                self.engine.pause_source(clock)
