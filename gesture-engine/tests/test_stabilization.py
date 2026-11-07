"""Deterministic XY noise/latency checks, not webcam FPS/accuracy evidence."""
from math import dist, sin
import unittest

from airdesk_gesture.stabilization import StablePointFilter, PointStabilizationConfig


class StablePointFilterTests(unittest.TestCase):
    def test_stationary_jitter_is_smaller_than_raw_and_has_no_amplification(self):
        f = StablePointFilter()
        f.update((.5, .5), 0)
        raw, outputs = [], []
        for frame in range(1, 121):
            point = (.5+.012*sin(frame*1.7), .5+.01*sin(frame*2.1))
            raw.append(dist(point, (.5, .5)))
            outputs.append(dist(f.update(point, frame*33), (.5, .5)))
        self.assertLess(max(outputs), max(raw)*.4)

    def test_isolated_spike_including_second_frame_is_rejected(self):
        f = StablePointFilter()
        self.assertEqual(f.update((.4, .4), 0), (.4, .4))
        self.assertEqual(f.update((.9, .1), 33), (.4, .4))
        self.assertEqual(f.update((.4, .4), 66), (.4, .4))
        for now in (99, 132, 165):
            self.assertEqual(f.update((.4, .4), now), (.4, .4))

    def test_intentional_motion_reaches_target_neighborhood_within_250ms(self):
        f = StablePointFilter()
        f.update((.4, .4), 0)
        for now in range(33, 265, 33): value = f.update((.5, .4), now)
        self.assertLess(dist(value, (.5, .4)), .01)
        self.assertGreater(value[0], .49)

    def test_slow_drift_accumulates_instead_of_being_permanently_frozen(self):
        f = StablePointFilter()
        f.update((.4, .4), 0)
        for frame in range(1, 101): value = f.update((.4+frame*.0003, .4), frame*20)
        self.assertGreater(value[0], .42)
        self.assertLess(value[0], .43)

    def test_time_aware_response_is_close_at_30_and_60fps(self):
        def trajectory(period):
            f = StablePointFilter()
            f.update((.4, .4), 0)
            for now in range(period, 1001, period): value = f.update((.4+.12*now/1000, .4), now)
            return value
        self.assertLess(dist(trajectory(33), trajectory(16)), .005)

    def test_invalid_and_nonincreasing_values_clear_history_immediately(self):
        for point, now in (((float('nan'), .4), 33), ((1.1, .4), 33), ((True, .4), 33), ((.4,), 33), ((.4, .4), 0), ((.4, .4), True)):
            f = StablePointFilter()
            f.update((.2, .2), 0)
            self.assertIsNone(f.update(point, now))
            self.assertEqual(f.update((.8, .8), 100), (.8, .8))

    def test_reset_gap_and_large_sustained_jump_do_not_blend_old_identity(self):
        f = StablePointFilter()
        f.update((.2, .2), 0)
        f.reset()
        self.assertEqual(f.update((.8, .8), 10), (.8, .8))
        self.assertEqual(f.update((.3, .3), 1000), (.3, .3))
        f.update((.9, .9), 1033)  # one sample remains a rejected spike
        self.assertEqual(f.update((.9, .9), 1066), (.9, .9))

    def test_invalid_configuration_is_rejected(self):
        for options in ({'median_window': 2}, {'tau_ms': 0}, {'deadzone': -.1}, {'maximum_gap_ms': True}, {'jump_distance': .001}):
            with self.assertRaises(ValueError):
                PointStabilizationConfig(**options)
