package com.pleos.airdesk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The PC reference window must show the same target positions used for Android AIR dispatch. */
class PcKeyboardGeometryParityTest {
    private fun layout() = JSONObject(javaClass.classLoader!!.getResource("pc-keyboard-geometry.json")!!.readText())
    @Test fun everyPcKeyMatchesAndroidHitGeometry() {
        val json = layout()
        assertEquals(DesktopKeyboardGeometry.WIDTH.toDouble(), json.getDouble("width"), .001)
        assertEquals(DesktopKeyboardGeometry.HEIGHT.toDouble(), json.getDouble("height"), .001)
        val keys = json.getJSONArray("keys")
        val pc = (0 until keys.length()).map { keys.getJSONObject(it) }.associateBy { it.getString("code") }
        assertEquals(keys.length(), pc.size)
        assertEquals(DesktopKeyboardGeometry.keys.map { it.key.code }.toSet(), pc.keys)
        for (key in DesktopKeyboardGeometry.keys) {
            val expected = pc.getValue(key.key.code)
            assertEquals(key.bounds.left.toDouble(), expected.getDouble("left"), .001)
            assertEquals(key.bounds.top.toDouble(), expected.getDouble("top"), .001)
            assertEquals(key.bounds.width.toDouble(), expected.getDouble("width"), .001)
            assertEquals(key.bounds.height.toDouble(), expected.getDouble("height"), .001)
        }
    }
    @Test fun sideMouseReferenceMatchesAndroidSurface() {
        val mouse = layout().getJSONObject("mouse")
        val android = DesktopKeyboardGeometry.mouseBounds
        assertEquals(android.left.toDouble(), mouse.getDouble("left"), .001)
        assertEquals(android.top.toDouble(), mouse.getDouble("top"), .001)
        assertEquals(android.width.toDouble(), mouse.getDouble("width"), .001)
        assertEquals(android.height.toDouble(), mouse.getDouble("height"), .001)
    }
}
