package com.pleos.airdesk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiagnosticGestureDeliveryTest {
    private fun reducer() = DiagnosticMessageReducer(DiagnosticSchema(listOf(
        "control/messages.schema.json", "gesture/gesture-event.schema.json").map {
        javaClass.classLoader!!.getResourceAsStream(it)!!.bufferedReader().use { r -> JSONObject(r.readText()) }
    }))
    private fun wire(type: String, sequence: Int, time: Int = sequence, payload: String = """{"x":0.2,"y":0.2,"confidence":1.0}""") =
        """{"protocolVersion":"0.1","type":"$type","sessionId":"camera-demo","sequence":$sequence,"timestampMs":$time,"payload":$payload}"""

    @Test fun onlyAcceptedValidatedHintsExposeOriginalTransitionTypeAndReceiptTime() {
        val r = reducer()
        r.consume(wire("auth.accepted", 0, payload = """{"mode":"diagnostic_only"}"""), 1000)
        assertNull(r.lastAcceptedHint)
        r.consume(wire("gesture.pinch_start", 1, 100), 1100)
        assertEquals("gesture.pinch_start", r.lastAcceptedHint!!.type)
        assertEquals(1100L, r.lastAcceptedHint!!.receivedAtMs)
        assertEquals(.2f, r.lastAcceptedHint!!.x!!, 0f)
        assertNull(r.consume(wire("gesture.pinch_start", 1, 100), 1101))
        assertNull(r.lastAcceptedHint)
        assertNull(r.consume(wire("gesture.move", 100, 100000), 1102))
        assertNull(r.lastAcceptedHint)
    }

    @Test fun busDeliversStartReleaseLossWithoutStateConflationOrSubscriptionReplay() {
        val bus = DiagnosticGestureBus(); val events = mutableListOf<String>()
        val remove = bus.observe { if (it is DiagnosticGestureSignal.Hint) events.add(it.value.type) }
        listOf("gesture.pinch_start", "gesture.pinch_end", "tracking.lost").forEachIndexed { i, type ->
            bus.emit(DiagnosticGestureSignal.Hint(ValidatedGestureHint(type, "s", i.toLong(), i.toLong(), i.toLong())))
        }
        assertEquals(listOf("gesture.pinch_start", "gesture.pinch_end", "tracking.lost"), events)
        remove(); bus.emit(DiagnosticGestureSignal.Cancel("disconnect"))
        val later = mutableListOf<DiagnosticGestureSignal>(); bus.observe { later.add(it) }
        assertTrue(later.isEmpty())
    }

    @Test fun observerFailureCancelsOtherObserversAndUnsubscribesFailingCallback() {
        val bus = DiagnosticGestureBus(); val received = mutableListOf<DiagnosticGestureSignal>()
        var failures = 0
        bus.observe { if (it is DiagnosticGestureSignal.Hint) { failures++; throw IllegalStateException("synthetic") } }
        bus.observe { received.add(it) }
        val hint = DiagnosticGestureSignal.Hint(ValidatedGestureHint("gesture.move", "s", 1, 1, 1))
        bus.emit(hint)
        assertEquals(1, failures); assertTrue(received.single() is DiagnosticGestureSignal.Cancel)
        bus.emit(hint); assertEquals(1, failures); assertEquals(hint, received.last())
    }

    @Test fun duplicateJsonPropertiesAreRejectedBeforeAnyGestureIsPublished() {
        val r = reducer(); r.consume(wire("auth.accepted", 0, payload = """{"mode":"diagnostic_only"}"""), 1000)
        val duplicate = wire("gesture.move", 1).replace("\"x\":0.2", "\"x\":0.2,\"\\u0078\":0.8")
        assertNull(r.consume(duplicate, 1001)); assertNull(r.lastAcceptedHint)
    }

    @Test fun duplicateEnvelopePropertiesAndTrailingTokensNeverExposeHints() {
        val r = reducer(); r.consume(wire("auth.accepted", 0, payload = """{"mode":"diagnostic_only"}"""), 1000)
        val normal = wire("gesture.move", 1)
        val variants = listOf(
            normal.replace("\"type\":\"gesture.move\"", "\"type\":\"gesture.move\",\"type\":\"gesture.pinch_end\""),
            normal.replace("\"sequence\":1", "\"sequence\":1,\"\\u0073equence\":2"),
            normal.replace("\"payload\":", "\"payload\":{},\"payload\":"),
            normal + " false",
        )
        variants.forEach { assertNull(r.consume(it, 1001)); assertNull(r.lastAcceptedHint) }
    }
}
