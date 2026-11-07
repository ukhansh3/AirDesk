"""Experimental, calibrated ten-finger AIR typing hints. NEVER OS input.

Specification / boundary:
* This infers deliberate finger folding/MCP depression from relative 3D hand shape. It does not
  measure pressure, physical contact, absolute depth or a physical keyboard plane.
* One start runs guided automatic stable-pose capture: neutral + ten finger strokes.
  Manual capture remains available. Each finger learns its own shape-vector direction. A pose
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
AUTO_START_MS = 5000
AUTO_NEUTRAL_MS = 700
AUTO_IDLE_MS = 15000
Feature = tuple[float, float]


def _subtract(a, b):
    return tuple(x-y for x, y in zip(a, b))


def _dot(a, b):
    return sum(x*y for x, y in zip(a, b))


def _unit(vector):
    length = dist(vector, (0.,)*len(vector))
    if length < 1e-5:
        raise ValueError("degenerate palm/finger direction")
    return tuple(v/length for v in vector)


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
    neutral_since: int | None = None


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
        self._neutral: dict[tuple[str, str], Feature] = {}
        self._noise: dict[tuple[str, str], float] = {}
        self._thresholds: dict[tuple[str, str], tuple[float, float]] = {}
        self._directions: dict[tuple[str, str], Feature] = {}
        self._fingers = {(hand, finger): _Finger() for hand in HANDS for finger in FINGERS}
        self._wrists: dict[str, tuple[float, float]] = {}
        self._neutral_order: float | None = None
        self._last_ms = -1
        self._capture_at: int | None = None
        self._samples: list[dict[tuple[str, str], Feature]] = []
        self._capture_invalid = False
        self._capture_order: list[float] = []
        self._automatic = False
        self._auto_not_before = 0
        self._auto_neutral_since: int | None = None
        self._auto_ready = False
        self._auto_ready_since = 0
        self._sample_times: list[int] = []
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
            if action == "start_calibration":
                self._automatic = True
                self._auto_not_before = now_ms + AUTO_START_MS
                self.status = "COUNTDOWN"
            return
        self._automatic = False  # capture is the explicit legacy/manual fallback.
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
            self._sample_times = []
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
    def _thumb_feature(cls, points: tuple[tuple[float, float, float], ...], normal) -> Feature:
        # Thumb has CMC/MCP/IP anatomy, not the other fingers' MCP/PIP/DIP chain.
        return cls._folding(points, (1, 2, 3, 4)), _dot(_unit(_subtract(points[3], points[2])), normal)

    @classmethod
    def features(cls, hand: CameraHand) -> dict[str, Feature]:
        """(chain folding, signed MCP flex relative to palm), both dimensionless.

        A straight MCP-only stroke has no chain shortening. Its proximal phalanx
        does change direction relative to the palm normal, while translating or
        rigidly rotating the whole hand changes neither feature. Calibration learns
        the sign/direction; no camera-facing or anatomical down sign is guessed.
        """
        if len(hand.landmarks) != 21 or not isfinite(hand.y_scale) or not .1 <= hand.y_scale <= 10:
            raise ValueError("invalid hand dimensions")
        points = tuple((float(x), float(y) * hand.y_scale, float(z)) for x, y, z in hand.landmarks)
        if not all(isfinite(v) for p in points for v in p) or dist(points[0], points[9]) < .02:
            raise ValueError("invalid hand shape")
        a, b = _subtract(points[5], points[0]), _subtract(points[17], points[0])
        normal = _unit((a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]))
        values = {"thumb": cls._thumb_feature(points, normal)}
        for name, base in zip(FINGERS[1:], (5, 9, 13, 17)):
            values[name] = (cls._folding(points, (base, base+1, base+2, base+3)),
                            _dot(_unit(_subtract(points[base+1], points[base])), normal))
        return values

    def _observations(self, sample: CameraSample) -> tuple[dict[str, CameraHand], dict[tuple[str, str], Feature]]:
        hands: dict[str, CameraHand] = {}
        features: dict[tuple[str, str], Feature] = {}
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
        if hand == "right":
            self._mouse_active = False
            self._mouse_since = None
            self._mouse = GestureEngine()
            self._mouse_needs_release = True

    def _release_all(self):
        for hand in HANDS:
            self._release_hand(hand)

    def _calibrate(self, now: int, hands: dict[str, CameraHand], features: dict[tuple[str, str], Feature]):
        if self._automatic:
            self._auto_calibrate(now, hands, features)
            return
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
        self._finish_capture()

    def _capture_statistics(self):
        center = {key: tuple(median(s[key][axis] for s in self._samples) for axis in (0, 1)) for key in self._fingers}
        spread = {key: 2*max(dist(s[key], center[key]) for s in self._samples) for key in self._fingers}
        return center, spread

    def _finish_capture(self) -> bool:
        if self._capture_invalid or len(self._samples) < MIN_CAPTURE_FRAMES:
            self.status = "FAILED"
            return False
        center, spread = self._capture_statistics()
        if self.stage == "NEUTRAL":
            if any(center[key][0] > .6 or spread[key] > .08 for key in center):
                self.status = "FAILED"
                return False
            self._neutral, self._noise = center, spread
            self._neutral_order = median(self._capture_order)
        else:
            side, name = self.stage.lower().split("_", 1)
            key = side, name
            difference = _subtract(center[key], self._neutral[key])
            delta = dist(center[key], self._neutral[key])
            minimum = max(.08, 4 * self._noise[key])
            if delta < minimum or spread[key] > max(.08, delta * .3) or any(
                dist(center[other], self._neutral[other]) > max(.10, delta*.65)
                for other in center if other != key
            ):
                self.status = "FAILED"
                return False
            self._directions[key] = _unit(difference)
            self._thresholds[key] = (delta*.55, delta*.25)
        self.stage = STAGES[STAGES.index(self.stage) + 1]
        self.status = "READY" if self.stage == "READY" else "WAITING"
        return True

    def _clear_auto_window(self):
        self._samples = []
        self._sample_times = []
        self._capture_order = []
        self._capture_invalid = False

    def _all_neutral(self, features) -> bool:
        return len(features) == 10 and all(dist(features[key], self._neutral[key]) <= max(.08, 4*self._noise[key])
                                          for key in self._fingers)

    def _auto_calibrate(self, now: int, hands: dict[str, CameraHand], features):
        if now < self._auto_not_before:
            self.status = "COUNTDOWN"
            return
        if len(hands) != 2 or len(features) != 10:
            self._clear_auto_window()
            self._auto_neutral_since = None
            self._auto_ready = False
            self.status = "WAITING"
            return
        target = None if self.stage == "NEUTRAL" else tuple(self.stage.lower().split("_", 1))
        if target is not None:
            if not self._auto_ready:
                if self._all_neutral(features):
                    if self._auto_neutral_since is None:
                        self._auto_neutral_since = now
                    self.status = "COUNTDOWN"
                    if now-self._auto_neutral_since >= AUTO_NEUTRAL_MS:
                        self._auto_ready = True
                        self._auto_ready_since = now
                        self.status = "WAITING"
                else:
                    self._auto_neutral_since = None
                    self.status = "COUNTDOWN" if self.status != "FAILED" else "FAILED"
                return
            delta = dist(features[target], self._neutral[target])
            coupled = any(dist(features[key], self._neutral[key]) > max(.10, delta*.65)
                          for key in self._fingers if key != target)
            if coupled:
                self._clear_auto_window()
                self._auto_ready = False
                self._auto_neutral_since = None
                self.status = "FAILED"
                return
            if delta < max(.08, 4*self._noise[target]):
                self._clear_auto_window()
                self.status = "WAITING"
                # Idle prompts stay on this stage; never capture a blind timer pose.
                if now-self._auto_ready_since >= AUTO_IDLE_MS:
                    self._auto_ready_since = now
                return
        elif any(value[0] > .6 for value in features.values()):
            self._clear_auto_window()
            self.status = "WAITING"
            return
        self._samples.append(features.copy())
        self._sample_times.append(now)
        self._capture_order.append(hands["right"].landmarks[0][0] - hands["left"].landmarks[0][0])
        # Let the user settle into the pose: discard an unstable leading sample
        # instead of demanding a new click or mistaking the initial movement for a hold.
        while len(self._samples) > 1:
            _, spread = self._capture_statistics()
            limit = max(.08, dist(features[target], self._neutral[target])*.3) if target else .08
            if all(spread[key] <= (limit if key == target else .08) for key in self._fingers):
                break
            self._samples.pop(0); self._sample_times.pop(0); self._capture_order.pop(0)
        self.status = "CAPTURING"
        if now-self._sample_times[0] >= CAPTURE_MS and len(self._samples) >= MIN_CAPTURE_FRAMES:
            success = self._finish_capture()
            self._clear_auto_window()
            self._auto_neutral_since = None
            self._auto_ready = False
            if success:
                if self.stage == "READY":
                    self._automatic = False
                    self.armed = False
                else:
                    self.status = "COUNTDOWN"

    def _projection(self, key, feature: Feature) -> float:
        return _dot(_subtract(feature, self._neutral[key]), self._directions[key])

    def _neutral_hand(self, hand: str, features: dict[tuple[str, str], Feature]) -> bool:
        return all((hand, name) in self._thresholds and (hand, name) in features and
                   self._projection((hand, name), features[hand, name]) <= self._thresholds[hand, name][1]
                   for name in FINGERS)

    def _typing(self, hand: CameraHand, features: dict[tuple[str, str], Feature], now: int):
        side = hand.handedness
        for name in FINGERS:
            key = side, name
            state = self._fingers[key]
            xy = hand.landmarks[TIP[name]][:2]
            down, up = self._thresholds[key]
            value = self._projection(key, features[key])
            if value <= up:
                if state.neutral_since is None:
                    state.neutral_since = now
            else:
                state.neutral_since = None
            rearmed = state.neutral_since is not None and now-state.neutral_since >= NEUTRAL_REARM_MS
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

    def _update_mouse(self, hand: CameraHand, features: dict[tuple[str, str], Feature], now: int) -> bool:
        wrist_x, wrist_y, _ = hand.landmarks[0]
        in_pad = wrist_x >= MOUSE_ROI[0] and MOUSE_ROI[1] <= wrist_y <= MOUSE_ROI[3]
        if not self._mouse_active:
            if in_pad:
                for name in FINGERS:
                    self._fingers["right", name] = _Finger()
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

    def _payload(self, hands: dict[str, CameraHand], features: dict[tuple[str, str], Feature]) -> dict:
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
            if self._automatic:
                self._clear_auto_window()
                self._auto_ready = False
                self._auto_neutral_since = None
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
