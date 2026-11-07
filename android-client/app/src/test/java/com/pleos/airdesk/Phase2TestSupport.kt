package com.pleos.airdesk

import org.json.JSONObject

internal fun phase2Schema(): Phase2Schema = Phase2Schema(listOf(
    "pairing/pairing-messages.schema.json", "session/session-messages.schema.json",
    "input/input-event.schema.json", "errors/error.schema.json", "session/session-state.schema.json",
).map { path -> Phase2Schema::class.java.classLoader!!.getResourceAsStream(path)!!.bufferedReader().use { JSONObject(it.readText()) } })

internal fun wire(type: String, sid: String = "session-1", seq: Long = 0, timestamp: Long = 1100, payload: String): String =
    JSONObject().put("protocolVersion", "0.1").put("type", type).put("sessionId", sid)
        .put("sequence", seq).put("timestampMs", timestamp).put("payload", JSONObject(payload)).toString()

internal const val PEER = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

internal fun approvedSession(): SecureSessionReducer {
    val reducer = SecureSessionReducer(phase2Schema())
    reducer.bind(PEER); reducer.foreground(true); reducer.pairingStarted("pair-1")
    check(reducer.consume(wire("pairing.completed", "pair-1", payload = """{"pairingId":"offer-1","connectionId":"connection-1"}"""), PEER, 200, 100000))
    reducer.requestStarted("session-1", setOf("POINTER", "KEYBOARD"))
    check(reducer.consume(wire("session.approve", payload = """{"requestId":"request-1","approved":true,"expiresAtMs":160000}"""), PEER, 200, 100000))
    check(reducer.consume(wire("session.granted", seq = 1, payload = """{"grantId":"grant-example-0001","capabilities":["POINTER","KEYBOARD"],"expiresAtMs":160000}"""), PEER, 200, 100000))
    return reducer
}
