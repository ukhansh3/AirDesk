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
        e = GestureEngine()
        a = e.process(hand(0.3,0.4,True),100)
        b = e.process(hand(0.3,0.4,False),134)
        self.assertIn("gesture.pinch_start",[v["type"] for v in a])
        self.assertIn("gesture.pinch_end",[v["type"] for v in b])

    def test_drag_and_lost_releases(self):
        e = GestureEngine(EngineConfig(smoothing_alpha=1.0))
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

    def test_config_hysteresis(self):
        with self.assertRaises(ValueError):
            EngineConfig(press_ratio=.5,release_ratio=.2)

if __name__ == "__main__":
    unittest.main()
