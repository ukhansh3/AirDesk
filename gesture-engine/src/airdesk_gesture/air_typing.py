"""Experimental, calibrated ten-finger AIR typing hints. NEVER OS input.

Specification / boundary:
* This infers deliberate finger folding from relative 3D hand shape. It does not
  measure pressure, physical contact, absolute depth or a physical keyboard plane.
* Calibration captures neutral + each of ten deliberately flexed fingers. A pose
  that cannot be separated from neutral fails at that stage and may be retried.
* Camera normalized x/y map DIRECTLY to the full 1120x360 virtual surface. There
  is no hidden keyboard rescaling. The side pad is a separate intent region.
* Per-finger state is independent, has one Down per neutral-to-flex cycle, captures
  its pre-Down hover, and must observe neutral again after loss. No hold-repeat.
* Result array order is never identity. Duplicate labels, crossing, implausible
  track jumps, malformed observations and stale frames release affected ownership.
* Output contains bounded tracking hints only, no key code, text or input log.
  Consumer authorization, local ownership and versioned wire validation are separate.

Tests are synthetic model evidence. Reliable webcam AIR typing remains unverified.
"""
from __future__ import annotations

from dataclasses import dataclass
from math import dist, isfinite
from statistics import median
from uuid import uuid4

from .camera import CameraHand, CameraSample
from .engine import GestureEngine

FINGERS = ("thumb", "index", "middle", "ring", "little")
HANDS = ("left", "right")
TIP = dict(zip(FINGERS, (4, 8, 12, 16, 20)))
STAGES = ("NEUTRAL",) + tuple(f"{hand.upper()}_{finger.upper()}" for hand in HANDS for finger in FINGERS) + ("READY",)
# Same geometry as the full app-owned virtual layout. This is image projection,
# not a calibrated real-world plane or a safe vehicle input grant.
SURFACE_WIDTH = 1120
SURFACE_HEIGHT = 360
KEYBOARD_ROI = (8 / SURFACE_WIDTH, 44 / SURFACE_HEIGHT, 960 / SURFACE_WIDTH, 352 / SURFACE_HEIGHT)
MOUSE_ROI = (972 / SURFACE_WIDTH, 44 / SURFACE_HEIGHT, 1112 / SURFACE_WIDTH, 352 / SURFACE_HEIGHT)
MOUSE_EXIT_X = .84
COUNTDOWN_MS = 1500
CAPTURE_MS = 500
MIN_CAPTURE_FRAMES = 6
NEUTRAL_REARM_MS = 200
MOUSE_DWELL_MS = 300
DOWN_MS = 60
UP_MS = 50
DEBOUNCE_MS = 140
STALE_MS = 750
MAX_HOLD_MS = 5000


@dataclass
class _Finger:
    ready: bool = False
    pressed: bool = False
    pending_down: int | None = None
    pending_up: int | None = None
    down_at: int = -1
    last_up: int = -100000
    hover: tuple[float, float] = (.5, .5)
    captured: tuple[float, float] = (.5, .5)


class AirTypingEngine:
    """Pure bounded state, command-driven calibration, complete per-frame hints.

    Commands: start_calibration, capture, arm, disarm, reset. All time values use
    producer monotonic milliseconds. Unknown/invalid commands raise ValueError;
    an unavailable arm/capture request fails closed without throwing.
    """
    def __init__(self):
        self._reset()

    def _reset(self):
        self.epoch = uuid4().hex
        self.stage = "NEUTRAL"
        self.status = "WAITING"
        self.armed = False
        self._neutral: dict[tuple[str, str], float] = {}
        self._noise: dict[tuple[str, str], float] = {}
        self._thresholds: dict[tuple[str, str], tuple[float, float]] = {}
        self._fingers = {(hand, finger): _Finger() for hand in HANDS for finger in FINGERS}
        self._neutral_since: dict[str, int] = {}
        self._wrists: dict[str, tuple[float, float]] = {}
        self._neutral_order: float | None = None
        self._last_ms = -1
        self._capture_at: int | None = None
        self._samples: list[dict[tuple[str, str], float]] = []
        self._capture_invalid = False
        self._capture_order: list[float] = []
        self._mouse = GestureEngine()
        self._mouse_active = False
        self._mouse_since: int | None = None
        self._mouse_needs_release = True
        self._last_payload = self._payload({}, {})

    def command(self, action: str, now_ms: int) -> None:
        if type(now_ms) is not int or now_ms < 0:
            raise ValueError("invalid command time")
        if action not in {"start_calibration", "capture", "arm", "disarm", "reset"}:
            raise ValueError("unknown AIR typing command")
        if action in {"reset", "start_calibration"}:
            self._reset()
            return
        if action == "disarm":
            self.armed = False
            self._release_all()
            self._capture_at = None
            self.status = "READY" if self.stage == "READY" else "WAITING"
        elif action == "arm":
            self._release_all()
            self.armed = self.stage == "READY" and len(self._thresholds) == 10 and self._capture_at is None
            self.status = "ARMED" if self.armed else "FAILED"
        elif action == "capture":
            self.armed = False
            self._release_all()
            if self.stage == "READY" or self._capture_at is not None:
                return
            self._capture_at = now_ms + COUNTDOWN_MS
            self._samples = []
            self._capture_order = []
            self._capture_invalid = False
            self.status = "COUNTDOWN"

    @staticmethod
    def _folding(points: tuple[tuple[float, float, float], ...], indexes: tuple[int, ...]) -> float:
        chain = [points[i] for i in indexes]
        length = sum(dist(a, b) for a, b in zip(chain, chain[1:]))
        if length < .015:
            raise ValueError("degenerate finger chain")
        return max(0., min(1., 1. - dist(chain[0], chain[-1]) / length))

    @classmethod
    def _thumb_feature(cls, points: tuple[tuple[float, float, float], ...]) -> float:
        # Thumb has CMC/MCP/IP anatomy, not the other fingers' MCP/PIP/DIP chain.
        return cls._folding(points, (1, 2, 3, 4))

    @classmethod
    def features(cls, hand: CameraHand) -> dict[str, float]:
        if len(hand.landmarks) != 21 or not isfinite(hand.y_scale) or not .1 <= hand.y_scale <= 10:
            raise ValueError("invalid hand dimensions")
        points = tuple((float(x), float(y) * hand.y_scale, float(z)) for x, y, z in hand.landmarks)
        if not all(isfinite(v) for p in points for v in p) or dist(points[0], points[9]) < .02:
            raise ValueError("invalid hand shape")
        values = {"thumb": cls._thumb_feature(points)}
        for name, base in zip(FINGERS[1:], (5, 9, 13, 17)):
            values[name] = cls._folding(points, (base, base+1, base+2, base+3))
        return values

    def _observations(self, sample: CameraSample) -> tuple[dict[str, CameraHand], dict[tuple[str, str], float]]:
        hands: dict[str, CameraHand] = {}
        features: dict[tuple[str, str], float] = {}
        known_labels = [h.handedness for h in sample.hands if h.handedness in HANDS]
        if len(sample.hands) > 2 or len(known_labels) != len(set(known_labels)):
            return {}, {}
        for hand in sample.hands:
            try:
                if hand.handedness not in HANDS or not isfinite(hand.score) or not .75 <= hand.score <= 1:
                    continue
                if any(len(p) != 3 or not all(isfinite(v) for v in p) or
                       not 0 <= p[0] <= 1 or not 0 <= p[1] <= 1 or abs(p[2]) > 2 for p in hand.landmarks):
                    continue
                values = self.features(hand)
                wrist = hand.landmarks[0][:2]
                previous = self._wrists.get(hand.handedness)
                if previous is not None and dist(wrist, previous) > .25:
                    # Forget old association but reject the jump frame, never carry held state.
                    self._wrists[hand.handedness] = wrist
                    continue
                hands[hand.handedness] = hand
                features.update({(hand.handedness, name): value for name, value in values.items()})
                self._wrists[hand.handedness] = wrist
            except (TypeError, ValueError, OverflowError):
                continue
        if len(hands) == 2:
            order = hands["right"].landmarks[0][0] - hands["left"].landmarks[0][0]
            if abs(order) < .08 or self._neutral_order is not None and order * self._neutral_order <= 0:
                return {}, {}
        return hands, features

    def _release_hand(self, hand: str):
        for name in FINGERS:
            self._fingers[hand, name] = _Finger()
        self._neutral_since.pop(hand, None)
        if hand == "right":
            self._mouse_active = False
            self._mouse_since = None
            self._mouse = GestureEngine()
            self._mouse_needs_release = True

    def _release_all(self):
        for hand in HANDS:
            self._release_hand(hand)

    def _calibrate(self, now: int, hands: dict[str, CameraHand], features: dict[tuple[str, str], float]):
        if self._capture_at is None:
            return
        if now < self._capture_at:
            self.status = "COUNTDOWN"
            return
        if now < self._capture_at + CAPTURE_MS:
            self.status = "CAPTURING"
            if len(hands) == 2 and len(features) == 10:
                self._samples.append(features.copy())
                self._capture_order.append(hands["right"].landmarks[0][0] - hands["left"].landmarks[0][0])
            else:
                self._capture_invalid = True
            return
        self._capture_at = None
        if self._capture_invalid or len(self._samples) < MIN_CAPTURE_FRAMES:
            self.status = "FAILED"
            return
        center = {key: median(s[key] for s in self._samples) for key in self._fingers}
        spread = {key: max(s[key] for s in self._samples)-min(s[key] for s in self._samples) for key in self._fingers}
        if self.stage == "NEUTRAL":
            if any(center[key] > .6 or spread[key] > .08 for key in center):
                self.status = "FAILED"
                return
            self._neutral, self._noise = center, spread
            self._neutral_order = median(self._capture_order)
        else:
            side, name = self.stage.lower().split("_", 1)
            key = side, name
            delta = center[key] - self._neutral[key]
            minimum = max(.08, 4 * self._noise[key])
            if delta < minimum or spread[key] > max(.08, delta * .3) or any(
                abs(center[other]-self._neutral[other]) > max(.10, delta*.65)
                for other in center if other != key
            ):
                self.status = "FAILED"
                return
            self._thresholds[key] = (self._neutral[key] + delta*.55, self._neutral[key] + delta*.25)
        self.stage = STAGES[STAGES.index(self.stage) + 1]
        self.status = "READY" if self.stage == "READY" else "WAITING"

    def _neutral_hand(self, hand: str, features: dict[tuple[str, str], float]) -> bool:
        return all((hand, name) in self._thresholds and features.get((hand, name), 2.) <= self._thresholds[hand, name][1]
                   for name in FINGERS)

    def _typing(self, hand: CameraHand, features: dict[tuple[str, str], float], now: int):
        side = hand.handedness
        if self._neutral_hand(side, features):
            self._neutral_since.setdefault(side, now)
        else:
            self._neutral_since.pop(side, None)
        rearmed = side in self._neutral_since and now-self._neutral_since[side] >= NEUTRAL_REARM_MS
        for name in FINGERS:
            key = side, name
            state = self._fingers[key]
            xy = hand.landmarks[TIP[name]][:2]
            down, up = self._thresholds[key]
            value = features[key]
            if not state.ready:
                if rearmed:
                    state.ready = True
                    state.hover = xy
                continue
            if state.pressed:
                if now-state.down_at > MAX_HOLD_MS:
                    self._fingers[key] = _Finger()
                elif value <= up:
                    if state.pending_up is None:
                        state.pending_up = now
                    if now-state.pending_up >= UP_MS:
                        state.pressed = False
                        state.pending_up = None
                        state.pending_down = None
                        state.last_up = now
                        state.hover = xy
                else:
                    state.pending_up = None
            elif value >= down:
                if state.pending_down is None:
                    state.pending_down = now
                    state.captured = state.hover
                if now-state.pending_down >= DOWN_MS and now-state.last_up >= DEBOUNCE_MS:
                    state.pressed = True
                    state.down_at = now
                    state.pending_down = None
            else:
                state.pending_down = None
                state.hover = xy

    def _update_mouse(self, hand: CameraHand, features: dict[tuple[str, str], float], now: int) -> bool:
        wrist_x, wrist_y, _ = hand.landmarks[0]
        in_pad = wrist_x >= MOUSE_ROI[0] and MOUSE_ROI[1] <= wrist_y <= MOUSE_ROI[3]
        if not self._mouse_active:
            if in_pad:
                for name in FINGERS:
                    self._fingers["right", name] = _Finger()
                self._neutral_since.pop("right", None)
                if self._neutral_hand("right", features) and self._pinch_open(hand):
                    if self._mouse_since is None:
                        self._mouse_since = now
                    self._mouse_active = now-self._mouse_since >= MOUSE_DWELL_MS
                else:
                    self._mouse_since = None
                return True
            self._mouse_since = None
            return False
        if wrist_x < MOUSE_EXIT_X or not MOUSE_ROI[1] <= wrist_y <= MOUSE_ROI[3]:
            self._release_hand("right")
            return True
        x, y, _ = hand.landmarks[8]
        if not MOUSE_ROI[0] <= x <= MOUSE_ROI[2] or not MOUSE_ROI[1] <= y <= MOUSE_ROI[3]:
            self._mouse = GestureEngine()
            self._mouse_needs_release = True
            return True
        if self._mouse_needs_release:
            if not self._pinch_open(hand):
                return True
            self._mouse_needs_release = False
        self._mouse.process([(x, y) for x, y, _ in hand.landmarks], now)
        return True

    def _pinch_open(self, hand: CameraHand) -> bool:
        p = hand.landmarks
        reference = dist(p[0][:2], p[9][:2])
        return reference > 1e-5 and dist(p[4][:2], p[8][:2])/reference >= self._mouse.config.release_ratio

    def _payload(self, hands: dict[str, CameraHand], features: dict[tuple[str, str], float]) -> dict:
        fingers = []
        for side in HANDS:
            hand = hands.get(side)
            for name in FINGERS:
                if hand is None:
                    continue
                state = self._fingers[side, name]
                x, y = state.captured if state.pressed else hand.landmarks[TIP[name]][:2]
                fingers.append({"hand": side, "finger": name, "x": x, "y": y,
                                "pressed": bool(self.armed and state.pressed and not (side == "right" and self._mouse_active)),
                                "valid": bool(not (side == "right" and self._mouse_active))})
        mouse = {"active": self.armed and self._mouse_active, "valid": False,
                 "x": .5, "y": .5, "pressed": False, "dragging": False}
        if mouse["active"] and "right" in hands and self._mouse.last_xy is not None:
            x, y = self._mouse.last_xy
            left, top, right, bottom = MOUSE_ROI
            if left <= x <= right and top <= y <= bottom:
                mouse.update(valid=True, x=(x-left)/(right-left), y=(y-top)/(bottom-top),
                             pressed=self._mouse.state in {"PINCHED", "DRAGGING"}, dragging=self._mouse.state == "DRAGGING")
        return {"epoch": self.epoch, "stage": self.stage, "status": self.status,
                "armed": self.armed, "fingers": fingers, "mouse": mouse}

    def process(self, sample: CameraSample) -> dict:
        now = sample.timestamp_ms
        if type(now) is not int or now < 0 or now <= self._last_ms:
            return self._last_payload
        if self._last_ms >= 0 and now-self._last_ms > STALE_MS:
            self._release_all()
        self._last_ms = now
        hands, features = self._observations(sample)
        if sample.loss_reason != "hand_not_detected" and not sample.hands:
            self.armed = False
            self._release_all()
            self.status = "READY" if self.stage == "READY" else "FAILED"
        self._calibrate(now, hands, features)
        for side in HANDS:
            if side not in hands:
                self._release_hand(side)
        if self.armed:
            for side in HANDS:
                hand = hands.get(side)
                if hand is not None and not (side == "right" and self._update_mouse(hand, features, now)):
                    self._typing(hand, features, now)
            self.status = "ARMED"
        self._last_payload = self._payload(hands, features)
        return self._last_payload
