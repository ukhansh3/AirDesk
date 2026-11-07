package com.pleos.airdesk

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Validated Phase 1 wire -> app target callbacks -> focused desktop editor, with no Android events. */
class DesktopInteractionIntegrationTest {
    @Test fun cameraKeyRequiresReleaseConfirmationAndReplayCannotTypeAgain() {
        val h = WorkspaceHarness()
        h.click("note-a", 1000)
        h.move("key-KeyA", 1350)
        assertEquals("", h.keyboard.value("note-a").text)
        h.send("gesture.pinch_start", "key-KeyA", 1400)
        h.send("gesture.pinch_end", "key-KeyA", 1500)
        val releaseSequence = h.sequence
        assertEquals("", h.keyboard.value("note-a").text)
        h.send("gesture.move", "key-KeyA", 1520)
        assertEquals("a", h.keyboard.value("note-a").text)
        assertFalse(h.send("gesture.pinch_end", "key-KeyA", 1500, releaseSequence))
        h.move("key-KeyA", 1600)
        assertEquals("a", h.keyboard.value("note-a").text)
    }

    @Test fun mouseSelectedModifierChordsUseOnlyFocusedEditorAndExplicitClipboard() {
        val h = WorkspaceHarness()
        h.click("note-a", 1000)
        h.keyboard.updateEditorValue("note-a", TextFieldValue("AirDesk", TextRange(7)))
        h.click("key-ControlLeft", 1400)
        h.click("key-KeyA", 1800)
        assertEquals(TextRange(0, 7), h.keyboard.value("note-a").selection)
        h.click("key-KeyC", 2200)
        assertEquals("AirDesk", h.clipboard.content)
        assertEquals(1, h.clipboard.writes)
        h.click("key-KeyX", 2600)
        assertEquals("", h.keyboard.value("note-a").text)
        h.click("key-KeyV", 3000)
        assertEquals("AirDesk", h.keyboard.value("note-a").text)
        assertEquals(1, h.clipboard.reads)
        h.click("key-KeyZ", 3400)
        assertEquals("", h.keyboard.value("note-a").text)
        h.click("key-KeyZ", 3800)
        assertEquals("AirDesk", h.keyboard.value("note-a").text)
        assertEquals("", h.keyboard.value("note-b").text)
    }

    @Test fun gestureFocusTransferCommitsCompositionAndReleasesLatchedModifiers() {
        val h = WorkspaceHarness(english = false)
        h.click("note-a", 1000)
        h.click("key-KeyR", 1400)
        assertEquals("ㄱ", h.keyboard.value("note-a").text)
        assertNotNull(h.keyboard.value("note-a").composition)
        h.click("key-ShiftLeft", 1800)
        assertTrue(h.keyboard.shift)
        h.click("note-b", 2200)
        assertFalse(h.keyboard.shift)
        assertNull(h.keyboard.value("note-a").composition)
        h.click("key-KeyK", 2600)
        assertEquals("ㅏ", h.keyboard.value("note-b").text)
        assertEquals("ㄱ", h.keyboard.value("note-a").text)
    }

    @Test fun oneShotRightClickOpensLocalContextAndThenRestoresEditorFocusClicks() {
        val h = WorkspaceHarness()
        h.click("mode-right", 1000)
        assertEquals(GestureMouseMode.RIGHT_ONCE, h.router.snapshot.mode)
        h.click("note-a", 1400)
        assertEquals(1, h.contextOpens)
        assertEquals(GestureMouseMode.LEFT, h.router.snapshot.mode)
        assertNull(h.keyboard.focusedEditorId)
        h.click("note-b", 1800)
        assertEquals("note-b", h.keyboard.focusedEditorId)
        assertEquals(1, h.contextOpens)
    }

    @Test fun lossAfterPinchEndCancelsKeyBeforeAnyTextOrClipboardAction() {
        val h = WorkspaceHarness()
        h.click("note-a", 1000)
        h.send("gesture.pinch_start", "key-KeyA", 1400)
        h.send("gesture.pinch_end", "key-KeyA", 1500)
        h.lost(1500)
        h.move("key-KeyA", 1600)
        assertEquals("", h.keyboard.value("note-a").text)
        assertNull(h.keyboard.focusedEditorId)
        assertEquals(0, h.clipboard.reads + h.clipboard.writes)
    }

    @Test fun backgroundAndOperationalScopeCannotDispatchPendingDesktopKeys() {
        val h = WorkspaceHarness()
        h.click("note-a", 1000)
        h.send("gesture.pinch_start", "key-KeyA", 1400)
        h.send("gesture.pinch_end", "key-KeyA", 1500)
        h.router.setForeground(false)
        h.move("key-KeyA", 1520)
        h.router.setForeground(true)
        assertFalse(h.router.snapshot.enabled)
        h.router.setDemoEnabled(true)
        h.move("key-KeyA", 1600)
        assertEquals("", h.keyboard.value("note-a").text)
        h.router.setScope(UiInteractionScope.OPERATIONAL)
        h.router.setDemoEnabled(true)
        h.click("note-a", 2000)
        h.click("key-KeyA", 2400)
        assertFalse(h.router.snapshot.enabled)
        assertEquals("", h.keyboard.value("note-a").text)
    }

    private class ClipboardSpy : DesktopClipboard {
        var content: String? = null
        var reads = 0
        var writes = 0
        override fun readText(): String? { reads++; return content }
        override fun writeText(text: String) { writes++; content = text }
    }

    private class WorkspaceHarness(english: Boolean = true) {
        val clipboard = ClipboardSpy()
        val keyboard = DesktopKeyboardController(clipboard)
        val router = GestureUiRouter(onCancelled = { keyboard.resetTransient() })
        var contextOpens = 0
        var sequence = 0L
            private set
        private val targets = linkedMapOf<String, UiBounds>()
        private val reducer = DiagnosticMessageReducer(DiagnosticSchema(listOf(
            "control/messages.schema.json", "gesture/gesture-event.schema.json",
        ).map { path -> javaClass.classLoader!!.getResourceAsStream(path)!!.bufferedReader().use { JSONObject(it.readText()) } }))

        init {
            keyboard.registerEditor("note-a")
            keyboard.registerEditor("note-b")
            if (english) keyboard.press("Lang")
            router.setViewport(UiBounds(0f, 0f, 1000f, 800f))
            router.setForeground(true)
            router.setDemoEnabled(true)
            register("note-a", UiBounds(10f, 10f, 450f, 190f),
                click = { keyboard.focusEditor("note-a") }, context = { contextOpens++ })
            register("note-b", UiBounds(500f, 10f, 950f, 190f), click = { keyboard.focusEditor("note-b") })
            listOf("KeyA", "KeyC", "KeyX", "KeyV", "KeyZ", "ControlLeft", "ShiftLeft", "KeyR", "KeyK").forEachIndexed { i, code ->
                register("key-$code", UiBounds(10f + i * 100f, 300f, 90f + i * 100f, 360f), click = { keyboard.press(code) })
            }
            val bounds = UiBounds(700f, 210f, 950f, 270f)
            targets["mode-right"] = bounds
            router.register(GestureUiTarget("mode-right", bounds, isModeControl = true,
                onClick = { router.setMode(GestureMouseMode.RIGHT_ONCE) }))
            check(reducer.consume(envelope("auth.accepted", 0, 900, JSONObject().put("mode", "diagnostic_only")), 900)!!.authorized)
        }

        private fun register(id: String, bounds: UiBounds, click: () -> Unit, context: (() -> Unit)? = null) {
            targets[id] = bounds
            router.register(GestureUiTarget(id, bounds, onClick = click, onContext = context))
        }

        fun click(target: String, start: Long) {
            move(target, start)
            send("gesture.pinch_start", target, start + 1)
            send("gesture.pinch_end", target, start + 101)
            move(target, start + 121)
        }
        fun move(target: String, now: Long) = send("gesture.move", target, now)
        fun send(type: String, target: String, now: Long, overrideSequence: Long? = null): Boolean {
            val bounds = targets.getValue(target)
            val payload = JSONObject().put("x", ((bounds.left + bounds.right) / 2f / 999f).toDouble())
                .put("y", ((bounds.top + bounds.bottom) / 2f / 799f).toDouble()).put("confidence", 1.0)
            val next = overrideSequence ?: ++sequence
            if (reducer.consume(envelope(type, next, now, payload), now) == null) return false
            return reducer.lastAcceptedHint?.let { router.accept(it, now) } ?: false
        }
        fun lost(now: Long) {
            check(reducer.consume(envelope("tracking.lost", ++sequence, now,
                JSONObject().put("reason", "hand_not_detected")), now) != null)
            reducer.lastAcceptedHint?.let { router.accept(it, now) }
        }
        private fun envelope(type: String, sequence: Long, now: Long, payload: JSONObject): String =
            JSONObject().put("protocolVersion", "0.1").put("type", type).put("sessionId", "desktop-integration")
                .put("sequence", sequence).put("timestampMs", now).put("payload", payload).toString()
    }
}
