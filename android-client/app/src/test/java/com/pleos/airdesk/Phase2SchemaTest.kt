package com.pleos.airdesk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Phase2SchemaTest {
    @Test fun capabilityArraysAndBooleanAreValidated() {
        val schema = phase2Schema()
        val granted = JSONObject(wire("session.granted", payload = """{"grantId":"test-grant-00000001","capabilities":["POINTER"],"expiresAtMs":10000}"""))
        assertTrue(schema.accepts(granted))
        granted.getJSONObject("payload").put("capabilities", org.json.JSONArray(listOf("POINTER", "POINTER")))
        assertFalse(schema.accepts(granted))
        val approval = JSONObject(wire("session.approve", payload = """{"requestId":"request","approved":"true","expiresAtMs":1}"""))
        assertFalse(schema.accepts(approval))
    }
    @Test fun codePointLengthIsNotUtf16Length() {
        val schema = phase2Schema()
        val text = "😀".repeat(1024)
        val message = JSONObject(wire("keyboard.text", payload = JSONObject().put("grantId", "grant-example-0001").put("text", text).toString()))
        assertTrue(schema.accepts(message))
        message.getJSONObject("payload").put("text", text + "a")
        assertFalse(schema.accepts(message))
    }
    @Test fun invalidSurrogatesAndUnknownAssertionsFailClosed() {
        val schema = phase2Schema()
        val message = JSONObject(wire("keyboard.text", payload = JSONObject().put("grantId", "grant-example-0001").put("text", "\uD800").toString()))
        assertFalse(schema.accepts(message))
        assertFalse(Phase2Schema(listOf(JSONObject("""{"type":"object","not":{}}"""))).accepts(JSONObject()))
    }
    @Test fun nullableStateProjectionIsSupported() {
        assertTrue(phase2Schema().accepts(JSONObject("""{"state":"DISCONNECTED","safety":"UNKNOWN","hostApproved":false,"updatedAtMs":0,"foreground":false,"authVerified":false,"heartbeatFresh":false,"uxAllowed":false,"sessionId":null}""")))
    }
    @Test fun strictJsonRejectsDuplicateNestedEscapedKeysAndTrailingData() {
        listOf("""{"a":1,"a":2}""", """{"p":{"a":1,"\u0061":2}}""", """{"a":1} trailing""",
            """{'a':1}""", """{a:1}""", """{"a":NaN}""", """{"a":Infinity}""", """{"a":01}""",
            """{"a":"\uD800"}""").forEach { raw ->
            assertTrue(runCatching { StrictJson.objectValue(raw) }.isFailure)
        }
    }
    @Test fun strictJsonLimitsDepthAndUtf8Bytes() {
        assertTrue(runCatching { StrictJson.objectValue("[".repeat(34) + "0" + "]".repeat(34)) }.isFailure)
        assertTrue(runCatching { StrictJson.objectValue("""{"a":"${"한".repeat(3000)}"}""") }.isFailure)
        assertEquals("한글😀", StrictJson.objectValue("""{"text":"한글😀"}""").getString("text"))
    }
}
