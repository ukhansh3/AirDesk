package com.pleos.airdesk

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class AirTypingControllerTest {
    private class Rig(initial: String = "", clipboard: DesktopClipboard = InMemoryDesktopClipboard()) {
        val keyboard = DesktopKeyboardController(clipboard).apply {
            registerEditor("document", TextFieldValue(initial, TextRange(initial.length)))
            registerEditor("note")
            focusEditor("document"); press("Lang")
        }
        val controller = AirTypingController(keyboard).apply { setAllowed(true); setEnabled(true) }
        var sequence = 0L
        var clock = 100L
        fun send(vararg fingers: AirFinger, mouse: AirMouse = noMouse, epoch: String = "epoch-1",
            session: String = "session-1", armed: Boolean = true): AirFrame {
            clock += 10
            val frame = AirFrame(epoch, "READY", if (armed) "ARMED" else "READY", armed,
                fingers.toList(), mouse, session, ++sequence, clock)
            assertTrue(controller.accept(frame, clock))
            return frame
        }
        fun text() = keyboard.value("document").text
    }

    @Test fun twoHandsProduceIndependentDeterministicallyOrderedCharacters() {
        val r = Rig()
        r.send(f("KeyA"), f("KeyB", "right"))
        r.send(f("KeyB", "right", pressed = true), f("KeyA", pressed = true))
        assertEquals("ab", r.text())
        assertEquals(setOf("KeyA", "KeyB"), r.controller.snapshot.pressedKeyCodes)
        repeat(3) { r.send(f("KeyA", pressed = true), f("KeyB", "right", pressed = true)) }
        assertEquals("ab", r.text())
    }

    @Test fun shiftDownIsAppliedBeforeAnotherHandsCharacterInTheSameFrame() {
        val r = Rig()
        r.send(f("KeyA"), f("ShiftRight", "right", "little"))
        r.send(f("KeyA", pressed = true), f("ShiftRight", "right", "little", pressed = true))
        assertEquals("A", r.text())
        assertFalse(r.keyboard.shift)
        assertTrue("ShiftRight" in r.controller.snapshot.pressedKeyCodes)
    }

    @Test fun validModifierUpFollowsThisFramesCharacterDownAndDoesNotLatch() {
        val r = Rig()
        r.send(f("ShiftLeft", "left", "little"), f("KeyA", "right"))
        r.send(f("ShiftLeft", "left", "little", pressed = true), f("KeyA", "right"))
        r.send(f("ShiftLeft", "left", "little"), f("KeyA", "right", pressed = true))
        assertEquals("A", r.text())
        assertFalse(r.keyboard.shift)
        r.send(f("KeyA", "right"))
        r.send(f("KeyA", "right", pressed = true))
        assertEquals("Aa", r.text())
    }

    @Test fun ctrlCopyAndPasteUseOnlyTheFocusedEditorAndHeldOwnership() {
        val clipboard = InMemoryDesktopClipboard()
        val r = Rig("synthetic", clipboard)
        r.keyboard.updateEditorValue("document", TextFieldValue("synthetic", TextRange(0, 9)))
        r.send(f("ControlLeft", "left", "little"), f("KeyC", "right"))
        r.send(f("KeyC", "right", pressed = true), f("ControlLeft", "left", "little", pressed = true))
        assertEquals("synthetic", clipboard.readText())
        assertEquals("synthetic", r.text())
        assertFalse(r.keyboard.ctrl)
        assertEquals("", r.keyboard.value("note").text)
        r.send(f("ControlLeft", "left", "little"), f("KeyV", "right"))
        r.keyboard.updateEditorValue("document", TextFieldValue("synthetic", TextRange(9)))
        r.send(f("ControlLeft", "left", "little", pressed = true), f("KeyV", "right", pressed = true))
        assertEquals("syntheticsynthetic", r.text())
    }

    @Test fun sharedKeyIsReferenceCountedAndNeverRepeatsUntilEveryOwnerReleases() {
        val r = Rig()
        r.send(f("KeyA"), f("KeyA", "right"))
        r.send(f("KeyA", pressed = true), f("KeyA", "right", pressed = true))
        assertEquals("a", r.text())
        r.send(f("KeyA"), f("KeyA", "right", pressed = true))
        assertEquals(setOf("KeyA"), r.controller.snapshot.pressedKeyCodes)
        r.send(f("KeyA", pressed = true), f("KeyA", "right", pressed = true))
        assertEquals("a", r.text())
        r.send(f("KeyA"), f("KeyA", "right"))
        assertTrue(r.controller.snapshot.pressedKeyCodes.isEmpty())
        r.send(f("KeyA", "right", pressed = true))
        assertEquals("aa", r.text())
    }

    @Test fun aHeldFingerCapturesItsOriginalKeyWhileItsHoverMoves() {
        val r = Rig()
        r.send(f("KeyA")); r.send(f("KeyA", pressed = true))
        r.send(f("KeyB", pressed = true))
        assertEquals("a", r.text())
        assertEquals(setOf("KeyA"), r.controller.snapshot.pressedKeyCodes)
        assertEquals("KeyB", r.controller.snapshot.hoveredKeys["left.index"])
        r.send(f("KeyB")); r.send(f("KeyB", pressed = true))
        assertEquals("ab", r.text())
    }

    @Test fun enteringOverAGapOrDisabledKeyConsumesDownUntilARealRelease() {
        val r = Rig()
        r.send(f("F2")); r.send(f("F2", pressed = true))
        r.send(f("KeyA", pressed = true))
        assertEquals("", r.text())
        r.send(f("KeyA")); r.send(f("KeyA", pressed = true))
        assertEquals("a", r.text())
        r.send(f("KeyA"))
        r.send(AirFinger("left", "index", 0f, 0f, true, true))
        r.send(f("KeyB", pressed = true))
        assertEquals("a", r.text())
    }

    @Test fun aMissingHandCancelsItsModifierBeforeOtherHandsCharacterDown() {
        val r = Rig()
        r.send(f("ShiftLeft", "left", "little"), f("KeyA", "right"))
        r.send(f("ShiftLeft", "left", "little", pressed = true), f("KeyA", "right"))
        r.send(f("KeyA", "right", pressed = true))
        assertEquals("a", r.text())
        assertFalse("ShiftLeft" in r.controller.snapshot.pressedKeyCodes)
        r.send(f("ShiftLeft", "left", "little", pressed = true), f("KeyA", "right", pressed = true))
        assertFalse("ShiftLeft" in r.controller.snapshot.pressedKeyCodes)
    }

    @Test fun oneInvalidFingerRearmsIndependentlyWhileOtherHandKeepsTyping() {
        val r = Rig()
        r.send(f("KeyA"), f("KeyB", "right"))
        r.send(f("KeyA", pressed = true), f("KeyB", "right", pressed = true))
        r.send(f("KeyA", pressed = true, valid = false), f("KeyB", "right"))
        r.send(f("KeyA", pressed = true), f("KeyB", "right", pressed = true))
        assertEquals("abb", r.text())
        r.send(f("KeyA"), f("KeyB", "right", pressed = true))
        r.send(f("KeyA", pressed = true), f("KeyB", "right", pressed = true))
        assertEquals("abba", r.text())
    }

    @Test fun mouseModeReleasesRightHandKeysWithoutSuppressingLeftHand() {
        val r = Rig()
        r.send(f("KeyA"), f("KeyB", "right"))
        r.send(f("KeyA"), f("KeyB", "right", pressed = true))
        r.send(f("KeyA", pressed = true), mouse = noMouse.copy(active = true, valid = true))
        assertEquals("ba", r.text())
        assertEquals(setOf("KeyA"), r.controller.snapshot.pressedKeyCodes)
        r.send(f("KeyA", pressed = true), f("KeyB", "right", pressed = true))
        assertEquals("ba", r.text())
    }

    @Test fun pressedFirstFrameCannotTypeUntilThatFingerIsSeenNeutral() {
        val r = Rig()
        r.send(f("KeyA", pressed = true)); r.send(f("KeyA", pressed = true))
        assertEquals("", r.text())
        r.send(f("KeyA")); r.send(f("KeyA", pressed = true))
        assertEquals("a", r.text())
    }

    @Test fun focusChangeCancelsOwnershipAndDoesNotTypeIntoTheNewEditor() {
        val r = Rig()
        r.send(f("KeyA")); r.send(f("KeyA", pressed = true))
        r.keyboard.focusEditor("note")
        r.send(f("KeyB", pressed = true))
        assertEquals("", r.keyboard.value("note").text)
        r.send(f("KeyB")); r.send(f("KeyB", pressed = true))
        assertEquals("b", r.keyboard.value("note").text)
        assertEquals("a", r.text())
    }

    @Test fun noFocusOrHiddenKeyboardCannotDispatchEvenSupportedKeys() {
        val r = Rig()
        r.keyboard.focusEditor(null)
        r.send(f("KeyA")); r.send(f("KeyA", pressed = true))
        assertEquals("", r.text())
        r.keyboard.focusEditor("document"); r.keyboard.hide()
        r.send(f("KeyA")); r.send(f("KeyA", pressed = true))
        assertEquals("", r.text())
    }

    @Test fun explicitLocalEnableAndServerArmAreBothRequired() {
        val r = Rig()
        r.controller.setEnabled(false)
        r.send(f("KeyA")); r.send(f("KeyA", pressed = true))
        assertEquals("", r.text())
        r.controller.setEnabled(true)
        r.send(f("KeyA"), armed = false); r.send(f("KeyA", pressed = true), armed = false)
        assertEquals("", r.text())
        r.send(f("KeyA", pressed = true)); assertEquals("", r.text())
        r.send(f("KeyA")); r.send(f("KeyA", pressed = true)); assertEquals("a", r.text())
    }

    @Test fun lifecycleAndWatchdogCancelWithoutAutomaticResume() {
        val r = Rig()
        r.send(f("ShiftLeft", "left", "little")); r.send(f("ShiftLeft", "left", "little", pressed = true))
        r.controller.setAllowed(false)
        assertTrue(r.controller.snapshot.pressedKeyCodes.isEmpty())
        assertFalse(r.controller.snapshot.enabled)
        r.controller.setAllowed(true)
        r.send(f("KeyA")); r.send(f("KeyA", pressed = true))
        assertEquals("", r.text())
        r.controller.setEnabled(true)
        r.send(f("KeyA")); r.send(f("KeyA", pressed = true)); assertEquals("a", r.text())
        r.controller.tick(r.clock + AirTypingController.MAX_AGE_MS + 1)
        assertFalse(r.controller.snapshot.enabled)
        assertTrue(r.controller.snapshot.pressedKeyCodes.isEmpty())
    }

    @Test fun staleAndMalformedFramesReleaseHeldStateAndNeverType() {
        val r = Rig()
        r.send(f("KeyA")); val old = r.send(f("KeyA", pressed = true))
        assertFalse(r.controller.accept(old.copy(seq = old.seq + 1), old.receivedAtMs + 251))
        assertFalse(r.controller.snapshot.enabled)
        assertTrue(r.controller.snapshot.pressedKeyCodes.isEmpty())
        r.controller.setEnabled(true)
        assertFalse(r.controller.accept(old.copy(seq = old.seq + 2, fingers = listOf(f("KeyB").copy(x = Float.NaN))), old.receivedAtMs))
        assertEquals("a", r.text())
    }

    @Test fun duplicateAndOutOfOrderMessagesDoNotRepeatOrExtendWatchdog() {
        val r = Rig()
        val neutral = r.send(f("KeyA")); val down = r.send(f("KeyA", pressed = true))
        assertFalse(r.controller.accept(down, r.clock + 100))
        assertFalse(r.controller.accept(neutral, r.clock + 100))
        assertEquals("a", r.text())
        r.controller.tick(down.receivedAtMs + 251)
        assertFalse(r.controller.snapshot.enabled)
    }

    @Test fun epochAndSessionReplacementRequireNewEnableAndRejectRetiredIdentity() {
        val r = Rig()
        val previous = r.send(f("KeyA")); r.send(f("KeyA", pressed = true))
        r.send(f("KeyA"), epoch = "epoch-2")
        assertFalse(r.controller.snapshot.enabled)
        assertFalse(r.controller.accept(previous.copy(seq = 99, receivedAtMs = r.clock), r.clock))
        r.controller.setEnabled(true)
        r.send(f("KeyB"), epoch = "epoch-2"); r.send(f("KeyB", pressed = true), epoch = "epoch-2")
        assertEquals("ab", r.text())
        r.send(f("KeyC"), epoch = "epoch-2", session = "session-2")
        assertFalse(r.controller.snapshot.enabled)
        assertFalse(r.controller.accept(previous.copy(seq = 100, receivedAtMs = r.clock), r.clock))
        assertEquals("ab", r.text())
    }

    @Test fun malformedDuplicateFingerAndMouseTypingConflictAreRejected() {
        val r = Rig()
        val frame = r.send(f("KeyA"))
        assertFalse(r.controller.accept(frame.copy(seq = 2, fingers = listOf(f("KeyA"), f("KeyB"))), r.clock))
        assertFalse(r.controller.accept(frame.copy(seq = 3, fingers = listOf(f("KeyA", "right")),
            mouse = noMouse.copy(active = true, valid = true)), r.clock))
        assertEquals("", r.text())
    }

    @Test fun directAirActionsDoNotOverwriteTouchLatches() {
        val r = Rig()
        r.keyboard.press("ControlLeft")
        assertTrue(r.keyboard.ctrl)
        r.keyboard.pressAir("KeyA")
        assertEquals("a", r.text())
        assertTrue(r.keyboard.ctrl)
        r.keyboard.pressAir("KeyB", heldShift = true)
        assertEquals("aB", r.text())
        assertTrue(r.keyboard.ctrl); assertFalse(r.keyboard.shift)
        r.keyboard.pressAir("ShiftLeft")
        assertFalse(r.keyboard.shift)
    }

    @Test fun helpCallbackCancellationStopsRemainingCharacterDownsInTheSameFrame() {
        lateinit var controller: AirTypingController
        val keyboard = DesktopKeyboardController(onHelp = { controller.cancel("도움말") }).apply {
            registerEditor("document"); focusEditor("document"); press("Lang")
        }
        controller = AirTypingController(keyboard).apply { setAllowed(true); setEnabled(true) }
        val neutral = AirFrame("epoch", "READY", "ARMED", true,
            listOf(f("F1", "left", "thumb"), f("KeyA", "left", "index")), noMouse, "session", 1, 100)
        assertTrue(controller.accept(neutral, 100))
        val down = neutral.copy(seq = 2, receivedAtMs = 110,
            fingers = neutral.fingers.map { it.copy(pressed = true) })
        assertTrue(controller.accept(down, 110))
        assertEquals("", keyboard.value("document").text)
        assertFalse(controller.snapshot.enabled)
        assertTrue(controller.snapshot.pressedKeyCodes.isEmpty())
        assertTrue(controller.snapshot.status.contains("도움말"))
    }

    @Test fun tabFocusCallbackStopsRemainingDownsAndRequiresNeutralInNewEditor() {
        lateinit var keyboard: DesktopKeyboardController
        keyboard = DesktopKeyboardController(onTab = { keyboard.focusEditor("note") }).apply {
            registerEditor("document"); registerEditor("note"); focusEditor("document"); press("Lang")
        }
        val controller = AirTypingController(keyboard).apply { setAllowed(true); setEnabled(true) }
        val neutral = AirFrame("epoch", "READY", "ARMED", true,
            listOf(f("Tab", "left", "thumb"), f("KeyA", "left", "index")), noMouse, "session", 1, 100)
        controller.accept(neutral, 100)
        val down = neutral.copy(seq = 2, receivedAtMs = 110, fingers = neutral.fingers.map { it.copy(pressed = true) })
        controller.accept(down, 110)
        assertEquals("note", keyboard.focusedEditorId)
        assertEquals("", keyboard.value("note").text)
        assertTrue(controller.snapshot.pressedKeyCodes.isEmpty())
        controller.accept(down.copy(seq = 3, receivedAtMs = 120), 120)
        assertEquals("", keyboard.value("note").text)
        controller.accept(neutral.copy(seq = 4, receivedAtMs = 130), 130)
        controller.accept(neutral.copy(seq = 5, receivedAtMs = 140,
            fingers = listOf(f("KeyA", "left", "index", pressed = true))), 140)
        assertEquals("a", keyboard.value("note").text)
    }

    @Test fun diagnosticsSeparateObservedPressFromNeutralPreparationAndAcceptedKeyDown() {
        val r = Rig()
        r.send(f("KeyA", pressed = true), f("KeyB", "right", pressed = true))
        assertEquals(2, r.controller.snapshot.observedFingerCount)
        assertEquals(2, r.controller.snapshot.detectedPressedCount)
        assertFalse(r.controller.snapshot.ready)
        assertTrue(r.controller.snapshot.rearmedOwners.isEmpty())
        assertEquals(setOf(AirFingerState.WAIT_NEUTRAL), r.controller.snapshot.fingerStates.values.toSet())
        assertEquals("", r.text())
        r.send(f("KeyA"), f("KeyB", "right"))
        assertTrue(r.controller.snapshot.ready)
        assertEquals(setOf("left.index", "right.index"), r.controller.snapshot.rearmedOwners)
        assertEquals(AirFingerState.HOVER, r.controller.snapshot.fingerStates["left.index"])
        r.send(f("KeyA", pressed = true), f("KeyB", "right"))
        assertEquals(AirFingerState.KEYDOWN, r.controller.snapshot.fingerStates["left.index"])
        assertEquals(AirFingerState.HOVER, r.controller.snapshot.fingerStates["right.index"])
        assertEquals(setOf("KeyA"), r.controller.snapshot.pressedKeyCodes)
        assertEquals(1, r.controller.snapshot.detectedPressedCount)
        assertEquals("a", r.text())
    }

    @Test fun diagnosticsReportGapAndUnsupportedDownUntilReleaseRatherThanClaimTyping() {
        val r = Rig()
        val gap = AirFinger("left", "index", 0f, 0f, false, true)
        r.send(gap)
        assertFalse(r.controller.snapshot.ready)
        assertEquals(AirFingerState.NO_TARGET, r.controller.snapshot.fingerStates["left.index"])
        r.send(gap.copy(pressed = true))
        r.send(f("KeyA", pressed = true))
        assertEquals(AirFingerState.NO_TARGET, r.controller.snapshot.fingerStates["left.index"])
        assertFalse(r.controller.snapshot.ready)
        assertEquals("", r.text())
        r.send(f("F2")); r.send(f("F2", pressed = true))
        assertEquals(AirFingerState.UNSUPPORTED, r.controller.snapshot.fingerStates["left.index"])
        assertFalse(r.controller.snapshot.ready)
        assertTrue(r.controller.snapshot.pressedKeyCodes.isEmpty())
        r.send(f("KeyA")); r.send(f("KeyA", pressed = true))
        assertEquals(AirFingerState.KEYDOWN, r.controller.snapshot.fingerStates["left.index"])
        assertEquals("a", r.text())
    }

    @Test fun serverArmedCannotMakeLocalOffDiagnosticsClaimReady() {
        val r = Rig()
        r.controller.cancel("손 추적 시간 초과")
        r.send(f("KeyA", pressed = true))
        assertFalse(r.controller.snapshot.enabled)
        assertFalse(r.controller.snapshot.ready)
        assertTrue(r.controller.snapshot.status.contains("입력 꺼짐"))
        assertEquals(AirFingerState.BLOCKED, r.controller.snapshot.fingerStates["left.index"])
        assertEquals(1, r.controller.snapshot.observedFingerCount)
        assertEquals(1, r.controller.snapshot.detectedPressedCount)
        assertTrue(r.controller.snapshot.rearmedOwners.isEmpty())
        assertEquals("", r.text())
    }

    @Test fun diagnosticsDistinguishMissingEditorAndHiddenKeyboard() {
        val r = Rig()
        r.keyboard.focusEditor(null)
        r.send(f("KeyA"))
        assertTrue(r.controller.snapshot.status.contains("편집기"))
        assertEquals(AirFingerState.BLOCKED, r.controller.snapshot.fingerStates["left.index"])
        r.keyboard.hide()
        r.send(f("KeyA"))
        assertTrue(r.controller.snapshot.status.contains("키보드를 펼치세요"))
        assertFalse(r.controller.snapshot.ready)
        assertEquals("", r.text())
    }

    @Test fun diagnosticMapsAreBoundedAndLostOrMouseFingerDoesNotRemainRearmed() {
        val r = Rig()
        val all = listOf("left", "right").flatMap { hand ->
            listOf("thumb", "index", "middle", "ring", "little").map { finger -> f("KeyA", hand, finger) }
        }
        r.send(*all.toTypedArray())
        assertEquals(10, r.controller.snapshot.observedFingerCount)
        assertEquals(10, r.controller.snapshot.rearmedOwners.size)
        assertEquals(10, r.controller.snapshot.fingerStates.size)
        r.send(f("KeyA", "left", valid = false), f("KeyA", "right", valid = false),
            mouse = noMouse.copy(active = true, valid = true))
        assertEquals(0, r.controller.snapshot.observedFingerCount)
        assertTrue(r.controller.snapshot.rearmedOwners.isEmpty())
        assertEquals(AirFingerState.LOST, r.controller.snapshot.fingerStates["left.index"])
        assertEquals(AirFingerState.MOUSE, r.controller.snapshot.fingerStates["right.index"])
        assertEquals(2, r.controller.snapshot.fingerStates.size)
        r.send()
        assertTrue(r.controller.snapshot.fingerStates.isEmpty())
        assertTrue(r.controller.snapshot.rearmedOwners.isEmpty())
    }

    companion object {
        private val noMouse = AirMouse(false, false, 0f, 0f, false, false)
        private fun f(code: String, hand: String = "left", finger: String = "index", pressed: Boolean = false,
            valid: Boolean = true): AirFinger {
            val b = DesktopKeyboardGeometry.keys.single { it.key.code == code }.bounds
            return AirFinger(hand, finger, (b.left + b.width / 2f) / DesktopKeyboardGeometry.WIDTH,
                (b.top + b.height / 2f) / DesktopKeyboardGeometry.HEIGHT, pressed, valid)
        }
    }
}
