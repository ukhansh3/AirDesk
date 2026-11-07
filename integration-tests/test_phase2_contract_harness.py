"""Shared wire conformance fixtures, not proof of pairing/session authorization."""
from __future__ import annotations

import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = Path(__file__).parent / "fixtures" / "phase2-wire-cases.json"


def strict_json(raw: str):
    def object_pairs(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate JSON property")
            result[key] = value
        return result
    def invalid_constant(_):
        raise ValueError("Non-finite JSON constant")
    return json.loads(raw, object_pairs_hook=object_pairs, parse_constant=invalid_constant)


class Phase2ContractHarness(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog = json.loads(FIXTURES.read_text(encoding="utf-8"))
        cls.validators = {}
        for path in (ROOT / "contracts").rglob("*.schema.json"):
            schema = json.loads(path.read_text(encoding="utf-8"))
            Draft202012Validator.check_schema(schema)
            cls.validators[path.relative_to(ROOT / "contracts").as_posix()] = Draft202012Validator(schema)

    def test_wire_fixture_catalog(self):
        self.assertEqual(self.catalog["scope"], "synthetic-wire-conformance-only")
        cases = self.catalog["cases"]
        self.assertEqual(len({case["id"] for case in cases}), len(cases))
        for case in cases:
            with self.subTest(case=case["id"]):
                validator = self.validators[case["schema"]]
                try:
                    message = strict_json(case["wire"])
                except ValueError:
                    accepted = False
                else:
                    accepted = validator.is_valid(message)
                self.assertEqual(accepted, case["schemaValid"])

    def test_valid_wire_does_not_imply_runtime_authorization(self):
        cases = [case for case in self.catalog["cases"] if "runtimeMustRejectUnless" in case]
        self.assertTrue(cases)
        for case in cases:
            with self.subTest(case=case["id"]):
                self.assertTrue(case["schemaValid"])
                self.assertTrue(self.validators[case["schema"]].is_valid(strict_json(case["wire"])))
                self.assertTrue(case["runtimeMustRejectUnless"])

    def test_duplicate_nested_properties_rejected_before_schema(self):
        with self.assertRaises(ValueError):
            strict_json('{"payload":{"reason":"TIMEOUT","reason":"USER_REQUEST"}}')

    def test_integral_numeric_spellings_remain_schema_compatible(self):
        cases = {case["id"]: case for case in self.catalog["cases"]}
        for name in ("integer-decimal-spelling", "integer-exponent-spelling"):
            with self.subTest(case=name):
                case = cases[name]
                parsed = strict_json(case["wire"])
                self.assertEqual(parsed["sequence"], 1)
                self.assertEqual(parsed["timestampMs"], 100)
                self.assertTrue(self.validators[case["schema"]].is_valid(parsed))

    def test_nonfinite_constants_rejected_before_schema(self):
        for value in ("NaN", "Infinity", "-Infinity"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                strict_json('{"x":' + value + '}')


if __name__ == "__main__":
    unittest.main()
