"""Headless geometry and real Pillow/font tests. No webcam, Tk or GUI is started."""
from dataclasses import FrozenInstanceError
import unittest
from unittest.mock import patch

from airdesk_gesture.camera_keyboard import CameraKeyboardOverlay, RenderUnavailable
from airdesk_gesture.keyboard_preview import KeyboardGeometry, JAMO


class CameraKeyboardGeometryTests(unittest.TestCase):
    def test_all_81_keys_and_26_letter_jamo_preserve_exact_normalized_geometry(self):
        renderer = CameraKeyboardOverlay()
        plan = renderer.layout_plan(860, 480)
        self.assertEqual(len(plan.keys), 81)
        self.assertEqual(len({key.code for key in plan.keys}), 81)
        self.assertTrue(all(key.english for key in plan.keys))
        letters = [key for key in plan.keys if key.code.startswith("Key")]
        self.assertEqual(len(letters), 26)
        self.assertEqual({key.hangul for key in letters}, set(JAMO.values()))
        for source, placed in zip(KeyboardGeometry.keys(), plan.keys):
            self.assertAlmostEqual(placed.bounds[0]/860, source.bounds.x/1120)
            self.assertAlmostEqual(placed.bounds[1]/480, source.bounds.y/360)
        with self.assertRaises(FrozenInstanceError): plan.width = 123

    def test_resize_uses_full_independent_axes_and_mouse_is_optional(self):
        renderer = CameraKeyboardOverlay(include_mouse=False)
        self.assertIsNone(renderer.layout_plan(640, 480).mouse_bounds)
        a, b = renderer.layout_plan(1120, 360), renderer.layout_plan(560, 720)
        self.assertEqual(b.keys[0].bounds[0], a.keys[0].bounds[0]/2)
        self.assertEqual(b.keys[0].bounds[1], a.keys[0].bounds[1]*2)

    def test_invalid_sizes_opacity_and_rgb_are_rejected_without_drawing(self):
        renderer = CameraKeyboardOverlay()
        for width, height in ((0, 1), (True, 1), (10, -1), (8192, 8192)):
            with self.assertRaises(ValueError): renderer.layout_plan(width, height)
        for opacity in (-.1, 1.1, float("nan"), True):
            with self.assertRaises(ValueError): renderer.render_rgb(2, 2, bytes(12), opacity)
        with self.assertRaises(ValueError): renderer.render_rgb(2, 2, bytes(11))

    def test_zero_opacity_is_exact_noop_without_pillow_or_fonts(self):
        renderer = CameraKeyboardOverlay(font_path="synthetic-missing-font.ttf")
        original = bytes(range(12))
        with patch("airdesk_gesture.camera_keyboard._pillow", side_effect=RenderUnavailable("not installed")):
            self.assertIs(renderer.render_rgb(2, 2, original, opacity=0), original)
            with self.assertRaises(RenderUnavailable): renderer.render_rgb(2, 2, original)


class CameraKeyboardPillowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        try:
            from PIL import Image
            cls.Image = Image
            cls.renderer = CameraKeyboardOverlay()
            cls.renderer.rgba_mask(860, 480)
        except (ImportError, RenderUnavailable) as exc:
            raise unittest.SkipTest("Optional Pillow/Hangul font unavailable; real offscreen render NOT_RUN") from exc

    def test_key_interiors_and_corners_remain_transparent_except_outline_and_text(self):
        mask = self.Image.frombytes("RGBA", (860, 480), self.renderer.rgba_mask(860, 480))
        alpha = mask.getchannel("A")
        self.assertEqual(alpha.getpixel((0, 0)), 0)
        self.assertEqual(alpha.getpixel((859, 479)), 0)
        for key in self.renderer.layout_plan(860, 480).keys:
            left, top, right, bottom = map(int, key.bounds)
            self.assertEqual(alpha.getpixel((left+3, top+3)), 0, key.code)
            crop = alpha.crop((left+2, top+2, right-2, bottom-2))
            self.assertLess(sum(value > 0 for value in crop.tobytes()), crop.width*crop.height*.45, key.code)

    def test_alpha_zero_pixels_keep_original_rgb_exact_and_output_is_green_only(self):
        width, height = 860, 480
        original = bytes((50, 60, 70)) * (width*height)
        result = self.renderer.render_rgb(width, height, original)
        mask = self.renderer.rgba_mask(width, height)
        clear = [index for index in range(width*height) if mask[index*4+3] == 0]
        self.assertTrue(clear)
        self.assertTrue(all(result[index*3:index*3+3] == original[index*3:index*3+3] for index in clear))
        self.assertEqual(original, bytes((50, 60, 70)) * (width*height))
        self.assertTrue(any(result[index*3+1] > original[index*3+1] for index in range(width*height)))

    def test_cache_contains_only_one_reference_mask_and_rebuilds_only_on_size_or_opacity(self):
        renderer = CameraKeyboardOverlay()
        with patch.object(renderer, "_build_mask", wraps=renderer._build_mask) as build:
            first = renderer.rgba_mask(860, 480, .4)
            for _ in range(3): self.assertIs(renderer.rgba_mask(860, 480, .4), first)
            renderer.render_rgb(860, 480, bytes(860*480*3))
            renderer.render_rgb(860, 480, bytes([220])*(860*480*3))
            self.assertEqual(build.call_count, 1)
            renderer.rgba_mask(860, 480, .7)
            self.assertEqual(build.call_count, 2)
            renderer.rgba_mask(640, 480, .7)
            self.assertEqual(build.call_count, 3)
        self.assertEqual(renderer.cached_key, (640, 480, .7))
        self.assertEqual(len(renderer._cache_rgba), 640*480*4)

    def test_missing_or_unsupported_font_has_explicit_failure_not_tofu_fallback(self):
        renderer = CameraKeyboardOverlay(font_path="synthetic-missing-font.ttf")
        with self.assertRaises(RenderUnavailable): renderer.rgba_mask(860, 480)
        with patch("PIL.ImageFont.truetype", side_effect=OSError("synthetic bad font")):
            with self.assertRaises(RenderUnavailable): CameraKeyboardOverlay().rgba_mask(860, 480)
        from PIL import ImageFont
        missing = ImageFont.truetype(self.renderer._font(ImageFont), 20).getmask("\U0010ffff")
        class MissingGlyphFont:
            def getmask(self, _): return missing
        with patch("PIL.ImageFont.truetype", return_value=MissingGlyphFont()):
            with self.assertRaises(RenderUnavailable): CameraKeyboardOverlay().rgba_mask(860, 480)

    def test_render_never_constructs_tk_camera_or_window(self):
        with patch("tkinter.Tk", side_effect=AssertionError("GUI prohibited")), patch("tkinter.Toplevel", side_effect=AssertionError("GUI prohibited")):
            rendered = self.renderer.render_rgb(860, 480, bytes(860*480*3))
        self.assertEqual(len(rendered), 860*480*3)
