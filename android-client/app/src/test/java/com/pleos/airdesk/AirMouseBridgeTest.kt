package com.pleos.airdesk

import org.junit.Assert.*
import org.junit.Test

class AirMouseBridgeTest {
    private fun frame(t: Long, pressed: Boolean = false, active: Boolean = true, valid: Boolean = true,
        dragging: Boolean = false, epoch: String = "epoch-a") = AirFrame(epoch, "READY", "ARMED", true,
        emptyList(), AirMouse(active, valid, .5f, .5f, pressed, dragging), "air-test", t, t)
    @Test fun pinchReleaseUsesExistingClickConfirmationRatherThanHoverOrHoldRepeat() {
        val b = AirMouseBridge(); var clicks = 0
        val router = GestureUiRouter()
        router.setViewport(UiBounds(0f, 0f, 1000f, 360f), 1f)
        router.setForeground(true); router.setDemoEnabled(true)
        router.register(GestureUiTarget("target", UiBounds(400f, 100f, 600f, 250f), onClick = { clicks++ }))
        for (f in listOf(frame(10), frame(100, true), frame(190, true), frame(200), frame(230), frame(260)))
            b.accept(f, true, f.receivedAtMs).forEach { router.accept(it, f.receivedAtMs) }
        assertEquals(1, clicks)
    }
    @Test fun lossReleasesAsTrackingLossAndNeverEmitsClickRelease() {
        val b = AirMouseBridge(); b.accept(frame(10), true, 10); b.accept(frame(100, true), true, 100)
        assertEquals(listOf("tracking.lost"), b.accept(frame(200, active = false), true, 200).map { it.type })
        assertTrue(b.accept(frame(210, true), true, 210).none { it.type == "gesture.pinch_start" })
        b.accept(frame(220), true, 220)
        assertEquals("gesture.pinch_start", b.accept(frame(300, true), true, 300).single().type)
    }
    @Test fun disarmAndStaleFramesCancelAndRequireFreshNeutral() {
        val b = AirMouseBridge(); b.accept(frame(10), true, 10)
        assertEquals("tracking.lost", b.accept(frame(20), false, 20).single().type)
        assertTrue(b.accept(frame(30, true), true, 30).none { it.type == "gesture.pinch_start" })
        assertEquals("tracking.lost", b.accept(frame(40), true, 400).single().type)
    }
    @Test fun dragStartsOnceAndEpochReplacementRearms() {
        val b = AirMouseBridge(); b.accept(frame(10), true, 10); b.accept(frame(100, true), true, 100)
        assertEquals("gesture.drag_start", b.accept(frame(200, true, dragging = true), true, 200).single().type)
        assertEquals("gesture.drag_move", b.accept(frame(230, true, dragging = true), true, 230).single().type)
        assertEquals("gesture.move", b.accept(frame(240, true, epoch = "epoch-b"), true, 240).single().type)
    }
}
