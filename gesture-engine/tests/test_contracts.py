import json
import sys
import unittest
from pathlib import Path
from airdesk_gesture.engine import GestureEngine
from airdesk_gesture.synthetic import sample_frames

ROOT = Path(__file__).resolve().parents[2]
class ContractTests(unittest.TestCase):
    def test_synthetic_events_schema(self):
        try:
            from jsonschema import Draft202012Validator
        except ImportError:
            self.skipTest("install gesture-engine[dev] for jsonschema")
        schema = json.loads((ROOT / "contracts/gesture/gesture-event.schema.json").read_text(encoding="utf-8"))
        validator = Draft202012Validator(schema)
        engine = GestureEngine()
        events = [e for timestamp,points in sample_frames(20) for e in engine.process(points,timestamp)]
        self.assertTrue(events)
        for event in events:
            self.assertEqual(list(validator.iter_errors(event)), [])

    def test_out_of_bounds_rejected(self):
        try:
            from jsonschema import Draft202012Validator
        except ImportError:
            self.skipTest("install gesture-engine[dev] for jsonschema")
        schema = json.loads((ROOT / "contracts/gesture/gesture-event.schema.json").read_text(encoding="utf-8"))
        bad = {
            "protocolVersion":"0.1","type":"gesture.move","sessionId":"demo",
            "sequence":1,"timestampMs":4,"payload":{"x":2.0,"y":0.4,"confidence":0.9}
        }
        self.assertTrue(list(Draft202012Validator(schema).iter_errors(bad)))

if __name__ == "__main__":
    unittest.main()
