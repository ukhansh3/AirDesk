"""Stable visualization only. CameraSample remains raw for independent input validation."""
from __future__ import annotations

from dataclasses import dataclass
from math import dist, isfinite

from .stabilization import StablePointFilter, DEFAULT_POINT_STABILIZATION


@dataclass(frozen=True)
class OverlayHand:
    handedness: str
    points: tuple[tuple[float, float], ...]


class CameraOverlayStabilizer:
    def __init__(self, max_hands: int = 2):
        self.max_hands = max_hands
        self._points = {}
        self._wrists = {}
        self._last_ms = -1
        self._order = None

    def reset(self):
        self._points.clear(); self._wrists.clear(); self._order = None; self._last_ms = -1

    def _clear_hand(self, key):
        for identity in [identity for identity in self._points if identity[0] == key]:
            del self._points[identity]

    def update(self, observations, now_ms: int) -> tuple[OverlayHand, ...]:
        if type(now_ms) is not int or now_ms < 0 or now_ms <= self._last_ms:
            self.reset(); return ()
        if self._last_ms >= 0 and now_ms - self._last_ms > DEFAULT_POINT_STABILIZATION.maximum_gap_ms:
            self.reset()
        self._last_ms = now_ms
        if len(observations) > self.max_hands:
            self.reset(); return ()
        labels = [hand.handedness for hand in observations]
        if self.max_hands == 2 and len(labels) != len(set(labels)):
            self.reset(); return ()
        valid = {}
        for hand in observations:
            key = hand.handedness if self.max_hands == 2 else "single"
            try:
                usable = len(hand.landmarks) == 21 and all(len(p) == 3 and all(isfinite(v) for v in p) and
                    0 <= p[0] <= 1 and 0 <= p[1] <= 1 and abs(p[2]) <= 2 for p in hand.landmarks)
                if self.max_hands == 2:
                    usable = usable and key in {"left", "right"} and isfinite(hand.score) and .75 <= hand.score <= 1
                if not usable:
                    self._clear_hand(key); self._wrists.pop(key, None); continue
                wrist = hand.landmarks[0][:2]
                previous = self._wrists.get(key)
                self._wrists[key] = wrist
                if previous is not None and dist(previous, wrist) > DEFAULT_POINT_STABILIZATION.jump_distance:
                    self._clear_hand(key); continue
                valid[key] = hand
            except (TypeError, ValueError):
                self._clear_hand(key); self._wrists.pop(key, None)
        if "left" in valid and "right" in valid:
            order = valid["right"].landmarks[0][0] - valid["left"].landmarks[0][0]
            if abs(order) < .08:
                self.reset(); return ()
            if self._order is not None and self._order * order < 0:
                self._points.clear(); self._order = order
                return ()
            self._order = order
        for key in set(self._wrists) - set(valid):
            # A rejected jump keeps its new raw anchor, but never keeps old displayed points.
            self._clear_hand(key)
            if key not in labels and not (key == "single" and observations): self._wrists.pop(key, None)
        result = []
        for key, hand in valid.items():
            points = []
            for index, point in enumerate(hand.landmarks):
                smoother = self._points.setdefault((key, index), StablePointFilter())
                filtered = smoother.update(point[:2], now_ms)
                if filtered is None: break
                points.append(filtered)
            if len(points) == 21: result.append(OverlayHand(hand.handedness, tuple(points)))
            else: self._clear_hand(key)
        return tuple(result)
