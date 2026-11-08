"""Stable visualization only. CameraSample remains raw for independent input validation."""
from __future__ import annotations

from dataclasses import dataclass
from math import dist, isfinite

from .stabilization import StablePointFilter, DEFAULT_POINT_STABILIZATION


@dataclass(frozen=True)
class OverlayHand:
    handedness: str
    points: tuple[tuple[float, float], ...]


_REASON_LABELS = {
    "NO_DETECTIONS": "no hand detected", "INVALID_CLOCK": "invalid frame clock",
    "TOO_MANY_HANDS": "too many hands", "DUPLICATE_SIDE": "duplicate left/right label",
    "UNKNOWN_SIDE": "unknown left/right label", "LOW_SIDE_SCORE": "low handedness score",
    "INVALID_LANDMARKS": "invalid/outside landmarks", "WRIST_JUMP": "wrist jump",
    "WRIST_OVERLAP": "wrists too close", "SIDE_ORDER_CHANGED": "left/right order changed",
    "POINT_FILTER_REJECTED": "invalid point sample",
}


@dataclass(frozen=True)
class PreviewDiagnostics:
    """Current-frame counts/codes only; no images, coordinates or stored history.

    Displayed means eligible for this PREVIEW overlay, never accepted AIR input.
    The engine additionally validates palm/finger geometry and calibration state.
    """
    detected_hands: int = 0
    displayed_hands: int = 0
    rejection_counts: tuple[tuple[str, int], ...] = ()

    def lines(self) -> tuple[str, str, str]:
        detected = "3+" if self.detected_hands >= 3 else str(self.detected_hands)
        reasons = [f"{_REASON_LABELS[code]} x{count}" for code, count in self.rejection_counts[:2]]
        if len(self.rejection_counts) > 2:
            reasons.append(f"+{len(self.rejection_counts)-2} more")
        return (f"Preview only | detected {detected} | drawn {self.displayed_hands}",
                "Guard: " + ("; ".join(reasons) if reasons else "OK for drawing only"),
                "Not AIR accepted; side score != landmark confidence")


class CameraOverlayStabilizer:
    def __init__(self, max_hands: int = 2):
        self.max_hands = max_hands
        self._points = {}
        self._wrists = {}
        self._last_ms = -1
        self._order = None
        self._diagnostics = PreviewDiagnostics()

    @property
    def diagnostics(self) -> PreviewDiagnostics:
        return self._diagnostics

    def _report(self, detected: int, displayed: int, reasons):
        self._diagnostics = PreviewDiagnostics(min(detected, 3), min(displayed, 2),
            tuple((code, min(3, count)) for code, count in reasons.items() if code in _REASON_LABELS and count > 0))

    def reset(self):
        self._points.clear(); self._wrists.clear(); self._order = None; self._last_ms = -1
        self._diagnostics = PreviewDiagnostics()

    def _clear_hand(self, key):
        for identity in [identity for identity in self._points if identity[0] == key]:
            del self._points[identity]

    def update(self, observations, now_ms: int) -> tuple[OverlayHand, ...]:
        detected = len(observations)
        reasons = {}
        def rejected(code, count=1):
            reasons[code] = reasons.get(code, 0) + count
        if type(now_ms) is not int or now_ms < 0 or now_ms <= self._last_ms:
            self.reset(); self._report(detected, 0, {"INVALID_CLOCK": 1}); return ()
        if self._last_ms >= 0 and now_ms - self._last_ms > DEFAULT_POINT_STABILIZATION.maximum_gap_ms:
            self.reset()
        self._last_ms = now_ms
        if len(observations) > self.max_hands:
            self.reset(); self._report(detected, 0, {"TOO_MANY_HANDS": detected}); return ()
        labels = [hand.handedness for hand in observations]
        if self.max_hands == 2 and len(labels) != len(set(labels)):
            self.reset(); self._report(detected, 0, {"DUPLICATE_SIDE": detected}); return ()
        valid = {}
        for hand in observations:
            key = hand.handedness if self.max_hands == 2 else "single"
            try:
                usable = len(hand.landmarks) == 21 and all(len(p) == 3 and all(isfinite(v) for v in p) and
                    0 <= p[0] <= 1 and 0 <= p[1] <= 1 and abs(p[2]) <= 2 for p in hand.landmarks)
                if not usable: rejected("INVALID_LANDMARKS")
                if self.max_hands == 2:
                    if key not in {"left", "right"}: rejected("UNKNOWN_SIDE")
                    if not isfinite(hand.score) or not .75 <= hand.score <= 1: rejected("LOW_SIDE_SCORE")
                    usable = usable and key in {"left", "right"} and isfinite(hand.score) and .75 <= hand.score <= 1
                if not usable:
                    self._clear_hand(key); self._wrists.pop(key, None); continue
                wrist = hand.landmarks[0][:2]
                previous = self._wrists.get(key)
                self._wrists[key] = wrist
                if previous is not None and dist(previous, wrist) > DEFAULT_POINT_STABILIZATION.jump_distance:
                    rejected("WRIST_JUMP")
                    self._clear_hand(key); continue
                valid[key] = hand
            except (TypeError, ValueError):
                rejected("INVALID_LANDMARKS")
                self._clear_hand(key); self._wrists.pop(key, None)
        if "left" in valid and "right" in valid:
            order = valid["right"].landmarks[0][0] - valid["left"].landmarks[0][0]
            if abs(order) < .08:
                self.reset(); self._report(detected, 0, {"WRIST_OVERLAP": 2}); return ()
            if self._order is not None and self._order * order < 0:
                self._points.clear(); self._order = order
                self._report(detected, 0, {"SIDE_ORDER_CHANGED": 2})
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
            else:
                rejected("POINT_FILTER_REJECTED")
                self._clear_hand(key)
        if not observations: rejected("NO_DETECTIONS")
        self._report(detected, len(result), reasons)
        return tuple(result)
