package com.pleos.airdesk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedWireCasesTest {
    @Test fun canonicalCrossPlatformWireCasesAgreeWithoutTreatingSchemaAsAuthority() {
        val resource = javaClass.classLoader!!.getResourceAsStream("phase2-wire-cases.json")!!
        val cases = resource.bufferedReader().use { JSONObject(it.readText()).getJSONArray("cases") }
        val schema = phase2Schema()
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val accepted = runCatching { schema.accepts(StrictJson.objectValue(case.getString("wire"))) }.getOrDefault(false)
            assertEquals(case.getString("id"), case.getBoolean("schemaValid"), accepted)
        }
    }
}
