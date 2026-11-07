"""Pure normalized XY stabilization shared by camera dots and AIR hover hints.

This filters presentation/hover coordinates after raw validity and identity gates.
It never makes an invalid hand valid and never filters calibration features,
press/release decisions, timestamps, camera images or original landmark samples.
"""
from __future__ import annotations

from collections import deque
from dataclasses import dataclass
from math import dist, exp, isfinite, sqrt
from statistics import median


@dataclass(frozen=True)
class PointStabilizationConfig:
    median_window: int = 3
    tau_ms: float = 60.0
    deadzone: float = .004
    maximum_gap_ms: int = 750
    jump_distance: float = .25

    def __post_init__(self):
        if type(self.median_window) is not int or not 1 <= self.median_window <= 9 or self.median_window % 2 != 1:
            raise ValueError("median_window must be an odd integer from 1 to 9")
        if not isfinite(self.tau_ms) or not 0 < self.tau_ms <= 1000:
            raise ValueError("invalid point filter time constant")
        if not isfinite(self.deadzone) or not 0 <= self.deadzone <= .05:
            raise ValueError("invalid normalized point deadzone")
        if type(self.maximum_gap_ms) is not int or not 0 < self.maximum_gap_ms <= 10000:
            raise ValueError("invalid point filter gap")
        if not isfinite(self.jump_distance) or not self.deadzone < self.jump_distance <= sqrt(2):
            raise ValueError("invalid normalized point jump distance")


DEFAULT_POINT_STABILIZATION = PointStabilizationConfig()


class StablePointFilter:
    """Median(3) → time-aware EMA → radial soft deadzone, bounded per-point state.

    update((x, y), monotonic_ms) returns None and clears history for malformed,
    nonfinite, out-of-range or nonincreasing input. A fresh valid point starts at
    its own coordinate, never an old hand's coordinate. Call reset immediately
    when the caller's RAW hand/identity/lifecycle gate is lost.

    The median rejects one isolated spike. A sustained large median jump restarts
    the EMA at the new median instead of drawing a long path across old space.
    This is not a replacement for raw hand-identity/jump validation.
    """
    def __init__(self, config: PointStabilizationConfig = DEFAULT_POINT_STABILIZATION):
        self.config = config
        self.reset()

    def reset(self):
        self._points: deque[tuple[float, float]] = deque(maxlen=self.config.median_window)
        self._estimate: tuple[float, float] | None = None
        self._output: tuple[float, float] | None = None
        self._last_ms: int | None = None

    def update(self, point: tuple[float, float], timestamp_ms: int) -> tuple[float, float] | None:
        try:
            if len(point) != 2 or any(type(v) not in (int, float) or not isfinite(v) or not 0 <= v <= 1 for v in point):
                raise ValueError("invalid normalized point")
            if type(timestamp_ms) is not int or timestamp_ms < 0 or self._last_ms is not None and timestamp_ms <= self._last_ms:
                raise ValueError("invalid point clock")
            value = float(point[0]), float(point[1])
        except (TypeError, ValueError, OverflowError):
            self.reset()
            return None
        if self._last_ms is not None and timestamp_ms-self._last_ms > self.config.maximum_gap_ms:
            self.reset()
        if self._last_ms is None:
            # Seed the bounded median window: a second-frame spike must not leak
            # through the average of a temporarily even two-sample window.
            self._points.extend([value] * self.config.median_window)
            self._estimate = self._output = value
            self._last_ms = timestamp_ms
            return value
        elapsed = timestamp_ms-self._last_ms
        self._last_ms = timestamp_ms
        self._points.append(value)
        target = tuple(median(p[axis] for p in self._points) for axis in (0, 1))
        if dist(target, self._estimate) > self.config.jump_distance:
            self._points.clear()
            self._points.extend([target] * self.config.median_window)
            self._estimate = self._output = target
            return target
        alpha = 1. - exp(-elapsed/self.config.tau_ms)
        self._estimate = tuple(a+alpha*(b-a) for a, b in zip(self._estimate, target))
        distance = dist(self._estimate, self._output)
        if distance > self.config.deadzone:
            amount = (distance-self.config.deadzone)/distance
            self._output = tuple(a+amount*(b-a) for a, b in zip(self._output, self._estimate))
        return self._output
