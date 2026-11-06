package com.pleos.airdesk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONObject

class DiagnosticProtocolTest {
    private lateinit var reducer: DiagnosticMessageReducer

    @Before
    fun setUp() {
        reducer = DiagnosticMessageReducer(DiagnosticSchema(listOf(
            "control/messages.schema.json", "gesture/gesture-event.schema.json",
        ).map { path -> javaClass.classLoader!!.getResourceAsStream(path)!!.bufferedReader().use { JSONObject(it.readText()) } }))
        val accepted = reducer.consume(envelope("auth.accepted", 0, """{"mode":"diagnostic_only"}"""))
        assertTrue(accepted!!.authorized)
    }

    @Test
    fun movementAndGestureStatesAreApplied() {
        val move = reducer.consume(envelope("gesture.move", 1, point()))
        assertTrue(move!!.cursorVisible)
        assertTrue(move.handDetected)
        assertEquals(GesturePhase.TRACKING, move.gesturePhase)

        val pinch = reducer.consume(envelope("gesture.pinch_start", 2, point()))
        assertEquals(GesturePhase.PINCHED, pinch!!.gesturePhase)

        val drag = reducer.consume(envelope("gesture.drag_start", 3, point()))
        assertEquals(GesturePhase.DRAGGING, drag!!.gesturePhase)

        val released = reducer.consume(envelope("gesture.pinch_end", 4, point()))
        assertEquals(GesturePhase.TRACKING, released!!.gesturePhase)
    }

    @Test
    fun duplicateAndOutOfOrderEventsAreIgnored() {
        reducer.consume(envelope("gesture.move", 4, point(.7)))
        assertNull(reducer.consume(envelope("gesture.move", 4, point(.1))))
        assertNull(reducer.consume(envelope("gesture.move", 3, point(.1))))
        assertEquals(.7f, reducer.state.x, .0001f)
        assertEquals(4L, reducer.state.lastSequence)
    }

    @Test
    fun malformedAndUnexpectedPropertiesAreIgnored() {
        val extra = """{"x":0.2,"y":0.4,"confidence":0.9,"extra":true}"""
        assertNull(reducer.consume(envelope("gesture.move", 1, extra)))
        assertNull(reducer.consume(envelope("gesture.move", 1, """{"x":2,"y":0.4,"confidence":0.9}""")))
        assertNull(reducer.consume(envelope("unknown.event", 1, "{}")))
        assertEquals(0L, reducer.state.lastSequence)
    }

    @Test
    fun trackingLossAndWatchdogHideCursor() {
        reducer.consume(envelope("gesture.move", 1, point()))
        val lost = reducer.consume(envelope("tracking.lost", 2, """{"reason":"hand_not_detected"}"""))
        assertFalse(lost!!.cursorVisible)
        assertEquals(GesturePhase.LOST, lost.gesturePhase)

        reducer.consume(envelope("gesture.move", 3, point()))
        val timedOut = reducer.trackingTimedOut(3)
        assertFalse(timedOut!!.cursorVisible)
        assertFalse(timedOut.handDetected)
    }

    @Test
    fun staleTimestampAndCrossSessionAreIgnored() {
        reducer.reset()
        reducer.consume(envelope("auth.accepted", 0, """{"mode":"diagnostic_only"}"""), 5000)
        assertNull(reducer.consume(envelope("gesture.move", 1, point()), 5800))
        assertNull(reducer.consume(envelope("gesture.move", 1, point()).replace("camera-demo", "other"), 5100))
        assertEquals(0L, reducer.state.lastSequence)
    }

    @Test
    fun movementDoesNotClearHeldPinchAndResetClearsIt() {
        reducer.consume(envelope("gesture.pinch_start", 1, point()))
        val move = reducer.consume(envelope("gesture.move", 2, point(.4)))
        assertEquals(GesturePhase.PINCHED, move!!.gesturePhase)
        assertFalse(reducer.reset().cursorVisible)
        assertFalse(reducer.state.authorized)
    }

    @Test
    fun integerAndNumberStringsAreNotCoerced() {
        assertNull(reducer.consume(envelope("gesture.move", 1, point()).replace("\"sequence\":1", "\"sequence\":\"1\"")))
        assertNull(reducer.consume(envelope("gesture.move", 1, """{"x":"0.2","y":0.4,"confidence":0.9}""")))
    }

    private fun point(x: Double = .25) = """{"x":$x,"y":0.4,"confidence":0.9}"""

    private fun envelope(type: String, sequence: Long, payload: String) =
        """{"protocolVersion":"0.1","type":"$type","sessionId":"camera-demo","sequence":$sequence,"timestampMs":1000,"payload":$payload}"""
}
