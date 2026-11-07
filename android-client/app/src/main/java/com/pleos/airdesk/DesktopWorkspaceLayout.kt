package com.pleos.airdesk

import kotlin.math.max
import kotlin.math.min

/** Height budget below the actual measured title/tool/status/footer chrome, in density-independent pixels. */
data class DesktopWorkspacePlan(
    val compactControls: Boolean,
    val editorMinHeightDp: Float,
    val editorAreaHeightDp: Float,
    val keyboardHeightDp: Float,
    val showSecondaryEditor: Boolean,
    val showExamples: Boolean,
    val needsWorkspaceScroll: Boolean,
)

/**
 * Preserve an editable body before assigning space to the full desktop keyboard.
 * Very small windows require an explicit scrolling workspace; they never silently
 * squeeze the text field down to only its title and menu button.
 */
object DesktopWorkspaceLayout {
    fun calculate(
        widthDp: Float,
        heightDp: Float,
        chromeHeightDp: Float,
        keyboardVisible: Boolean,
        fontScale: Float = 1f,
    ): DesktopWorkspacePlan {
        require(widthDp.isFinite() && widthDp > 0f)
        require(heightDp.isFinite() && heightDp > 0f)
        require(chromeHeightDp.isFinite() && chromeHeightDp >= 0f)
        require(fontScale.isFinite() && fontScale > 0f)

        val textScale = max(fontScale, 1f)
        // Editor padding + nonwrapping header + body gap + a usable text body.
        val editorMin = 20f + max(44f, 18f * textScale + 16f) + 8f + 64f * textScale
        val available = max(0f, heightDp - chromeHeightDp)
        // The user explicitly prefers a complete scaled PC keyboard + mouse area to paging.
        // Reserve a readable editor first, then fit the entire keyboard into the remainder.
        val keyboardMin = min(48f, widthDp * DesktopKeyboardGeometry.HEIGHT / DesktopKeyboardGeometry.WIDTH)
        val desiredKeyboard = min(390f, widthDp * DesktopKeyboardGeometry.HEIGHT / DesktopKeyboardGeometry.WIDTH)
        val keyboardHeight = if (!keyboardVisible) 0f else
            min(max(keyboardMin, desiredKeyboard), max(keyboardMin, available - editorMin))
        val editorArea = max(editorMin, available - keyboardHeight)
        val overflow = editorMin + keyboardHeight > available
        val wide = widthDp >= 780f

        return DesktopWorkspacePlan(
            compactControls = !wide || heightDp < 560f * textScale,
            editorMinHeightDp = editorMin,
            editorAreaHeightDp = editorArea,
            keyboardHeightDp = keyboardHeight,
            showSecondaryEditor = wide && !overflow && editorArea >= editorMin * 2f + 7f,
            showExamples = wide && !overflow && editorArea >= max(280f, editorMin),
            needsWorkspaceScroll = overflow,
        )
    }
}

data class DesktopKeyboardRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    fun fitted(fit: DesktopKeyboardFit): DesktopKeyboardRect = DesktopKeyboardRect(
        fit.left + left * fit.scale, fit.top + top * fit.scale, width * fit.scale, height * fit.scale)
}

data class DesktopKeyPlacement(val key: DesktopKeySpec, val bounds: DesktopKeyboardRect)
data class DesktopKeyboardFit(val scale: Float, val left: Float, val top: Float, val width: Float, val height: Float)

/** Shared by the actual absolute Compose placement and geometry acceptance tests. */
object DesktopKeyboardGeometry {
    const val WIDTH = 1120f
    const val HEIGHT = 360f
    val mouseBounds = DesktopKeyboardRect(972f, 44f, 140f, 308f)

    val keys: List<DesktopKeyPlacement> = buildList {
        fun row(specs: List<DesktopKeySpec>, y: Float, unit: Float = 52f, start: Float = 8f) {
            var x = start
            for (key in specs) {
                val width = unit * key.units - 4f
                add(DesktopKeyPlacement(key, DesktopKeyboardRect(x, y, width, 48f)))
                x += width + 4f
            }
        }
        row(DesktopKeyboardLayout.functionRow, 44f)
        DesktopKeyboardLayout.mainRows.forEachIndexed { index, specs -> row(specs, 96f + index * 52f) }
        // Navigation keys form a separate non-overlapping desktop cluster.
        row(DesktopKeyboardLayout.navigationRows[0], 96f, unit = 56f, start = 796f)
        row(DesktopKeyboardLayout.navigationRows[1], 148f, unit = 56f, start = 796f)
        row(DesktopKeyboardLayout.navigationRows[2], 252f, unit = 56f, start = 852f)
        row(DesktopKeyboardLayout.navigationRows[3], 304f, unit = 56f, start = 796f)
    }

    fun fit(widthDp: Float, heightDp: Float): DesktopKeyboardFit {
        require(widthDp.isFinite() && widthDp > 0f && heightDp.isFinite() && heightDp > 0f)
        val scale = min(widthDp / WIDTH, heightDp / HEIGHT)
        val width = WIDTH * scale
        val height = HEIGHT * scale
        return DesktopKeyboardFit(scale, (widthDp - width) / 2f, (heightDp - height) / 2f, width, height)
    }
}
