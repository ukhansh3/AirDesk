"""User-opened, read-only AIR keyboard estimate window. Never injects or stores input."""
from __future__ import annotations

from dataclasses import dataclass
from math import isfinite
import re
from time import monotonic
import tkinter as tk
from typing import Callable


WIDTH, HEIGHT = 1120., 360.
STALE_SECONDS = .5
FINGERS = ("thumb", "index", "middle", "ring", "little")
STAGES = ("NEUTRAL",) + tuple(f"{side}_{finger.upper()}" for side in ("LEFT", "RIGHT") for finger in FINGERS) + ("READY",)
STATUSES = {"WAITING", "COUNTDOWN", "CAPTURING", "FAILED", "READY", "ARMED"}
JAMO = dict(zip("QWERTYUIOPASDFGHJKLZXCVBNM", "ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔㅁㄴㅇㄹㅎㅗㅓㅏㅣㅋㅌㅊㅍㅠㅜㅡ"))


@dataclass(frozen=True)
class Rect:
    x: float
    y: float
    width: float
    height: float

    @property
    def right(self): return self.x + self.width
    @property
    def bottom(self): return self.y + self.height
    def contains(self, x, y): return self.x <= x < self.right and self.y <= y < self.bottom


@dataclass(frozen=True)
class Key:
    code: str
    label: str
    bounds: Rect


@dataclass(frozen=True)
class Fit:
    scale: float
    left: float
    top: float

    def point(self, x, y): return self.left + x * self.scale, self.top + y * self.scale
    def rect(self, bounds):
        x, y = self.point(bounds.x, bounds.y)
        return x, y, x + bounds.width * self.scale, y + bounds.height * self.scale


def _letters(value): return [("Key" + c, c, 1.) for c in value]


class KeyboardGeometry:
    """Exact Android DesktopKeyboardGeometry design, including its separate navigation cluster."""
    mouse = Rect(972., 44., 140., 308.)

    @staticmethod
    def keys():
        result = []
        def row(specs, y, unit=52., start=8.):
            x = start
            for code, label, units in specs:
                width = unit * units - 4.
                result.append(Key(code, label, Rect(x, y, width, 48.)))
                x += width + 4.
        row([("Escape", "Esc", 1.5)] + [(f"F{n}", f"F{n}", 1.) for n in range(1, 13)], 44.)
        row([("Backquote", "` ~", 1.)] + [(f"Digit{n}", f"{n} {'!@#$%^&*('[n-1]}", 1.) for n in range(1, 10)] +
            [("Digit0", "0 )", 1.), ("Minus", "- _", 1.), ("Equal", "= +", 1.), ("Backspace", "Backspace", 2.)], 96.)
        row([("Tab", "Tab", 1.5)] + _letters("QWERTYUIOP") +
            [("BracketLeft", "[ {", 1.), ("BracketRight", "] }", 1.), ("Backslash", "\\ |", 1.5)], 148.)
        row([("CapsLock", "Caps Lock", 1.75)] + _letters("ASDFGHJKL") +
            [("Semicolon", "; :", 1.), ("Quote", "' \"", 1.), ("Enter", "Enter", 2.25)], 200.)
        row([("ShiftLeft", "Shift", 2.25)] + _letters("ZXCVBNM") +
            [("Comma", ", <", 1.), ("Period", ". >", 1.), ("Slash", "/ ?", 1.), ("ShiftRight", "Shift", 2.75)], 252.)
        row([("ControlLeft", "Ctrl", 1.5), ("AltLeft", "Alt", 1.5), ("Lang", "한 / 영", 1.5),
             ("Space", "Space", 7.5), ("AltRight", "Alt", 1.5), ("ControlRight", "Ctrl", 1.5)], 304.)
        row([("Home", "Home", 1.), ("End", "End", 1.), ("Delete", "Del", 1.)], 96., 56., 796.)
        row([("PageUp", "PgUp", 1.5), ("PageDown", "PgDn", 1.5)], 148., 56., 796.)
        row([("ArrowUp", "↑", 1.)], 252., 56., 852.)
        row([("ArrowLeft", "←", 1.), ("ArrowDown", "↓", 1.), ("ArrowRight", "→", 1.)], 304., 56., 796.)
        return tuple(result)

    @staticmethod
    def fit(width, height, left=0., top=0.):
        if not all(isinstance(v, (int, float)) and not isinstance(v, bool) and isfinite(v) for v in (width, height, left, top)) or width <= 0 or height <= 0:
            raise ValueError("invalid preview bounds")
        scale = min(width / WIDTH, height / HEIGHT)
        return Fit(scale, left + (width - WIDTH * scale) / 2, top + (height - HEIGHT * scale) / 2)

    @staticmethod
    def hit(x, y):
        if not _coordinate(x) or not _coordinate(y): return None
        return next((key.code for key in KEYS if key.bounds.contains(x * WIDTH, y * HEIGHT)), None)


KEYS = KeyboardGeometry.keys()


def _coordinate(value):
    return type(value) in (int, float) and isfinite(value) and 0 <= value <= 1


@dataclass(frozen=True)
class Finger:
    hand: str
    finger: str
    x: float
    y: float
    pressed: bool


@dataclass(frozen=True)
class KeyboardPreviewState:
    fingers: tuple[Finger, ...] = ()
    pressed_keys: frozenset[str] = frozenset()
    stage: str | None = None
    status: str | None = None
    armed: bool = False
    mouse: tuple[float, float, bool] | None = None

    @classmethod
    def from_payload(cls, payload):
        try:
            if not isinstance(payload, dict) or set(payload) != {"epoch", "stage", "status", "armed", "fingers", "mouse"}: return cls()
            if not isinstance(payload["epoch"], str) or not re.fullmatch(r"[A-Za-z0-9._-]{1,128}", payload["epoch"]): return cls()
            stage, status, armed = payload["stage"], payload["status"], payload["armed"]
            if stage not in STAGES or status not in STATUSES or type(armed) is not bool: return cls()
            if armed != (stage == "READY" and status == "ARMED"): return cls()
            values = payload["fingers"]
            if type(values) is not list or len(values) > 10: return cls()
            seen, fingers = set(), []
            for value in values:
                if not isinstance(value, dict) or set(value) != {"hand", "finger", "x", "y", "pressed", "valid"}: return cls()
                identity = value["hand"], value["finger"]
                if identity[0] not in ("left", "right") or identity[1] not in FINGERS or identity in seen: return cls()
                seen.add(identity)
                if not _coordinate(value["x"]) or not _coordinate(value["y"]): return cls()
                if type(value["pressed"]) is not bool or type(value["valid"]) is not bool: return cls()
                if value["pressed"] and (not value["valid"] or not armed): return cls()
                if value["valid"]: fingers.append(Finger(*identity, value["x"], value["y"], value["pressed"]))
            mouse = payload["mouse"]
            if not isinstance(mouse, dict) or set(mouse) != {"active", "valid", "x", "y", "pressed", "dragging"}: return cls()
            if any(type(mouse[k]) is not bool for k in ("active", "valid", "pressed", "dragging")): return cls()
            if not _coordinate(mouse["x"]) or not _coordinate(mouse["y"]): return cls()
            if mouse["pressed"] and (not armed or not mouse["active"] or not mouse["valid"]): return cls()
            if mouse["dragging"] and not mouse["pressed"]: return cls()
            if mouse["active"] and any(f.hand == "right" for f in fingers): return cls()
            keys = frozenset(code for f in fingers if armed and f.pressed if (code := KeyboardGeometry.hit(f.x, f.y)))
            point = (mouse["x"], mouse["y"], mouse["pressed"]) if armed and mouse["active"] and mouse["valid"] else None
            return cls(tuple(fingers), keys, stage, status, armed, point)
        except (TypeError, ValueError, KeyError):
            return cls()


class KeyboardPreviewModel:
    def __init__(self, clock: Callable[[], float] = monotonic):
        self.clock = clock
        self.state = KeyboardPreviewState()
        self.updated_at = None

    def update(self, payload):
        self.state = KeyboardPreviewState.from_payload(payload)
        self.updated_at = self.clock() if self.state.stage is not None else None

    def expire(self):
        if self.updated_at is not None and not 0 <= self.clock() - self.updated_at <= STALE_SECONDS:
            self.state = KeyboardPreviewState(); self.updated_at = None
            return True
        return False


def calibration_text(state):
    if state.stage is None: return "AIR 데이터 대기 · Android 앱의 양손 열손가락 모드에서 연결하세요"
    if state.stage == "READY":
        return "보정 완료 · 누름 추정 표시 중" if state.armed else "보정 완료 · Android 앱에서 메모 입력 → 타건 켜기"
    index = STAGES.index(state.stage) + 1
    if state.stage == "NEUTRAL": pose = "양손 기본 자세"
    else:
        side, finger = state.stage.lower().split("_", 1)
        pose = ("왼손" if side == "left" else "오른손") + " " + dict(zip(FINGERS, ("엄지", "검지", "중지", "약지", "새끼손가락")))[finger]
    detail = {"COUNTDOWN": "기본 자세로 준비", "CAPTURING": "자세를 유지하세요 · 자동 확인 중",
              "FAILED": "기본 자세로 돌아와 다시 시도", "WAITING": "이 손가락만 키를 누르듯 아래로 움직이세요" if state.stage != "NEUTRAL"
              else "앱에서 자동 보정 시작 · 안내에 따라 양손 기본 자세 유지"}.get(state.status, "앱의 안내를 확인하세요")
    if len(state.fingers) < 10 and state.status != "COUNTDOWN": detail = f"손끝 {len(state.fingers)}/10 · 양손과 열 손가락이 모두 보여야 합니다"
    return f"보정 {index}/11 · {pose} · {detail}"


class KeyboardPreviewWindow:
    """Create only from an explicit user button. No root, camera, mainloop or input binding is created."""
    def __init__(self, parent, on_close=None):
        self._on_close = on_close
        self.model = KeyboardPreviewModel()
        self._window = tk.Toplevel(parent)
        self._window.title("AirDesk · 큰 키보드 · 추정 상태 보기")
        self._window.geometry("1200x500")
        self._window.minsize(600, 260)
        self._canvas = tk.Canvas(self._window, bg="#081628", highlightthickness=0)
        self._canvas.pack(fill="both", expand=True)
        self._canvas.bind("<Configure>", lambda _: self._draw())
        self._window.protocol("WM_DELETE_WINDOW", self.close)
        self._timer = self._window.after(100, self._tick)

    @property
    def is_open(self):
        try: return self._window is not None and bool(self._window.winfo_exists())
        except tk.TclError: return False

    def show(self):
        if self.is_open: self._window.lift()

    def update(self, payload):
        if not self.is_open:
            self.model.update(None)
            return
        self.model.update(payload)
        self._draw()

    def close(self):
        if self._window is None: return
        window, self._window = self._window, None
        timer = getattr(self, "_timer", None)
        if timer is not None:
            try: window.after_cancel(timer)
            except tk.TclError: pass
        self.model.update(None)
        window.destroy()
        if self._on_close: self._on_close()

    def _tick(self):
        if not self.is_open: return
        if self.model.expire(): self._draw()
        self._timer = self._window.after(100, self._tick)

    def _draw(self):
        canvas = self._canvas
        width, height = canvas.winfo_width(), canvas.winfo_height()
        if width < 25 or height < 110: return
        fit = KeyboardGeometry.fit(width - 24, height - 100, 12, 88)
        state = self.model.state
        canvas.delete("all")
        headline = "DEMO · 누름 추정 / 실제 입력 상태는 Android 앱에서 확인"
        headline_size = max(10, min(16, int((width - 24) / len(headline))))
        canvas.create_text(12, 12, anchor="nw", text=headline,
                           fill="#ffcb7d", font=("맑은 고딕", -headline_size, "bold"))
        canvas.create_text(12, 39, anchor="nw", text=calibration_text(state), fill="#c7bcff", font=("맑은 고딕", -14))
        canvas.create_text(12, 62, anchor="nw", text=f"손끝 {len(state.fingers)}/10 · 추정 키 {len(state.pressed_keys)} · 왼손 파랑 / 오른손 분홍 · 이 창은 보기 전용",
                           fill="#aec6df", font=("맑은 고딕", -12))
        x, y = fit.point(8, 8)
        canvas.create_text(x, y, anchor="nw", text="PC QWERTY · 한/영 입력 모드는 Android 앱에서 확인 · F2–F12 앱 미지원",
                           fill="#c7bcff", font=("맑은 고딕", -max(8, round(13 * fit.scale))))
        for key in KEYS:
            pressed = key.code in state.pressed_keys
            disabled = bool(re.fullmatch(r"F(?:[2-9]|1[0-2])", key.code))
            canvas.create_rectangle(*fit.rect(key.bounds), fill="#986e35" if pressed else "#1b2739" if disabled else "#243956",
                                    outline="#ffcb7d" if pressed else "#415977", width=2 if pressed else 1)
            x, y = fit.point(key.bounds.x + key.bounds.width / 2, key.bounds.y + key.bounds.height / 2)
            label = key.label + ("\n" + JAMO[key.code[-1]] if key.code.startswith("Key") else "")
            canvas.create_text(x, y, text=label, fill="#75859d" if disabled else "#e3ebf9",
                               font=("맑은 고딕", -max(7, round((11 if len(key.label) > 6 else 13) * fit.scale))))
        canvas.create_rectangle(*fit.rect(KeyboardGeometry.mouse), fill="#345041" if state.mouse and state.mouse[2] else "#1b2f49", outline="#637796")
        x, y = fit.point(1042, 60)
        canvas.create_text(x, y, anchor="n", text="마우스 영역\nPinch 누름 추정", fill="#c7bcff", font=("맑은 고딕", -max(8, round(13 * fit.scale))))
        for finger in state.fingers:
            x, y = fit.point(finger.x * WIDTH, finger.y * HEIGHT)
            radius = max(4, 7 * fit.scale)
            color = "#76c9ff" if finger.hand == "left" else "#ff99c7"
            canvas.create_oval(x-radius, y-radius, x+radius, y+radius, fill=color,
                               outline="#ffca7b" if finger.pressed else "#081628", width=3 if finger.pressed else 1)
            label = ("L" if finger.hand == "left" else "R") + str(FINGERS.index(finger.finger) + 1)
            canvas.create_text(x, max(90, y-radius-9), text=label, fill=color, font=("Segoe UI", -11, "bold"))
        if state.mouse:
            mx, my, _ = state.mouse
            x, y = fit.point(KeyboardGeometry.mouse.x + mx * KeyboardGeometry.mouse.width,
                             KeyboardGeometry.mouse.y + my * KeyboardGeometry.mouse.height)
            canvas.create_oval(x-6, y-6, x+6, y+6, fill="#9fe3c1", outline="#ffcb7d")
