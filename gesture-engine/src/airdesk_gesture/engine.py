"""Pure deterministic gesture engine; input 21 normalized landmarks."""
from __future__ import annotations

from dataclasses import dataclass
from math import hypot, isfinite
from typing import Iterable, Sequence


@dataclass(frozen=True)
class EngineConfig:
    press_ratio: float = 0.32
    release_ratio: float = 0.49
    drag_distance: float = 0.035
    smoothing_alpha: float = 0.38

    def __post_init__(self):
        if not (0 < self.press_ratio < self.release_ratio):
            raise ValueError("press_ratio must be below release_ratio")
        if not 0 < self.smoothing_alpha <= 1:
            raise ValueError("invalid smoothing alpha")
        if self.drag_distance < 0:
            raise ValueError("negative drag distance")


class GestureEngine:
    """Gesture event producer. No OS input. Timestamps are producer monotonic ms."""

    def __init__(self, config: EngineConfig | None = None, session_id: str = "synthetic-demo"):
        self.config = config or EngineConfig()
        self.session_id = session_id
        self.seq = 0
        self.last_timestamp = -1
        self.state = "IDLE"
        self.last_xy: tuple[float, float] | None = None
        self.anchor: tuple[float, float] | None = None

    def _emit(self, kind: str, timestamp_ms: int, **payload: object) -> dict:
        event = {
            "protocolVersion": "0.1",
            "type": kind,
            "sessionId": self.session_id,
            "sequence": self.seq,
            "timestampMs": timestamp_ms,
            "payload": payload,
        }
        self.seq += 1
        return event

    @staticmethod
    def _validate_landmarks(points: Sequence[tuple[float, float]]):
        if len(points) != 21:
            raise ValueError("expected 21 landmark pairs")
        for p in points:
            if len(p) != 2 or not all(isfinite(float(v)) for v in p):
                raise ValueError("invalid or non-finite landmark")

    def process(self, points: Sequence[tuple[float, float]] | None, timestamp_ms: int,
                confidence: float = 1.0) -> list[dict]:
        if timestamp_ms < 0 or timestamp_ms <= self.last_timestamp:
            return []  # discard late or duplicate frames
        self.last_timestamp = timestamp_ms
        if points is None:
            if self.state in ("IDLE", "LOST"):
                return []
            out: list[dict] = []
            if self.state in ("PINCHED", "DRAGGING") and self.last_xy is not None:
                out.append(self._emit("gesture.pinch_end", timestamp_ms,
                                      x=self.last_xy[0], y=self.last_xy[1], confidence=0.0))
            out.append(self._emit("tracking.lost", timestamp_ms, reason="hand_not_detected"))
            self.state = "LOST"
            self.anchor = None
            self.last_xy = None
            return out
        self._validate_landmarks(points)
        if not isfinite(confidence) or not 0 <= confidence <= 1:
            raise ValueError("confidence must be normalized")
        x = max(0.0, min(1.0, float(points[8][0])))
        y = max(0.0, min(1.0, float(points[8][1])))
        if self.last_xy is not None:
            a = self.config.smoothing_alpha
            x, y = self.last_xy[0] * (1 - a) + x * a, self.last_xy[1] * (1 - a) + y * a
        self.last_xy = (x, y)

        # Pinch is scale invariant relative to the wrist/middle MCP reference.
        ref = max(hypot(points[0][0] - points[9][0], points[0][1] - points[9][1]), 1e-5)
        distance_ratio = hypot(points[4][0] - points[8][0], points[4][1] - points[8][1]) / ref
        was_pressing = self.state in ("PINCHED", "DRAGGING")
        pressing = distance_ratio < (self.config.release_ratio if was_pressing else self.config.press_ratio)

        events = [self._emit("gesture.move", timestamp_ms, x=x, y=y, confidence=confidence)]
        if pressing and not was_pressing:
            self.state = "PINCHED"
            self.anchor = (x, y)
            events.append(self._emit("gesture.pinch_start", timestamp_ms, x=x, y=y, confidence=confidence))
        elif pressing and self.state == "PINCHED" and self.anchor is not None:
            if hypot(x - self.anchor[0], y - self.anchor[1]) >= self.config.drag_distance:
                self.state = "DRAGGING"
                events.append(self._emit("gesture.drag_start", timestamp_ms, x=x, y=y, confidence=confidence))
        elif pressing and self.state == "DRAGGING":
            events.append(self._emit("gesture.drag_move", timestamp_ms, x=x, y=y, confidence=confidence))
        elif not pressing and was_pressing:
            self.state = "TRACKING"
            self.anchor = None
            events.append(self._emit("gesture.pinch_end", timestamp_ms, x=x, y=y, confidence=confidence))
        elif not pressing:
            self.state = "TRACKING"
        return events
