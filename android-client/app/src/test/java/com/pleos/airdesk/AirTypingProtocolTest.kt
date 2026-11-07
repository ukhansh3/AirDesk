package com.pleos.airdesk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AirTypingProtocolTest {
    private fun protocol() = AirTypingProtocol(DiagnosticSchema(listOf(JSONObject(
        javaClass.classLoader!!.getResource("air/air-messages.schema.json")!!.readText()))))
    private fun message(type: String, seq: Int, payload: JSONObject, session: String = "air-test") = JSONObject()
        .put("protocolVersion", "0.2").put("type", type).put("sessionId", session)
        .put("sequence", seq).put("timestampMs", 1000 + seq * 20).put("payload", payload)
    private fun auth(p: AirTypingProtocol) = p.consume(message("auth.accepted", 0,
        JSONObject().put("mode", "air_typing_demo")).toString(), 1000)
    private fun frame(seq: Int = 1, epoch: String = "epoch-a", armed: Boolean = false) = message("air.frame", seq,
        JSONObject().put("epoch", epoch).put("stage", "READY").put("status", if (armed) "ARMED" else "READY")
            .put("armed", armed).put("fingers", JSONArray()).put("mouse", JSONObject()
                .put("active", false).put("valid", false).put("x", .5).put("y", .5).put("pressed", false).put("dragging", false)))
    private fun finger() = JSONObject().put("hand", "left").put("finger", "index")
        .put("x", .3).put("y", .4).put("valid", true).put("pressed", false)

    @Test fun explicitAuthenticationAndCompleteFrameAreRequired() {
        val p = protocol()
        assertNull(p.consume(frame().toString(), 1020))
        assertTrue(auth(p) is AirWireEvent.Authorized)
        assertTrue(p.consume(frame().toString(), 1020) is AirWireEvent.Frame)
        assertNull(p.consume(frame().toString(), 1020))
        assertNull(p.consume(frame(2).put("sessionId", "other").toString(), 1040))
        assertTrue(p.consume(frame(2).toString(), 1040) is AirWireEvent.Frame)
    }
    @Test fun strictSchemaHandlesArraysBooleansAndRejectsUnknownOrNonFiniteData() {
        val p = protocol(); auth(p)
        val valid = frame(); valid.getJSONObject("payload").getJSONArray("fingers").put(finger())
        assertTrue(p.consume(valid.toString(), 1020) is AirWireEvent.Frame)
        val bad = frame(2); bad.getJSONObject("payload").getJSONArray("fingers").put(finger().put("pressed", "true"))
        assertNull(p.consume(bad.toString(), 1040))
        assertNull(p.consume(frame(2).put("protocolVersion", "0.1").toString(), 1040))
        assertNull(p.consume(frame(2).put("extra", true).toString(), 1040))
        assertNull(p.consume(frame(2).toString().replace("\"epoch-a\"", "NaN"), 1040))
        assertNull(p.consume(frame(2).toString().replace("\"armed\":false", "\"armed\":false,\"armed\":true"), 1040))
    }
    @Test fun duplicateFingerAndUnarmedPressNeverBecomeAcceptedHints() {
        val p = protocol(); auth(p)
        val duplicate = frame(); duplicate.getJSONObject("payload").getJSONArray("fingers").put(finger()).put(finger())
        assertNull(p.consume(duplicate.toString(), 1020))
        val pressed = frame(); pressed.getJSONObject("payload").getJSONArray("fingers").put(finger().put("pressed", true))
        assertNull(p.consume(pressed.toString(), 1020))
        val inconsistent = frame(armed = true); inconsistent.getJSONObject("payload").put("status", "READY")
        assertNull(p.consume(inconsistent.toString(), 1020))
    }
    @Test fun retiredCalibrationCannotReplayAndNewEpochCannotArriveArmed() {
        val p = protocol(); auth(p)
        assertNotNull(p.consume(frame().toString(), 1020))
        assertNull(p.consume(frame(2, "epoch-b", true).toString(), 1040))
        assertNotNull(p.consume(frame(2, "epoch-b").toString(), 1040))
        assertNull(p.consume(frame(3, "epoch-a").toString(), 1060))
        assertNotNull(p.consume(frame(3, "epoch-b").toString(), 1060))
    }
    @Test fun sourceClockOffsetPermitsDifferentOriginsButNotDelayedFrames() {
        val p = protocol()
        val auth = message("auth.accepted", 0, JSONObject().put("mode", "air_typing_demo")).put("timestampMs", 100)
        assertNotNull(p.consume(auth.toString(), 10000))
        assertNotNull(p.consume(frame().put("timestampMs", 120).toString(), 10020))
        assertNull(p.consume(frame(2).put("timestampMs", 140).toString(), 10400))
        assertNull(p.consume(frame(2).put("timestampMs", 119).toString(), 10040))
        p.reset()
        assertNull(p.consume(frame(3).toString(), 1060))
    }
}
