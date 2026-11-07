package com.pleos.airdesk

import org.json.JSONObject

data class InputCandidate(val type: String, val payload: JSONObject, val capability: String)

/** Pure candidate generation; this class has no network/OS input dependency. */
class InputCandidates {
    private val heldButtons = mutableSetOf<String>()
    private val heldKeys = mutableSetOf<String>()
    private var wasPinched = false
    val heldCount: Int get() = heldButtons.size + heldKeys.size

    fun move(x: Float, y: Float): InputCandidate? {
        if (!x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) return null
        return InputCandidate("pointer.move", JSONObject().put("displayId", "primary").put("x", x.toDouble()).put("y", y.toDouble()), "POINTER")
    }

    fun down(button: String = "left"): InputCandidate? {
        if (button !in setOf("left", "right", "middle") || !heldButtons.add(button)) return null
        return InputCandidate("pointer.down", JSONObject().put("button", button), "POINTER")
    }

    fun up(button: String = "left"): InputCandidate? {
        if (!heldButtons.remove(button)) return null
        return InputCandidate("pointer.up", JSONObject().put("button", button), "POINTER")
    }

    fun scroll(deltaY: Int): InputCandidate? {
        if (deltaY == 0) return null
        return InputCandidate("pointer.scroll", JSONObject().put("deltaX", 0).put("deltaY", deltaY.coerceIn(-120, 120)).put("unit", "line"), "POINTER")
    }

    fun text(committed: String): InputCandidate? {
        if (!validUnicode(committed) || committed.codePointCount(0, committed.length) !in 1..1024) return null
        return InputCandidate("keyboard.text", JSONObject().put("text", committed), "KEYBOARD")
    }

    fun key(code: String, down: Boolean): InputCandidate? {
        if (code !in PHYSICAL_KEYS) return null
        if (down && !heldKeys.add(code)) return null
        if (!down && !heldKeys.remove(code)) return null
        return InputCandidate(if (down) "keyboard.down" else "keyboard.up", JSONObject().put("code", code), "KEYBOARD")
    }

    fun release(reason: String): InputCandidate {
        require(reason in setOf("DISCONNECT", "TIMEOUT", "SAFETY_LOCK", "USER_REQUEST", "REVOKED", "TRACKING_LOST"))
        heldButtons.clear(); heldKeys.clear(); wasPinched = false
        return InputCandidate("input.release_all", JSONObject().put("reason", reason), "POINTER")
    }

    fun gesture(hint: DiagnosticUiState): List<InputCandidate> {
        if (!hint.authorized || !hint.handDetected || hint.gesturePhase == GesturePhase.LOST) return listOf(release("TRACKING_LOST"))
        val result = mutableListOf<InputCandidate>()
        move(hint.x, hint.y)?.let(result::add)
        val pressed = hint.gesturePhase in setOf(GesturePhase.PINCHED, GesturePhase.DRAGGING)
        if (pressed && !wasPinched) down()?.let(result::add)
        if (!pressed && wasPinched) up()?.let(result::add)
        wasPinched = pressed
        return result
    }

    companion object {
        val PHYSICAL_KEYS = setOf("Enter", "Backspace", "Delete", "ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown",
            "ShiftLeft", "ShiftRight", "ControlLeft", "ControlRight", "AltLeft", "AltRight")
        fun normalized(x: Float, y: Float, width: Float, height: Float): Pair<Float, Float>? {
            if (!x.isFinite() || !y.isFinite() || !width.isFinite() || !height.isFinite() || width <= 0 || height <= 0) return null
            return (x / width).coerceIn(0f, 1f) to (y / height).coerceIn(0f, 1f)
        }
        fun scrollLines(pixels: Float, pixelsPerLine: Float = 24f): Int = if (!pixels.isFinite() || pixelsPerLine <= 0 || !pixelsPerLine.isFinite()) 0
            else (pixels / pixelsPerLine).toInt().coerceIn(-120, 120)
    }
}
