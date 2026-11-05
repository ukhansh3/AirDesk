import json
import unittest
from airdesk_gesture.protocol import DiagnosticProtocolError, parse_debug_hello


def valid_message():
    return {
        "protocolVersion": "0.1",
        "type": "auth.hello",
        "sessionId": "diagnostic-client",
        "sequence": 0,
        "timestampMs": 12345,
        "payload": {"token": "a-test-only-long-random-token-123"},
    }


class DebugProtocolTests(unittest.TestCase):
    def test_hello(self):
        self.assertEqual(parse_debug_hello(json.dumps(valid_message()))[0], "diagnostic-client")

    def test_reject_bad_format(self):
        bad = [
            "{", "[]", "null",
            json.dumps({**valid_message(), "type": "pointer.move"}),
            json.dumps({**valid_message(), "protocolVersion": "1.0"}),
            json.dumps({**valid_message(), "payload": {"token": "short"}}),
            json.dumps({**valid_message(), "payload": {"token": "a-test-only-long-random-token-123", "secret": "bad"}}),
            json.dumps({**valid_message(), "sequence": -1}),
            json.dumps({**valid_message(), "timestampMs": True}),
            json.dumps({**valid_message(), "sessionId": "bad space"}),
            json.dumps({**valid_message(), "extra": 42}),
        ]
        for raw in bad:
            with self.subTest(raw=raw[:60]):
                with self.assertRaises(DiagnosticProtocolError):
                    parse_debug_hello(raw)


if __name__ == "__main__":
    unittest.main()
