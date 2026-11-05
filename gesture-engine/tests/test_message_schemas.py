"""Contract-first tests for every AirDesk event family, including disabled features.

Tests schema shapes only. They do NOT claim pairing, Windows input or streaming works.
"""
import json
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2] / "contracts"
SESSION = "test-session"
GRANT = "example-grant-id-01"


def message(kind, payload):
    return dict(
        protocolVersion="0.1",
        type=kind,
        sessionId=SESSION,
        sequence=1,
        timestampMs=123456,
        payload=payload,
    )


EVENTS = {
    "gesture/gesture-event.schema.json": {
        "gesture.move": {"x": 0.2, "y": 0.5, "confidence": 0.7},
        "gesture.pinch_start": {"x": 0.2, "y": 0.5, "confidence": 0.7},
        "gesture.pinch_end": {"x": 0.2, "y": 0.5, "confidence": 0.0},
        "gesture.drag_start": {"x": 0.2, "y": 0.5, "confidence": 0.7},
        "gesture.drag_move": {"x": 0.2, "y": 0.5, "confidence": 0.7},
        "tracking.lost": {"reason": "hand_not_detected"},
    },
    "control/messages.schema.json": {
        "auth.hello": {"token": "TEST_ONLY_TOKEN_FOR_SCHEMA_VALIDATION_000"},
        "auth.accepted": {"mode": "diagnostic_only"},
        "auth.rejected": {"errorCode": "UNAUTHENTICATED"},
        "heartbeat": {"lastReceivedSequence": 0},
        "session.locked": {"reason": "UNSAFE_VEHICLE"},
        "session.closed": {"reason": "USER_REQUEST"},
    },
    "pairing/pairing-messages.schema.json": {
        "pairing.begin": {"deviceLabel": "Test Android Emulator"},
        "pairing.offer": {"pairingId": "pairing-1", "expiresAtMs": 123000, "code": "12345678"},
        "pairing.claim": {"pairingId": "pairing-1", "code": "12345678", "deviceLabel": "Test Client"},
        "pairing.approval": {"pairingId": "pairing-1", "side": "HOST", "approved": True},
        "pairing.completed": {"pairingId": "pairing-1", "connectionId": "connection-1"},
        "pairing.revoked": {"pairingId": "pairing-1", "reason": "EXPIRED"},
    },
    "session/session-messages.schema.json": {
        "session.request": {"connectionId": "connection-1", "capabilities": ["VIEW_VIDEO"]},
        "session.approve": {"requestId": "request-1", "approved": True, "expiresAtMs": 123456},
        "session.granted": {"grantId": GRANT, "capabilities": ["POINTER"], "expiresAtMs": 123456},
        "session.heartbeat": {"ackSequence": 0},
        "session.revoke": {"reason": "USER_REQUEST"},
        "session.ended": {"reason": "SAFETY_LOCK"},
    },
    "input/input-event.schema.json": {
        "pointer.move": {"grantId": GRANT, "displayId": "primary", "x": 0.3, "y": 0.5},
        "pointer.down": {"grantId": GRANT, "button": "left"},
        "pointer.up": {"grantId": GRANT, "button": "left"},
        "pointer.scroll": {"grantId": GRANT, "deltaX": 0, "deltaY": -2, "unit": "line"},
        "keyboard.down": {"grantId": GRANT, "code": "KeyA", "modifiers": ["Shift"]},
        "keyboard.up": {"grantId": GRANT, "code": "KeyA"},
        "keyboard.text": {"grantId": GRANT, "text": "Hi"},
        "input.release_all": {"reason": "SAFETY_LOCK"},
    },
    "errors/error.schema.json": {
        "protocol.error": {"code": "UNSAFE_VEHICLE", "message": "Vehicle safety unknown", "retryable": False,
                           "correlationId": "example-correlation-1"},
    },
}


def load_validator(path):
    schema = json.loads((ROOT / path).read_text(encoding="utf8"))
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema)


class MessageSchemaTests(unittest.TestCase):
    def test_all_reserved_messages_have_valid_examples(self):
        for name, events in EVENTS.items():
            validator = load_validator(name)
            for kind, payload in events.items():
                with self.subTest(schema=name, event=kind):
                    self.assertEqual(list(validator.iter_errors(message(kind, payload))), [])

    def test_every_message_rejects_wrong_version_unexpected_and_missing_field(self):
        for name, events in EVENTS.items():
            validator = load_validator(name)
            for kind, payload in events.items():
                with self.subTest(schema=name, event=kind):
                    correct = message(kind, payload)
                    for patch in ({"protocolVersion": "2.0"}, {"extra": "unexpected"}):
                        self.assertFalse(validator.is_valid({**correct, **patch}))
                    missing_sequence = dict(correct)
                    del missing_sequence["sequence"]
                    self.assertFalse(validator.is_valid(missing_sequence))
                    extra_payload = dict(correct)
                    extra_payload["payload"] = {**payload, "unexpected": 1}
                    self.assertFalse(validator.is_valid(extra_payload))

    def test_rejects_bad_pairing_code_and_unapproved_side(self):
        validator = load_validator("pairing/pairing-messages.schema.json")
        self.assertFalse(validator.is_valid(message("pairing.claim", {
            "pairingId": "pairing-1", "code": "123", "deviceLabel": "Android"
        })))
        self.assertFalse(validator.is_valid(message("pairing.approval", {
            "pairingId": "pairing-1", "side": "ATTACKER", "approved": True
        })))

    def test_rejects_input_without_grant_or_outside_screen(self):
        validator = load_validator("input/input-event.schema.json")
        self.assertFalse(validator.is_valid(message("pointer.down", {"button": "left"})))
        self.assertFalse(validator.is_valid(message("pointer.down", {
            "grantId": GRANT, "button": "unknown"
        })))
        self.assertFalse(validator.is_valid(message("pointer.move", {
            "grantId": GRANT, "displayId": "primary", "x": 1.1, "y": 0.5
        })))

    def test_non_json_nan_and_lost_without_reason(self):
        # JSON Schema validates JSON values; NaN is not JSON. Ensure the
        # production serializer does not silently output a NaN extension.
        bad = message("gesture.move", {"x": float("nan"), "y": 0.4, "confidence": 0.9})
        with self.assertRaises(ValueError):
            json.dumps(bad, allow_nan=False)
        validator = load_validator("gesture/gesture-event.schema.json")
        self.assertFalse(validator.is_valid(message("tracking.lost", {})))

    def test_session_state_projection_schema(self):
        validator = load_validator("session/session-state.schema.json")
        valid = dict(state="DISCONNECTED", safety="UNKNOWN", hostApproved=False,
                     updatedAtMs=0, foreground=False, authVerified=False,
                     heartbeatFresh=False, uxAllowed=False, sessionId=None)
        self.assertTrue(validator.is_valid(valid))
        self.assertFalse(validator.is_valid({**valid, "safety": "I_SAY_PARKED"}))
        self.assertFalse(validator.is_valid({**valid, "unexpected": "yes"}))


if __name__ == "__main__":
    unittest.main()
