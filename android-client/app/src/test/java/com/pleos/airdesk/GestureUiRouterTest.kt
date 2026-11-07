package com.pleos.airdesk

import org.junit.Assert.*
import org.junit.Test

class GestureUiRouterTest {
    private class Fixture {
        var clicks = 0
        var contexts = 0
        var cancellations = 0
        val dragged = mutableListOf<UiPoint>()
        val ended = mutableListOf<Boolean>()
        val scroll = mutableListOf<Float>()
        val router = GestureUiRouter(onCancelled = { cancellations++ })
        var sequence = 0L
        init { router.setViewport(UiBounds(0f, 0f, 1000f, 1000f)); router.setForeground(true); router.setDemoEnabled(true) }
        fun button(id: String = "button", layer: Int = 0, enabled: Boolean = true, modeControl: Boolean = false) = GestureUiTarget(
            id, UiBounds(100f, 100f, 400f, 400f), layer, enabled, modeControl,
            onClick = { clicks++ }, onContext = { contexts++ })
        fun addButton() { router.register(button()) }
        fun addDrag() { router.register(button().copy(onDrag = { dragged.add(it) }, onDragEnd = { ended.add(it) })) }
        fun event(type: String, time: Long, x: Float = .2f, y: Float = .2f, confidence: Float = 1f,
            session: String = "source", seq: Long = sequence++, received: Long = time): Boolean = router.accept(
            ValidatedGestureHint(type, session, seq, time, received, x, y, confidence), time)
        fun click(start: Long = 10) {
            event("gesture.pinch_start", start); event("gesture.pinch_end", start + 90); event("gesture.move", start + 100)
        }
    }

    @Test fun normalizedPositionUsesMeasuredRootViewportAndHalfOpenEdges() {
        val rect = UiBounds(50f, 120f, 450f, 320f)
        assertEquals(UiPoint(50f, 120f), rect.map(0f, 0f))
        assertEquals(UiPoint(449f, 319f), rect.map(1f, 1f))
        assertTrue(rect.contains(rect.map(1f, 1f)!!))
        assertNull(rect.map(Float.NaN, .5f)); assertNull(rect.map(-.1f, .5f)); assertNull(rect.map(.5f, 1.1f))
        assertNull(UiBounds(0f, 0f, 0f, 20f).map(.5f, .5f))
    }

    @Test fun normalReleaseClicksOnceOnlyAfterFreshValidHandConfirmation() {
        val f = Fixture(); f.addButton()
        f.event("gesture.pinch_start", 10); f.event("gesture.pinch_end", 100)
        assertEquals(0, f.clicks); assertEquals(UiPointerPhase.PENDING_RELEASE, f.router.snapshot.phase)
        f.event("gesture.move", 110); f.event("gesture.move", 120)
        assertEquals(1, f.clicks)
        assertNull(f.router.snapshot.pressedId)
    }

    @Test fun releaseThenTrackingLostCannotClickEvenWithNonzeroReleaseConfidence() {
        val f = Fixture(); f.addButton()
        f.event("gesture.pinch_start", 10); f.event("gesture.pinch_end", 100)
        f.event("tracking.lost", 101); f.event("gesture.move", 120)
        assertEquals(0, f.clicks); assertNull(f.router.snapshot.capturedId)
    }

    @Test fun phase1ZeroConfidenceReleaseCancelsDragAndClick() {
        val f = Fixture(); f.addDrag()
        f.event("gesture.pinch_start", 10); f.event("gesture.drag_start", 100, x = .25f)
        f.event("gesture.pinch_end", 110, x = .25f, confidence = 0f); f.event("tracking.lost", 110)
        assertEquals(listOf(true), f.ended); assertEquals(0, f.clicks)
    }

    @Test fun minimumPinchAndDebounceSuppressThresholdBounce() {
        val f = Fixture(); f.addButton()
        f.event("gesture.pinch_start", 10); f.event("gesture.pinch_end", 89); f.event("gesture.move", 90)
        assertEquals(0, f.clicks)
        f.click(110); f.click(230)
        assertEquals(1, f.clicks)
        f.click(500); assertEquals(2, f.clicks)
    }

    @Test fun jitterLimitUsesDensityAndMovementNeverTurnsUnrecognizedDragIntoClick() {
        val f = Fixture(); f.addButton(); f.router.setViewport(UiBounds(0f, 0f, 1000f, 1000f), 2f)
        f.event("gesture.pinch_start", 10); f.event("gesture.move", 90, x = .22f)
        f.event("gesture.pinch_end", 100, x = .22f); f.event("gesture.move", 110, x = .22f)
        assertEquals(1, f.clicks) // 20px is inside the approved 12dp/24px limit.
        f.event("gesture.pinch_start", 500); f.event("gesture.move", 580, x = .23f)
        f.event("gesture.pinch_end", 590, x = .23f); f.event("gesture.move", 600, x = .23f)
        assertEquals(1, f.clicks)
        f.event("gesture.pinch_start", 1000); f.event("gesture.drag_start", 1100, x = .25f)
        f.event("gesture.pinch_end", 1120, x = .2f); f.event("gesture.move", 1130)
        assertEquals(1, f.clicks)
    }

    @Test fun dragCapturesOneTargetAndValidDropDoesNotClick() {
        val f = Fixture(); f.addDrag()
        f.event("gesture.pinch_start", 10); f.event("gesture.drag_start", 100, x = .28f)
        f.event("gesture.drag_move", 120, x = .75f)
        assertEquals("button", f.router.snapshot.capturedId)
        f.event("gesture.pinch_end", 140, x = .75f)
        assertTrue(f.ended.isEmpty())
        f.event("gesture.move", 150, x = .75f)
        assertEquals(listOf(false), f.ended); assertEquals(0, f.clicks)
        assertEquals(.75f * 999f, f.dragged.last().x, .01f)
    }

    @Test fun capturedTargetMayMoveItsOwnBoundsWithoutLosingOwnership() {
        val f = Fixture(); f.addDrag()
        f.event("gesture.pinch_start", 10); f.event("gesture.drag_start", 100, x = .28f)
        f.router.register(f.button().copy(bounds = UiBounds(300f, 100f, 600f, 400f),
            onDrag = { f.dragged.add(it) }, onDragEnd = { f.ended.add(it) }))
        assertEquals("button", f.router.snapshot.capturedId)
        f.event("gesture.pinch_end", 140, x = .4f); f.event("gesture.move", 150, x = .4f)
        assertEquals(listOf(false), f.ended)
    }

    @Test fun phase1MoveThenDragMoveAtSamePositionDoesNotDuplicateDragCallback() {
        val f = Fixture(); f.addDrag()
        f.event("gesture.pinch_start", 10); f.event("gesture.drag_start", 100, x = .28f)
        f.event("gesture.move", 120, x = .3f); f.event("gesture.drag_move", 120, x = .3f)
        assertEquals(2, f.dragged.size)
    }

    @Test fun clickWithoutPostReleaseHandFrameTimesOutInsteadOfFiring() {
        val f = Fixture(); f.addButton()
        f.event("gesture.pinch_start", 10); f.event("gesture.pinch_end", 100)
        f.router.tick(900); f.event("gesture.move", 910)
        assertEquals(0, f.clicks); assertEquals(UiPointerPhase.HOVER, f.router.snapshot.phase)
    }

    @Test fun unregisterAndDisabledTargetCancelCapturedDrag() {
        for (remove in listOf(true, false)) {
            val f = Fixture(); f.addDrag(); f.event("gesture.pinch_start", 10); f.event("gesture.drag_start", 100, x = .28f)
            if (remove) f.router.unregister("button") else f.router.register(f.button(enabled = false))
            assertEquals(listOf(true), f.ended); assertNull(f.router.snapshot.capturedId)
            f.event("gesture.pinch_end", 140); f.event("gesture.move", 150)
            assertEquals(0, f.clicks)
        }
    }

    @Test fun viewportResizeCancelsPressedAndUnconfirmedRelease() {
        for (release in listOf(true, false)) {
            val f = Fixture(); f.addButton(); f.event("gesture.pinch_start", 10)
            if (release) f.event("gesture.pinch_end", 100)
            f.router.setViewport(UiBounds(40f, 20f, 840f, 620f))
            f.event("gesture.move", 120)
            assertEquals(0, f.clicks); assertNull(f.router.snapshot.pressedId)
        }
    }

    @Test fun rightClickIsOneShotAndNeverFallsBackToLeftOnUnsupportedTarget() {
        val f = Fixture(); f.addButton(); f.router.setMode(GestureMouseMode.RIGHT_ONCE); f.click()
        assertEquals(1, f.contexts); assertEquals(0, f.clicks); assertEquals(GestureMouseMode.LEFT, f.router.snapshot.mode)
        f.router.register(f.button().copy(onContext = null)); f.router.setMode(GestureMouseMode.RIGHT_ONCE); f.click(500)
        assertEquals(0, f.clicks); assertEquals(1, f.contexts); assertEquals(GestureMouseMode.LEFT, f.router.snapshot.mode)
    }

    @Test fun explicitModeBeforeFirstFrameIsPreservedButReplacingSourceCancelsIt() {
        val f = Fixture(); f.addButton(); f.router.setMode(GestureMouseMode.RIGHT_ONCE)
        f.event("gesture.move", 10)
        assertEquals(GestureMouseMode.RIGHT_ONCE, f.router.snapshot.mode)
        f.event("gesture.move", 20, session = "replacement", seq = 0)
        assertEquals(GestureMouseMode.LEFT, f.router.snapshot.mode)
        assertNull(f.router.snapshot.pressedId)
    }

    @Test fun overlayAppearingAfterReleaseCannotActivateCoveredTarget() {
        val f = Fixture(); f.addButton()
        f.event("gesture.pinch_start", 10); f.event("gesture.pinch_end", 100)
        f.router.register(GestureUiTarget("modal.shield", UiBounds(0f, 0f, 1000f, 1000f),
            layer = 200, isModeControl = true, onClick = {}))
        f.event("gesture.move", 110)
        assertEquals(0, f.clicks); assertEquals(0, f.contexts)
        assertNull(f.router.snapshot.pressedId)
        assertEquals(UiPointerPhase.LOST, f.router.snapshot.phase)
    }

    @Test fun modeControlsRemainLeftClickableAndDisabledTargetsBlockClickThrough() {
        val f = Fixture(); f.router.register(f.button(id = "mode", modeControl = true))
        f.router.setMode(GestureMouseMode.RIGHT_ONCE); f.click()
        assertEquals(1, f.clicks); assertEquals(0, f.contexts)
        f.router.register(f.button(id = "disabled", layer = 10, enabled = false)); f.click(500)
        assertEquals(1, f.clicks)
    }

    @Test fun scrollModeUsesCapturedEligibleTargetWithDirectionRateAndPerEventClamp() {
        val f = Fixture(); f.router.register(f.button().copy(onScroll = { f.scroll.add(it) }))
        f.router.setMode(GestureMouseMode.SCROLL)
        f.event("gesture.pinch_start", 10, y = .3f); f.event("gesture.drag_start", 110, y = .2f)
        assertEquals(1, f.scroll.size); assertEquals(48f, f.scroll.last(), .01f)
        f.event("gesture.drag_move", 115, y = .1f); assertEquals(1, f.scroll.size)
        f.event("gesture.drag_move", 130, y = .1f)
        assertEquals(24f, f.scroll.last(), .01f)
        f.event("gesture.drag_move", 150, y = .2f); assertTrue(f.scroll.last() < 0f)
        f.event("gesture.pinch_end", 170, y = .2f); f.event("gesture.move", 180, y = .2f)
        assertEquals(0, f.clicks); assertNull(f.router.snapshot.capturedId)
    }

    @Test fun scrollModeCapturesEligibleContainerUnderClickableListRow() {
        val f = Fixture()
        f.router.register(f.button(id = "container").copy(bounds = UiBounds(0f, 0f, 900f, 900f), onScroll = { f.scroll.add(it) }))
        f.router.register(f.button(id = "row", layer = 1).copy(parentId = "container"))
        f.router.setMode(GestureMouseMode.SCROLL)
        f.event("gesture.pinch_start", 10, y = .3f); f.event("gesture.drag_start", 110, y = .2f)
        assertEquals("container", f.router.snapshot.capturedId)
        assertTrue(f.scroll.isNotEmpty()); assertEquals(0, f.clicks)
    }

    @Test fun contextModeFindsEligibleParentButNeverClicksThroughDisabledOverlay() {
        val f = Fixture(); f.router.register(f.button(id = "parent"))
        f.router.register(f.button(id = "label", layer = 1).copy(onContext = null, parentId = "parent"))
        f.router.setMode(GestureMouseMode.RIGHT_ONCE); f.click()
        assertEquals(1, f.contexts)
        f.router.register(f.button(id = "blocked", layer = 3, enabled = false))
        f.router.setMode(GestureMouseMode.RIGHT_ONCE); f.click(500)
        assertEquals(1, f.contexts); assertEquals(0, f.clicks)
    }

    @Test fun unrelatedSmallOverlayNeverFallsThroughToUnderlyingContextTarget() {
        val f = Fixture(); f.addButton(); f.router.setMode(GestureMouseMode.RIGHT_ONCE)
        f.event("gesture.pinch_start", 10); f.event("gesture.pinch_end", 100)
        f.router.register(GestureUiTarget("small.modal", UiBounds(180f, 180f, 240f, 240f), layer = 200, onClick = {}))
        f.event("gesture.move", 110)
        assertEquals(0, f.contexts); assertEquals(0, f.clicks)
        assertEquals(UiPointerPhase.LOST, f.router.snapshot.phase)
    }

    @Test fun newHigherOverlayCancelsDragWhileOwnedMovingDescendantDoesNotCancelScroll() {
        val f = Fixture(); f.addDrag(); f.event("gesture.pinch_start", 10); f.event("gesture.drag_start", 100, x = .28f)
        f.router.register(GestureUiTarget("modal", UiBounds(250f, 150f, 350f, 250f), layer = 200, onClick = {}))
        assertEquals(listOf(true), f.ended); assertNull(f.router.snapshot.capturedId)

        val s = Fixture(); s.router.register(s.button(id = "scroll").copy(onScroll = { s.scroll.add(it) }))
        s.router.setMode(GestureMouseMode.SCROLL)
        s.event("gesture.pinch_start", 10, y = .3f); s.event("gesture.drag_start", 110, y = .2f)
        s.router.register(s.button(id = "row", layer = 1).copy(parentId = "scroll"))
        assertEquals("scroll", s.router.snapshot.capturedId)
    }

    @Test fun undeclaredMissingOrCyclicParentCannotBorrowAnUnderlyingAction() {
        val f = Fixture(); f.addButton()
        f.router.register(f.button(id = "orphan", layer = 10).copy(parentId = "missing"))
        f.click(); assertEquals(0, f.clicks)
        f.router.register(f.button(id = "orphan", layer = 10).copy(parentId = "orphan"))
        f.click(500); assertEquals(0, f.clicks)
    }

    @Test fun staleRejectedHighSequenceDoesNotPoisonLaterValidInput() {
        val f = Fixture(); f.addButton(); f.event("gesture.move", 10)
        assertFalse(f.event("gesture.pinch_start", 1000, seq = 999, received = 0))
        f.click(1100); assertEquals(1, f.clicks)
    }

    @Test fun duplicateAndOutOfOrderHintsCannotRepeatActions() {
        val f = Fixture(); f.addButton(); f.click()
        assertFalse(f.event("gesture.pinch_end", 120, seq = 1))
        assertFalse(f.event("gesture.pinch_start", 121, seq = 0))
        f.event("gesture.move", 130); assertEquals(1, f.clicks)
    }

    @Test fun receiveClockRollbackCancelsCaptureAndDoesNotConsumeSequence() {
        val f = Fixture(); f.addDrag(); f.event("gesture.pinch_start", 100)
        assertFalse(f.router.accept(ValidatedGestureHint("gesture.drag_start", "source", 99, 200, 90, .28f, .2f, 1f), 90))
        assertNull(f.router.snapshot.pressedId)
        f.click(500); assertEquals(1, f.clicks)
    }

    @Test fun malformedAndOutsidePositionCancelRatherThanDispatch() {
        val f = Fixture(); f.addButton(); f.event("gesture.pinch_start", 10)
        assertFalse(f.event("gesture.move", 100, x = Float.NaN))
        f.event("gesture.pinch_end", 110); f.event("gesture.move", 120)
        assertEquals(0, f.clicks)
    }

    @Test fun timeoutAndNewSessionCannotRestoreOldPressedOrPendingActions() {
        val f = Fixture(); f.addDrag(); f.event("gesture.pinch_start", 10); f.event("gesture.drag_start", 100, x = .28f)
        f.router.tick(900); assertEquals(listOf(true), f.ended)
        f.event("gesture.pinch_end", 1000, session = "new-session"); f.event("gesture.move", 1100, session = "new-session")
        assertEquals(0, f.clicks); assertNull(f.router.snapshot.pressedId)
    }

    @Test fun backgroundUnsafeAndOperationalScopeNeverRestoreAuthorityAutomatically() {
        val f = Fixture(); f.addButton(); f.event("gesture.pinch_start", 10)
        f.router.setForeground(false); f.router.setForeground(true); f.click(100)
        assertEquals(0, f.clicks); assertFalse(f.router.snapshot.enabled)
        f.router.setDemoEnabled(true); f.router.setVehicleSafety(VehicleSafety.DRIVING); f.router.setVehicleSafety(VehicleSafety.PARKED_CONFIRMED)
        f.click(500); assertEquals(0, f.clicks)
        f.router.setScope(UiInteractionScope.OPERATIONAL); f.router.setDemoEnabled(true); f.click(1000)
        assertFalse(f.router.snapshot.enabled); assertEquals(0, f.clicks)
    }

    @Test fun callbackFailureCancelsCaptureWithoutCrashingOrRetryingAction() {
        val f = Fixture(); f.router.register(f.button().copy(onClick = { throw IllegalStateException("synthetic") }))
        f.click(); assertEquals(UiPointerPhase.LOST, f.router.snapshot.phase)
        assertNull(f.router.snapshot.pressedId)
    }

    @Test fun registeringFreshCallbackObjectsDoesNotCauseRecompositionNotifications() {
        var changes = 0; val router = GestureUiRouter(onChanged = { changes++ })
        val target = GestureUiTarget("key", UiBounds(0f, 0f, 50f, 50f), onClick = {})
        router.register(target); val initial = changes
        repeat(10) { router.register(target.copy(onClick = {})) }
        assertEquals(initial, changes)
    }
}
