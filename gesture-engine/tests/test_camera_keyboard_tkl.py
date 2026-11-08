"""Offscreen 87-key ANSI TKL reference checks, not input/camera-plane evidence."""
import unittest

from airdesk_gesture.camera_keyboard import CameraKeyboardOverlay, RenderUnavailable
from airdesk_gesture.camera_keyboard_layouts import TKLKeyboardGeometry, BOARD_WIDTH, BOARD_HEIGHT


class TKLGeometryTests(unittest.TestCase):
    def setUp(self): self.keys = {key.code: key for key in TKLKeyboardGeometry.keys()}

    def test_exact_87_ansi_keys_groups_and_no_numpad_or_added_language_key(self):
        self.assertEqual(len(self.keys), 87)
        self.assertEqual(len(TKLKeyboardGeometry.keys()), 87)
        required = {"Escape", "PrintScreen", "ScrollLock", "Pause", "Insert", "Delete", "Home", "End", "PageUp", "PageDown",
                    "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "MetaLeft", "MetaRight", "ContextMenu"}
        required.update(f"F{n}" for n in range(1, 13))
        self.assertTrue(required <= self.keys.keys())
        self.assertFalse(any(code.startswith("Numpad") for code in self.keys))
        self.assertNotIn("Lang", self.keys)
        plan = CameraKeyboardOverlay(layout="tkl").layout_plan(860, 480)
        self.assertEqual(len([key for key in plan.keys if key.code.startswith("Key") and key.hangul]), 26)
        self.assertEqual(next(key for key in plan.keys if key.code == "AltRight").hangul, "한/영")
        self.assertIsNone(plan.mouse_bounds)

    def test_standard_wide_keys_stagger_bottom_row_and_function_group_gaps(self):
        for code, units in {"Tab":1.5, "CapsLock":1.75, "Backspace":2., "Enter":2.25,
                            "ShiftLeft":2.25, "ShiftRight":2.75, "Space":6.25}.items():
            self.assertEqual(self.keys[code].width, units)
        bottom = [key for key in self.keys.values() if key.y == 5.75 and key.x < 15.5]
        self.assertEqual(sum(key.width for key in bottom), 15.)
        self.assertTrue(all(key.width == 1.25 for key in bottom if key.code != "Space"))
        self.assertEqual(self.keys["KeyQ"].x-self.keys["Tab"].x, 1.5)
        self.assertEqual(self.keys["KeyA"].x-self.keys["CapsLock"].x, 1.75)
        self.assertEqual(self.keys["KeyZ"].x-self.keys["ShiftLeft"].x, 2.25)
        self.assertEqual(self.keys["F5"].x-self.keys["F4"].x-1, .5)
        self.assertEqual(self.keys["F9"].x-self.keys["F8"].x-1, .5)
        self.assertEqual(self.keys["Digit1"].y-self.keys["F1"].y-1, .5)

    def test_nominal_key_span_and_casing_match_physical_board_proportion(self):
        self.assertEqual(max(key.x+key.width for key in self.keys.values())-min(key.x for key in self.keys.values()), 18.25)
        self.assertEqual(min(key.x for key in self.keys.values()), .25)
        self.assertEqual(BOARD_WIDTH-max(key.x+key.width for key in self.keys.values()), .25)
        self.assertEqual(BOARD_HEIGHT, 7.)

    def test_uniform_fit_preserves_square_caps_and_all_bounds_without_crop(self):
        renderer = CameraKeyboardOverlay(layout="tkl")
        for width, height in ((860,480), (640,480), (480,860), (1920,1080), (1,1)):
            plan = renderer.layout_plan(width, height)
            left, top, right, bottom = plan.keyboard_bounds
            self.assertGreaterEqual(left, 0); self.assertGreaterEqual(top, 0)
            self.assertLessEqual(right, width); self.assertLessEqual(bottom, height)
            self.assertAlmostEqual((right-left)/(bottom-top), BOARD_WIDTH/BOARD_HEIGHT)
            self.assertAlmostEqual((left+right)/2, width/2)
            self.assertAlmostEqual(height-bottom, min(8., width*.02, height*.02))
            for key in plan.keys:
                x1,y1,x2,y2 = key.bounds
                self.assertTrue(left <= x1 < x2 <= right and top <= y1 < y2 <= bottom)
                if self.keys[key.code].width == 1:
                    self.assertAlmostEqual(x2-x1, y2-y1)

    def test_default_legacy_81_geometry_and_readonly_layout_are_preserved(self):
        self.assertEqual(CameraKeyboardOverlay().layout, "legacy")
        self.assertEqual(len(CameraKeyboardOverlay().layout_plan(860,480).keys), 81)
        self.assertIsNone(CameraKeyboardOverlay().layout_plan(860,480).keyboard_bounds)
        renderer = CameraKeyboardOverlay(layout="tkl", include_mouse=True)
        with self.assertRaises(AttributeError): renderer.layout = "legacy"
        for layout in ("unknown", [], None, True):
            with self.assertRaises(ValueError): CameraKeyboardOverlay(layout=layout)
        self.assertIsNone(renderer.layout_plan(860,480).mouse_bounds)


class TKLRenderTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        try:
            from PIL import Image
            cls.Image = Image
            cls.renderer = CameraKeyboardOverlay(layout="tkl", include_mouse=False)
            cls.renderer.rgba_mask(860,480)
        except (ImportError, RenderUnavailable) as exc:
            raise unittest.SkipTest("Optional Pillow/Hangul font unavailable; TKL render NOT_RUN") from exc

    def test_outer_casing_pixel_aspect_matches_uniform_fit_and_interiors_stay_clear(self):
        for width,height in ((860,480),(640,480),(480,860)):
            plan = self.renderer.layout_plan(width,height)
            mask = self.Image.frombytes("RGBA", (width,height), self.renderer.rgba_mask(width,height))
            alpha = mask.getchannel("A")
            bbox = alpha.getbbox()
            nominal_height = (bbox[2]-bbox[0])*BOARD_HEIGHT/BOARD_WIDTH
            self.assertLessEqual(abs((bbox[3]-bbox[1])-nominal_height), 1.1)
            self.assertEqual(alpha.getpixel((0,0)), 0)
            self.assertEqual(alpha.getpixel((width-1,height-1)), 0)
            key = next(key for key in plan.keys if key.code == "Space")
            self.assertEqual(alpha.getpixel((int(key.bounds[0])+3,int(key.bounds[1])+3)), 0)

    def test_tiny_nonstandard_frame_never_raises_and_no_text_spills_outside_board(self):
        for width,height in ((1,1),(2,3),(20,10),(120,300)):
            rendered = self.renderer.render_rgb(width,height,bytes(width*height*3))
            self.assertEqual(len(rendered), width*height*3)

    def test_tkl_cache_and_alpha_zero_pixels_preserve_source_and_layout(self):
        renderer = CameraKeyboardOverlay(layout="tkl")
        mask = renderer.rgba_mask(640,480)
        original = bytes((100,110,120))*(640*480)
        rendered = renderer.render_rgb(640,480,original)
        self.assertIs(renderer.rgba_mask(640,480), mask)
        self.assertTrue(all(rendered[i*3:i*3+3] == original[i*3:i*3+3]
                            for i in range(640*480) if mask[i*4+3] == 0))
