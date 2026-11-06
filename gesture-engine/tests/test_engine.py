import unittest
from airdesk_gesture.engine import EngineConfig, GestureEngine
from airdesk_gesture.synthetic import hand, sample_frames

class GestureTests(unittest.TestCase):
    def test_move(self):
        e = GestureEngine()
        out = e.process(hand(0.3,0.4),100)
        self.assertEqual(out[0]["type"], "gesture.move")
        self.assertEqual(e.state,"TRACKING")

    def test_pinch_and_release(self):
        e = GestureEngine(EngineConfig(pinch_debounce_ms=0))
        a = e.process(hand(0.3,0.4,True),100)
        b = e.process(hand(0.3,0.4,False),134)
        self.assertIn("gesture.pinch_start",[v["type"] for v in a])
        self.assertIn("gesture.pinch_end",[v["type"] for v in b])

    def test_drag_and_lost_releases(self):
        e = GestureEngine(EngineConfig(smoothing_alpha=1.0, pinch_debounce_ms=0))
        e.process(hand(0.3,0.4,True),100)
        b = e.process(hand(0.5,0.4,True),134)
        c = e.process(None,168)
        self.assertIn("gesture.drag_start",[v["type"] for v in b])
        self.assertEqual([v["type"] for v in c],["gesture.pinch_end","tracking.lost"])
        self.assertEqual(e.process(None,202),[])
        self.assertEqual(e.state,"LOST")

    def test_monotonic(self):
        e = GestureEngine()
        e.process(hand(0.3,0.4),100)
        self.assertEqual(e.process(hand(0.3,0.4),100),[])
        self.assertEqual(e.process(hand(0.3,0.4),99),[])

    def test_invalid_landmarks(self):
        e = GestureEngine()
        with self.assertRaises(ValueError):
            e.process([(0.3,0.4)],100)
        with self.assertRaises(ValueError):
            e.process([(float('nan'), 0.4)]*21,101)

    def test_sequence_unique(self):
        e = GestureEngine()
        events = []
        for ts, pts in sample_frames(10):
            events.extend(e.process(pts,ts))
        seq = [x["sequence"] for x in events]
        self.assertEqual(seq, list(range(len(seq))))

    def test_initial_sequence_continues_control_envelope(self):
        e = GestureEngine(initial_sequence=1)
        events = e.process(hand(0.3, 0.4), 100)
        self.assertEqual(events[0]["sequence"], 1)

    def test_terminal_camera_failure_is_announced_while_idle(self):
        e = GestureEngine()
        events = e.end_tracking(100, "camera_disconnected")
        self.assertEqual([v["type"] for v in events], ["tracking.lost"])
        self.assertEqual(events[0]["payload"]["reason"], "camera_disconnected")
        self.assertEqual(e.end_tracking(101, "camera_disconnected"), [])

    def test_capture_failure_releases_drag(self):
        e = GestureEngine(EngineConfig(smoothing_alpha=1.0, pinch_debounce_ms=0))
        e.process(hand(0.3, 0.4, True), 100)
        e.process(hand(0.5, 0.4, True), 134)
        events = e.end_tracking(168, "capture_failed")
        self.assertEqual([v["type"] for v in events], ["gesture.pinch_end", "tracking.lost"])
        self.assertEqual(events[-1]["payload"]["reason"], "capture_failed")

    def test_config_hysteresis(self):
        with self.assertRaises(ValueError):
            EngineConfig(press_ratio=.5,release_ratio=.2)

    def test_debounce_rejects_short_pinch_and_release_is_immediate(self):
        e = GestureEngine()
        self.assertNotIn("gesture.pinch_start", [v["type"] for v in e.process(hand(.3,.4,True),100)])
        e.process(hand(.3,.4),134)
        e.process(hand(.3,.4,True),168)
        pressed = e.process(hand(.3,.4,True),234)
        self.assertIn("gesture.pinch_start", [v["type"] for v in pressed])
        released = e.process(hand(.3,.4),235)
        self.assertIn("gesture.pinch_end", [v["type"] for v in released])

    def test_zero_palm_never_pinches(self):
        e = GestureEngine()
        points = [(0.4, 0.4)] * 21
        self.assertEqual(e.process(points,100)[-1]["type"], "tracking.lost")
        self.assertEqual(e.state, "LOST")

if __name__ == "__main__":
    unittest.main()

