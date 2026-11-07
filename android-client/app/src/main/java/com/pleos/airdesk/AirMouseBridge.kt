package com.pleos.airdesk

/** Converts the explicitly armed side-pad state into the existing local cursor router's hints. */
class AirMouseBridge {
    private var source: String? = null
    private var sequence = 0L
    private var active = false
    private var ready = false
    private var pressed = false
    private var dragging = false
    fun reset() { source = null; active = false; ready = false; pressed = false; dragging = false }
    fun accept(frame: AirFrame, enabled: Boolean, nowMs: Long): List<ValidatedGestureHint> {
        val sid = "${frame.sessionId}.mouse.${frame.epoch}"
        if (source != sid) { reset(); source = sid }
        val result = mutableListOf<ValidatedGestureHint>()
        fun emit(type: String) { result += ValidatedGestureHint(type, sid, ++sequence, nowMs, nowMs,
            frame.mouse.x, frame.mouse.y, if (type == "tracking.lost") 0f else 1f) }
        if (!enabled || !frame.armed || !frame.mouse.active || !frame.mouse.valid || nowMs - frame.receivedAtMs !in 0..250) {
            if (active) emit("tracking.lost")
            active = false; ready = false; pressed = false; dragging = false
            return result
        }
        active = true
        if (!frame.mouse.pressed) ready = true
        if (!ready) { emit("gesture.move"); return result }
        when {
            frame.mouse.pressed && !pressed -> { emit("gesture.pinch_start"); pressed = true }
            !frame.mouse.pressed && pressed -> { emit("gesture.pinch_end"); pressed = false; dragging = false }
            frame.mouse.dragging && !dragging -> { emit("gesture.drag_start"); dragging = true }
            dragging -> emit("gesture.drag_move")
            else -> emit("gesture.move")
        }
        return result
    }
}
