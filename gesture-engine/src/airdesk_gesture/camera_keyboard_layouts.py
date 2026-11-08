"""Pure ANSI TKL reference geometry; no input actions, camera, fonts or GUI.

All dimensions are keyboard units. Key pitch is 1u, with 0.08u visual cap gaps.
The nominal key span is 18.25u; 0.25u casing on each side makes an 18.75x7u board.
This physical-proportion reference is NOT the legacy v0.2 image-coordinate map.
"""
from __future__ import annotations

from dataclasses import dataclass
from math import isfinite

BOARD_WIDTH = 18.75
BOARD_HEIGHT = 7.0
CAP_GAP = .08


@dataclass(frozen=True)
class TKLKey:
    code: str
    label: str
    x: float
    y: float
    width: float = 1.0
    height: float = 1.0


@dataclass(frozen=True)
class TKLFit:
    scale: float
    left: float
    top: float

    @property
    def keyboard_bounds(self):
        return self.left, self.top, self.left+BOARD_WIDTH*self.scale, self.top+BOARD_HEIGHT*self.scale

    def key_bounds(self, key: TKLKey):
        inset = CAP_GAP/2
        return (self.left+(key.x+inset)*self.scale, self.top+(key.y+inset)*self.scale,
                self.left+(key.x+key.width-inset)*self.scale, self.top+(key.y+key.height-inset)*self.scale)


class TKLKeyboardGeometry:
    @staticmethod
    def keys() -> tuple[TKLKey, ...]:
        result = []
        def row(specs, y, start=.25):
            x = start
            for code, label, width in specs:
                result.append(TKLKey(code, label, x, y, width))
                x += width
        def letters(value): return [("Key"+char, char, 1.) for char in value]
        row([("Escape", "Esc", 1.)], .25)
        for first, start in ((1, 2.25), (5, 6.75), (9, 11.25)):
            row([(f"F{n}", f"F{n}", 1.) for n in range(first, first+4)], .25, start)
        row([("PrintScreen", "PrtSc", 1.), ("ScrollLock", "ScrLk", 1.), ("Pause", "Pause", 1.)], .25, 15.5)
        row([("Backquote", "` ~", 1.)] + [(f"Digit{n}", f"{n} {'!@#$%^&*('[n-1]}", 1.) for n in range(1, 10)] +
            [("Digit0", "0 )", 1.), ("Minus", "- _", 1.), ("Equal", "= +", 1.), ("Backspace", "Backspace", 2.)], 1.75)
        row([("Tab", "Tab", 1.5)] + letters("QWERTYUIOP") +
            [("BracketLeft", "[ {", 1.), ("BracketRight", "] }", 1.), ("Backslash", "\\ |", 1.5)], 2.75)
        row([("CapsLock", "Caps Lock", 1.75)] + letters("ASDFGHJKL") +
            [("Semicolon", "; :", 1.), ("Quote", "' \"", 1.), ("Enter", "Enter", 2.25)], 3.75)
        row([("ShiftLeft", "Shift", 2.25)] + letters("ZXCVBNM") +
            [("Comma", ", <", 1.), ("Period", ". >", 1.), ("Slash", "/ ?", 1.), ("ShiftRight", "Shift", 2.75)], 4.75)
        row([("ControlLeft", "Ctrl", 1.25), ("MetaLeft", "Win", 1.25), ("AltLeft", "Alt", 1.25),
             ("Space", "Space", 6.25), ("AltRight", "Alt", 1.25), ("MetaRight", "Win", 1.25),
             ("ContextMenu", "Menu", 1.25), ("ControlRight", "Ctrl", 1.25)], 5.75)
        row([("Insert", "Ins", 1.), ("Home", "Home", 1.), ("PageUp", "PgUp", 1.)], 1.75, 15.5)
        row([("Delete", "Del", 1.), ("End", "End", 1.), ("PageDown", "PgDn", 1.)], 2.75, 15.5)
        row([("ArrowUp", "↑", 1.)], 4.75, 16.5)
        row([("ArrowLeft", "←", 1.), ("ArrowDown", "↓", 1.), ("ArrowRight", "→", 1.)], 5.75, 15.5)
        return tuple(result)

    @staticmethod
    def fit(width: int, height: int) -> TKLFit:
        if type(width) not in (int, float) or type(height) not in (int, float) or not isfinite(width) or not isfinite(height) or width <= 0 or height <= 0:
            raise ValueError("invalid TKL reference dimensions")
        margin = min(8., width*.02, height*.02)
        scale = min((width-2*margin)/BOARD_WIDTH, (height-2*margin)/BOARD_HEIGHT)
        return TKLFit(scale, (width-BOARD_WIDTH*scale)/2, height-margin-BOARD_HEIGHT*scale)
