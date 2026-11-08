"""Transparent green PC-keyboard reference composited on a local RGB preview.

This is a 2D visual reference for the existing v0.2 image-coordinate experiment,
not a physical plane, projected keyboard, key detector or input authority. Key
interiors remain transparent except for labels. No camera/window/image recording
is performed here. One RGBA reference mask is cached; RGB camera frames are not.
"""
from __future__ import annotations

from dataclasses import dataclass
from math import ceil, floor, isfinite
import os
from pathlib import Path

from .keyboard_preview import KeyboardGeometry, JAMO, WIDTH, HEIGHT
from .camera_keyboard_layouts import TKLKeyboardGeometry


class RenderUnavailable(RuntimeError):
    """Pillow or a verified Hangul-capable font is unavailable."""


@dataclass(frozen=True)
class KeyOutline:
    code: str
    english: str
    hangul: str | None
    bounds: tuple[float, float, float, float]


@dataclass(frozen=True)
class CameraKeyboardPlan:
    width: int
    height: int
    keys: tuple[KeyOutline, ...]
    mouse_bounds: tuple[float, float, float, float] | None
    keyboard_bounds: tuple[float, float, float, float] | None = None
    layout: str = "legacy"


def _dimensions(width: int, height: int):
    if type(width) is not int or type(height) is not int or not 1 <= width <= 8192 or not 1 <= height <= 8192 or width*height > 16_777_216:
        raise ValueError("invalid camera keyboard dimensions")


def _opacity(value: float) -> float:
    if type(value) not in (float, int) or not isfinite(value) or not 0 <= value <= 1:
        raise ValueError("opacity must be finite and within 0..1")
    return float(value)


def _pillow():
    try:
        from PIL import Image, ImageDraw, ImageFont
    except ImportError as exc:
        raise RenderUnavailable("Pillow is required for the local camera keyboard overlay") from exc
    return Image, ImageDraw, ImageFont


class CameraKeyboardOverlay:
    """Static full-keyboard overlay; source normalized x/y are not remapped.

    Default legacy mode independently scales X/Y to the RGB frame, preserving
    the existing normalized v0.2 coordinates. Optional tkl mode uniformly fits a
    physical-proportion 87-key reference at the bottom center; it does NOT align
    to legacy AIR input coordinates. Neither mode estimates a physical plane.
    """
    GREEN = (40, 255, 96)

    def __init__(self, font_path: str | Path | None = None, *, include_mouse: bool = True, layout: str = "legacy"):
        if type(layout) is not str or layout not in {"legacy", "tkl"}:
            raise ValueError("camera keyboard layout must be legacy or tkl")
        self._layout = layout
        self._font_path = Path(font_path) if font_path is not None else None
        self._include_mouse = bool(include_mouse)
        self._cache_key: tuple[int, int, float] | None = None
        self._cache_rgba: bytes | None = None

    @property
    def layout(self) -> str:
        return self._layout

    @property
    def cached_key(self) -> tuple[int, int, float] | None:
        return self._cache_key

    def layout_plan(self, width: int, height: int) -> CameraKeyboardPlan:
        _dimensions(width, height)
        if self._layout == "tkl":
            fit = TKLKeyboardGeometry.fit(width, height)
            keys = tuple(KeyOutline(key.code, key.label,
                JAMO[key.code[-1]] if key.code.startswith("Key") else "한/영" if key.code == "AltRight" else None,
                fit.key_bounds(key)) for key in TKLKeyboardGeometry.keys())
            # include_mouse applies only to legacy. TKL has no large mouse pad.
            return CameraKeyboardPlan(width, height, keys, None, fit.keyboard_bounds, "tkl")
        sx, sy = width/WIDTH, height/HEIGHT
        def bounds(rect):
            return rect.x*sx, rect.y*sy, rect.right*sx, rect.bottom*sy
        keys = tuple(KeyOutline(key.code, "Han/Eng" if key.code == "Lang" else key.label,
            JAMO[key.code[-1]] if key.code.startswith("Key") else "한/영" if key.code == "Lang" else None,
            bounds(key.bounds)) for key in KeyboardGeometry.keys())
        return CameraKeyboardPlan(width, height, keys, bounds(KeyboardGeometry.mouse) if self._include_mouse else None)

    def _font(self, ImageFont):
        candidates = [self._font_path] if self._font_path is not None else [
            Path(os.environ.get("WINDIR", "C:/Windows"))/"Fonts"/"malgun.ttf",
            Path("C:/Windows/Fonts/malgun.ttf"),
            Path("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
            Path("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc"),
        ]
        for path in candidates:
            if not path.is_file():
                continue
            try:
                font = ImageFont.truetype(str(path), 20)
                # FreeType renders unsupported code points as one notdef glyph.
                # Reject a font that silently supplies that box for required labels.
                missing = font.getmask("\U0010ffff")
                missing_shape = missing.size, bytes(missing)
                required = set(JAMO.values()) | set("한영마우스↑↓←→")
                for char in required:
                    mask = font.getmask(char)
                    if not any(mask) or (mask.size, bytes(mask)) == missing_shape:
                        raise ValueError("required Hangul/arrow glyph missing")
                return str(path)
            except (OSError, ValueError):
                continue
        raise RenderUnavailable("A Hangul-capable Malgun Gothic or Noto Sans CJK font is required; no missing-glyph fallback is used")

    def _build_mask(self, width: int, height: int, opacity: float) -> bytes:
        if opacity == 0:
            return bytes(width*height*4)
        Image, ImageDraw, ImageFont = _pillow()
        plan = self.layout_plan(width, height)
        path = self._font(ImageFont)
        canvas = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        draw = ImageDraw.Draw(canvas)
        color = (*self.GREEN, round(opacity*255))
        stroke = max(1, round(min(width/WIDTH, height/HEIGHT)))
        fonts = {}  # temporary, bounded by the current layout's label sizes

        def font_at(size):
            if size not in fonts:
                fonts[size] = ImageFont.truetype(path, size)
            return fonts[size]

        def labels(rect, english, hangul=None):
            left, top, right, bottom = rect
            if min(right-left, bottom-top) < 10:
                return  # tiny previews retain outlines without unreadable text overflow
            available = max(1, right-left-6)
            letter_scale = .31 if self._layout == "tkl" else .27
            size = max(5, min(24, round((bottom-top)*(letter_scale if hangul else .4))))
            texts = (english, hangul) if hangul else (english,)
            while size > 5 and any(draw.textbbox((0, 0), text, font=font_at(size))[2] > available for text in texts):
                size -= 1
            font = font_at(size)
            boxes = [draw.textbbox((0, 0), text, font=font) for text in texts]
            spacing = max(2, round(size*.14)) if hangul else 0
            total_height = sum(b[3]-b[1] for b in boxes) + spacing*(len(texts)-1)
            y = (top+bottom-total_height)/2
            for text, box in zip(texts, boxes):
                text_width, text_height = box[2]-box[0], box[3]-box[1]
                draw.text(((left+right-text_width)/2-box[0], y-box[1]), text, font=font, fill=color)
                y += text_height+spacing

        def outline(rect):
            left, top, right, bottom = rect
            raster = max(0, floor(left)), max(0, floor(top)), min(width-1, ceil(right)-1), min(height-1, ceil(bottom)-1)
            if raster[2] >= raster[0] and raster[3] >= raster[1]:
                draw.rectangle(raster, outline=color, width=stroke)  # no fill

        if plan.keyboard_bounds is not None:
            outline(plan.keyboard_bounds)  # transparent outer casing, no input surface
        for key in plan.keys:
            outline(key.bounds)
            labels(key.bounds, key.english, key.hangul)
        if plan.mouse_bounds is not None:
            outline(plan.mouse_bounds)
            left, top, right, _ = plan.mouse_bounds
            labels((left, top+8, right, top+80*height/HEIGHT), "MOUSE", "마우스")
        return canvas.tobytes()

    def rgba_mask(self, width: int, height: int, opacity: float = .4) -> bytes:
        """Immutable reference mask for offscreen verification; contains no video."""
        _dimensions(width, height)
        opacity = _opacity(opacity)
        key = width, height, opacity
        if self._cache_key != key:
            mask = self._build_mask(width, height, opacity)
            self._cache_rgba, self._cache_key = mask, key
        return self._cache_rgba

    def render_rgb(self, width: int, height: int, rgb_bytes: bytes, opacity: float = .4) -> bytes:
        _dimensions(width, height)
        opacity = _opacity(opacity)
        if not isinstance(rgb_bytes, bytes) or len(rgb_bytes) != width*height*3:
            raise ValueError("RGB data must contain exactly width*height*3 bytes")
        if opacity == 0:
            return rgb_bytes
        Image, _, _ = _pillow()
        mask = self.rgba_mask(width, height, opacity)
        original = Image.frombytes("RGB", (width, height), rgb_bytes)
        overlay = Image.frombytes("RGBA", (width, height), mask)
        return Image.alpha_composite(original.convert("RGBA"), overlay).convert("RGB").tobytes()
