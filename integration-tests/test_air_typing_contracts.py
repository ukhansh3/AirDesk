"""Synthetic AIR v0.2 shape conformance; not camera accuracy or input authorization."""
from __future__ import annotations

import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator
from test_phase2_contract_harness import strict_json


ROOT = Path(__file__).resolve().parents[1]
FIXTURES = Path(__file__).parent / "fixtures" / "air-typing-wire.json"
SCHEMA = ROOT / "contracts" / "air" / "air-messages.schema.json"


class AirTypingContractHarness(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog = json.loads(FIXTURES.read_text(encoding="utf-8"))
        schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        cls.validator = Draft202012Validator(schema)
        cls.cases = {case["id"]: case for case in cls.catalog["cases"]}

    def message(self, name):
        return strict_json(self.cases[name]["wire"])

    def test_synthetic_catalog_conforms_to_separate_air_schema(self):
        self.assertEqual(self.catalog["scope"], "synthetic-air-wire-conformance-only")
        self.assertEqual(len(self.cases), len(self.catalog["cases"]))
        self.assertGreater(len(self.cases), 40)
        for case in self.catalog["cases"]:
            with self.subTest(case=case["id"]):
                try:
                    message = strict_json(case["wire"])
                except ValueError:
                    accepted = False
                else:
                    accepted = self.validator.is_valid(message)
                self.assertEqual(case["schemaValid"], accepted)

    def test_all_calibration_stages_and_statuses_have_positive_shapes(self):
        frames = [strict_json(case["wire"])["payload"] for case in self.catalog["cases"]
                  if case["schemaValid"] and strict_json(case["wire"])["type"] == "air.frame"]
        stages = {"NEUTRAL", "READY"} | {
            f"{hand}_{finger}" for hand in ("LEFT", "RIGHT")
            for finger in ("THUMB", "INDEX", "MIDDLE", "RING", "LITTLE")}
        self.assertEqual(stages, {frame["stage"] for frame in frames})
        self.assertEqual({"WAITING", "COUNTDOWN", "CAPTURING", "FAILED", "READY", "ARMED"},
                         {frame["status"] for frame in frames})

    def test_complete_frame_can_name_every_finger_and_loss_without_text(self):
        frame = self.message("air-ten-fingers-armed")["payload"]
        identities = {(finger["hand"], finger["finger"]) for finger in frame["fingers"]}
        expected = {(hand, finger) for hand in ("left", "right")
                    for finger in ("thumb", "index", "middle", "ring", "little")}
        self.assertEqual(expected, identities)
        self.assertEqual(len(frame["fingers"]), 10)
        self.assertEqual(set(frame), {"epoch", "stage", "status", "armed", "fingers", "mouse"})
        self.assertFalse(any(finger["pressed"] for finger in frame["fingers"]))
        self.assertEqual([], self.message("air-complete-loss-frame")["payload"]["fingers"])
        mouse_frame = self.message("air-mouse-drag-frame")["payload"]
        self.assertTrue(mouse_frame["mouse"]["dragging"])
        self.assertTrue(all(finger["hand"] == "left" for finger in mouse_frame["fingers"]))

    def test_commands_do_not_encode_text_or_native_input(self):
        for action in ("start_calibration", "capture", "arm", "disarm", "reset"):
            with self.subTest(action=action):
                message = self.message("air-command-" + action)
                self.assertEqual(message["payload"], {"action": action})
                self.assertTrue(self.validator.is_valid(message))
        accepted = self.message("air-auth-accepted")
        self.assertEqual(accepted["payload"], {"mode": "air_typing_demo"})

    def test_version_isolation_preserves_old_phase1_contracts(self):
        legacy = []
        for path in ("control/messages.schema.json", "gesture/gesture-event.schema.json"):
            schema = json.loads((ROOT / "contracts" / path).read_text(encoding="utf-8"))
            legacy.append(Draft202012Validator(schema))
        for name in ("air-auth-hello", "air-auth-accepted", "air-ten-fingers-armed"):
            message = self.message(name)
            with self.subTest(case=name):
                self.assertTrue(self.validator.is_valid(message))
                self.assertTrue(all(not validator.is_valid(message) for validator in legacy))
        original = json.loads((Path(__file__).parent / "fixtures" / "valid_gesture_move.json").read_text(encoding="utf-8"))
        self.assertFalse(self.validator.is_valid(original))
        self.assertTrue(legacy[1].is_valid(original))

    def test_schema_validity_is_not_semantic_or_local_demo_permission(self):
        required = {"air-duplicate-identity-is-semantic", "air-invalid-finger-pressed-is-semantic",
                    "air-unarmed-press-is-semantic", "air-armed-calibration-stage-is-semantic",
                    "air-mouse-drag-without-press-is-semantic", "air-replay-shaped-frame",
                    "air-old-session-shaped-frame", "air-old-epoch-shaped-frame", "air-stale-shaped-frame"}
        guarded = {case["id"] for case in self.catalog["cases"] if case.get("runtimeMustRejectUnless")}
        self.assertTrue(required <= guarded)
        for name in guarded:
            with self.subTest(case=name):
                self.assertTrue(self.cases[name]["schemaValid"])
                self.assertTrue(self.validator.is_valid(self.message(name)))
                self.assertTrue(self.cases[name]["runtimeMustRejectUnless"].strip())

    def test_duplicate_json_and_nonfinite_values_fail_before_schema(self):
        for name in ("air-duplicate-json-property", "air-nonfinite-coordinate"):
            with self.subTest(case=name), self.assertRaises(ValueError):
                strict_json(self.cases[name]["wire"])


if __name__ == "__main__":
    unittest.main()
