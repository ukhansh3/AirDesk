"""Deterministic synthetic hand frames — clearly not webcam evidence."""
from __future__ import annotations

def hand(index_x: float, index_y: float, pinched: bool = False):
    points = [(0.4, 0.6)] * 21
    points[0] = (0.5, 0.85)
    points[9] = (0.5, 0.45)
    points[8] = (index_x, index_y)
    points[4] = (index_x + (0.08 if pinched else 0.28), index_y)
    return points

def sample_frames(count: int = 20):
    for i in range(count):
        x = 0.2 + i * 0.45 / max(1, count - 1)
        yield (1000 + i * 34, hand(x, 0.4, pinched=3 <= i < max(5, count - 3)))
    yield (1000 + count * 34, None)
