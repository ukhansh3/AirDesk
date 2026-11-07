package com.pleos.airdesk

import org.junit.Assert.*
import org.junit.Test

class DesktopWorkspaceLayoutTest {
    @Test fun screenshotSizedPortraitRetainsAnEditableBodyAndKeyboard() {
        val plan = DesktopWorkspaceLayout.calculate(340f, 700f, 154f, keyboardVisible = true)
        assertTrue(plan.compactControls)
        assertFalse(plan.showSecondaryEditor)
        assertFalse(plan.showExamples)
        assertTrue(plan.editorAreaHeightDp - 20f - 44f - 8f >= 64f)
        assertTrue(plan.keyboardHeightDp > 0f)
        assertTrue(plan.keyboardHeightDp <= 340f * DesktopKeyboardGeometry.HEIGHT / DesktopKeyboardGeometry.WIDTH)
        assertTrue(plan.editorAreaHeightDp + plan.keyboardHeightDp <= 700f - 154f)
        assertFalse(plan.needsWorkspaceScroll)
    }

    @Test fun shortLandscapeScalesCompleteKeyboardRatherThanHidingInput() {
        val plan = DesktopWorkspaceLayout.calculate(700f, 300f, 100f, keyboardVisible = true)
        assertFalse(plan.needsWorkspaceScroll)
        assertTrue(plan.editorAreaHeightDp >= plan.editorMinHeightDp)
        assertTrue(plan.editorMinHeightDp >= 136f)
        assertTrue(plan.keyboardHeightDp in 48f..64f)
        assertTrue(plan.editorAreaHeightDp + plan.keyboardHeightDp <= 200f)
        assertFalse(plan.showSecondaryEditor)
        assertFalse(plan.showExamples)
    }

    @Test fun widePleosWorkspaceCanExposeEditorsAndMouseExamples() {
        val plan = DesktopWorkspaceLayout.calculate(1100f, 900f, 150f, keyboardVisible = true)
        assertFalse(plan.compactControls)
        assertTrue(plan.showSecondaryEditor)
        assertTrue(plan.showExamples)
        assertTrue(plan.editorAreaHeightDp >= plan.editorMinHeightDp * 2f + 7f)
        assertTrue(plan.keyboardHeightDp in 48f..390f)
        assertFalse(plan.needsWorkspaceScroll)
    }

    @Test fun hidingKeyboardReturnsItsSpaceToEditors() {
        val showing = DesktopWorkspaceLayout.calculate(340f, 700f, 154f, keyboardVisible = true)
        val hidden = DesktopWorkspaceLayout.calculate(340f, 700f, 154f, keyboardVisible = false)
        assertEquals(0f, hidden.keyboardHeightDp, 0f)
        assertTrue(hidden.editorAreaHeightDp > showing.editorAreaHeightDp)
        assertEquals(700f - 154f, hidden.editorAreaHeightDp, .001f)
        assertFalse(hidden.showSecondaryEditor)
    }

    @Test fun fontScalingReservesReadableBodyBeforeKeyboardAllocation() {
        val normal = DesktopWorkspaceLayout.calculate(840f, 600f, 150f, true, fontScale = 1f)
        val larger = DesktopWorkspaceLayout.calculate(840f, 600f, 150f, true, fontScale = 1.5f)
        assertTrue(larger.editorMinHeightDp > normal.editorMinHeightDp)
        assertTrue(larger.editorMinHeightDp - 20f - 44f - 8f >= 64f * 1.5f)
        assertFalse(larger.showSecondaryEditor)
        assertTrue(larger.editorAreaHeightDp >= larger.editorMinHeightDp)
        assertTrue(larger.editorAreaHeightDp + larger.keyboardHeightDp <= 450f)
    }

    @Test fun expandedChromeCannotStealTheLastEditableBody() {
        val plan = DesktopWorkspaceLayout.calculate(340f, 420f, 350f, keyboardVisible = true)
        assertTrue(plan.needsWorkspaceScroll)
        assertTrue(plan.editorAreaHeightDp >= plan.editorMinHeightDp)
        assertTrue(plan.editorMinHeightDp >= 136f)
        val noKeyboard = DesktopWorkspaceLayout.calculate(340f, 420f, 350f, keyboardVisible = false)
        assertTrue(noKeyboard.needsWorkspaceScroll)
        assertEquals(0f, noKeyboard.keyboardHeightDp, 0f)
    }

    @Test fun largeAccessibilityTextIncludesButtonPaddingBeforeReservingEditorBody() {
        val plan = DesktopWorkspaceLayout.calculate(840f, 900f, 200f, true, fontScale = 3f)
        val actualHeader = 18f * 3f + 16f
        assertEquals(70f, actualHeader, .001f)
        assertTrue(plan.editorMinHeightDp - 20f - actualHeader - 8f >= 64f * 3f)
        assertFalse(plan.needsWorkspaceScroll)
    }

    @Test fun invalidMeasurementDoesNotProduceNaNOrNegativeBudgets() {
        val invalid = listOf<() -> Unit>(
            { DesktopWorkspaceLayout.calculate(Float.NaN, 700f, 100f, true) },
            { DesktopWorkspaceLayout.calculate(340f, Float.POSITIVE_INFINITY, 100f, true) },
            { DesktopWorkspaceLayout.calculate(340f, 700f, -1f, true) },
            { DesktopWorkspaceLayout.calculate(340f, 700f, 100f, true, fontScale = 0f) },
        )
        for (calculate in invalid) {
            try { calculate(); fail("Invalid viewport accepted") }
            catch (_: IllegalArgumentException) { /* Expected boundary rejection. */ }
        }
    }

    @Test fun completeDesktopGroupsAndMouseFitOneAllocatedSurfaceAtEverySize() {
        val viewports = listOf(340f to 110f, 700f to 64f, 1100f to 350f, 1682f to 390f, 320f to 48f)
        for ((width, height) in viewports) {
            val fit = DesktopKeyboardGeometry.fit(width, height)
            val bounds = DesktopKeyboardGeometry.keys.map { it.bounds.fitted(fit) } + DesktopKeyboardGeometry.mouseBounds.fitted(fit)
            assertTrue(fit.scale > 0f)
            for (rectangle in bounds) {
                assertTrue(rectangle.left >= -.001f && rectangle.top >= -.001f)
                assertTrue(rectangle.right <= width + .001f && rectangle.bottom <= height + .001f)
                assertTrue(rectangle.width > 0f && rectangle.height > 0f)
            }
        }
    }

    @Test fun everyDesktopKeyHasExactlyOneNonoverlappingPlacementBesideMouse() {
        val keys = DesktopKeyboardGeometry.keys
        assertEquals(DesktopKeyboardLayout.allKeys.map { it.code }.toSet(), keys.map { it.key.code }.toSet())
        assertEquals(keys.size, keys.map { it.key.code }.toSet().size)
        assertTrue(keys.any { it.key.code == "F12" })
        assertTrue(keys.any { it.key.code == "Enter" })
        assertTrue(keys.any { it.key.code == "ArrowRight" })
        val bounds = keys.map { it.bounds } + DesktopKeyboardGeometry.mouseBounds
        for (a in bounds.indices) for (b in a + 1 until bounds.size) {
            val first = bounds[a]; val second = bounds[b]
            val overlaps = first.left < second.right && first.right > second.left &&
                first.top < second.bottom && first.bottom > second.top
            assertFalse("Overlapping desktop target $a/$b", overlaps)
        }
    }
}
