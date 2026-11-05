"""Strict diagnostic handshake parser. Never authorizes Windows OS input."""
from __future__ import annotations

import json
import re
from typing import Any

_SESSION = re.compile(r"^[A-Za-z0-9._-]{1,128}$")


class DiagnosticProtocolError(ValueError):
    pass


def parse_debug_hello(raw: str) -> tuple[str, str]:
    """Return (session_id, token); caller must compare token without logging.

    This is a local debug-only envelope check, not a production auth design.
    """
    try:
        event: Any = json.loads(raw)
    except (json.JSONDecodeError, TypeError) as exc:
        raise DiagnosticProtocolError("malformed JSON") from exc
    if not isinstance(event, dict):
        raise DiagnosticProtocolError("not a JSON object")
    if set(event) != {"protocolVersion", "type", "sessionId", "sequence", "timestampMs", "payload"}:
        raise DiagnosticProtocolError("invalid envelope properties")
    if event["protocolVersion"] != "0.1" or event["type"] != "auth.hello":
        raise DiagnosticProtocolError("not a supported diagnostic hello")
    sid = event["sessionId"]
    if not isinstance(sid, str) or not _SESSION.fullmatch(sid):
        raise DiagnosticProtocolError("invalid session id")
    if type(event["sequence"]) is not int or event["sequence"] < 0:
        raise DiagnosticProtocolError("invalid sequence")
    if type(event["timestampMs"]) is not int or event["timestampMs"] < 0:
        raise DiagnosticProtocolError("invalid timestamp")
    payload = event["payload"]
    if not isinstance(payload, dict) or set(payload) != {"token"}:
        raise DiagnosticProtocolError("invalid hello payload")
    token = payload["token"]
    if not isinstance(token, str) or not 24 <= len(token) <= 512:
        raise DiagnosticProtocolError("invalid token length")
    return sid, token
