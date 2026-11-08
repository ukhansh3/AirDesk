package com.pleos.airdesk

import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class AirTransportRecoveryPolicyTest {
    private fun frame(stage: String = "LEFT_RING", status: String = "WAITING", epoch: String = "cal-epoch",
        armed: Boolean = false, session: String = "authenticated-session", seq: Long = 2) =
        AirFrame(epoch, stage, status, armed, emptyList(), AirMouse(false, false, .5f, .5f, false, false), session, seq, 1000)
    private fun confirmed() = AirTransportRecoveryPolicy().apply {
        authorized("authenticated-session")
        frameAccepted(frame("READY", "READY", "previous-epoch", seq = 1))
        command("start_calibration")
        frameAccepted(frame())
    }

    @Test fun delayedUnarmedCalibrationRetainsItsStepWithoutSendingDisarm() {
        val policy = confirmed()
        val lost = policy.inputLost(AirInputLoss.TIMEOUT)
        assertTrue(lost.retainCalibration)
        assertFalse(lost.sendDisarm)
        assertTrue(policy.calibrationPaused)
        assertEquals("LEFT_RING", policy.retainedCalibrationStage)
    }

    @Test fun staleValidFrameUsesTheSameSafeCalibrationPause() {
        val policy = confirmed()
        val lost = policy.inputLost(AirInputLoss.STALE_VALID_FRAME)
        assertTrue(lost.retainCalibration)
        assertFalse(lost.sendDisarm)
        val resumed = policy.frameAccepted(frame("LEFT_LITTLE", seq = 3))
        assertFalse(resumed.releaseLocalInput)
        assertTrue(resumed.retainCalibration)
        assertFalse(policy.calibrationPaused)
        assertNull(policy.retainedCalibrationStage)
        policy.inputLost(AirInputLoss.TIMEOUT)
        assertEquals("LEFT_LITTLE", policy.retainedCalibrationStage)
    }

    @Test fun onlyAnExplicitAndConfirmedCalibrationCanBeRetained() {
        val noIntent = AirTransportRecoveryPolicy()
        noIntent.frameAccepted(frame())
        assertFalse(noIntent.inputLost(AirInputLoss.TIMEOUT).retainCalibration)
        val awaitingStart = AirTransportRecoveryPolicy()
        awaitingStart.authorized("authenticated-session")
        awaitingStart.frameAccepted(frame("READY", "READY", "old"))
        awaitingStart.command("start_calibration")
        awaitingStart.frameAccepted(frame("READY", "READY", "old", seq = 3))
        assertFalse(awaitingStart.inputLost(AirInputLoss.TIMEOUT).retainCalibration)
    }

    @Test fun armedAndPendingArmLossAlwaysRequireDisarmAndNeverRetainCalibration() {
        val armed = AirTransportRecoveryPolicy()
        armed.frameAccepted(frame("READY", "ARMED", armed = true))
        assertEquals(AirRecoveryDecision(false, true), armed.inputLost(AirInputLoss.TIMEOUT))
        val pending = confirmed()
        pending.command("arm")
        assertEquals(AirRecoveryDecision(false, true), pending.inputLost(AirInputLoss.STALE_VALID_FRAME))
        assertFalse(pending.calibrationPaused)
    }

    @Test fun genericMalformedMessagesNeverGetCalibrationRecoveryTreatment() {
        val policy = confirmed()
        assertEquals(AirRecoveryDecision(false, true), policy.inputLost(AirInputLoss.INVALID_MESSAGE))
        assertFalse(policy.calibrationPaused)
        assertNull(policy.retainedCalibrationStage)
        policy.frameAccepted(frame("LEFT_LITTLE", seq = 3))
        assertFalse(policy.inputLost(AirInputLoss.TIMEOUT).retainCalibration)
    }

    @Test fun explicitCancelResetOrManualCaptureClearsAutomaticCalibrationIntent() {
        for (action in listOf("disarm", "reset", "capture")) {
            val policy = confirmed()
            policy.command(action)
            assertFalse(policy.inputLost(AirInputLoss.TIMEOUT).retainCalibration)
            assertFalse(policy.calibrationPaused)
            assertNull(policy.retainedCalibrationStage)
        }
    }

    @Test fun reconnectOrAuthorizationResetCannotRestoreOldCalibrationIntent() {
        val policy = confirmed()
        policy.inputLost(AirInputLoss.TIMEOUT)
        policy.reset()
        assertFalse(policy.calibrationPaused)
        policy.frameAccepted(frame("LEFT_RING", session = "new-session", epoch = "new-epoch"))
        assertFalse(policy.inputLost(AirInputLoss.TIMEOUT).retainCalibration)
    }

    @Test fun newSessionOrUnexpectedEpochEndsRetainedProgress() {
        for (replacement in listOf(frame(epoch = "replacement"), frame(session = "replacement"))) {
            val policy = confirmed()
            policy.inputLost(AirInputLoss.TIMEOUT)
            val changed = policy.frameAccepted(replacement)
            assertTrue(changed.releaseLocalInput)
            assertFalse(changed.retainCalibration)
            assertFalse(policy.calibrationPaused)
            assertNull(policy.retainedCalibrationStage)
            assertFalse(policy.inputLost(AirInputLoss.TIMEOUT).retainCalibration)
        }
    }

    @Test fun anotherExplicitStartMayConfirmOneNewEpochButNeverResurrectTheOldOne() {
        val policy = confirmed()
        policy.command("start_calibration")
        policy.frameAccepted(frame(seq = 3)) // An in-flight old frame is not confirmation.
        assertFalse(policy.calibrationPaused)
        val next = policy.frameAccepted(frame("NEUTRAL", "COUNTDOWN", epoch = "next-calibration", seq = 4))
        assertTrue(next.retainCalibration)
        assertTrue(policy.inputLost(AirInputLoss.TIMEOUT).retainCalibration)
        assertFalse(policy.frameAccepted(frame(seq = 5)).retainCalibration)
    }

    @Test fun completionEndsProgressRetentionButDoesNotIssueOrInferAnArm() {
        val policy = confirmed()
        policy.inputLost(AirInputLoss.TIMEOUT)
        val done = policy.frameAccepted(frame("READY", "READY", seq = 3))
        assertFalse(done.releaseLocalInput)
        assertFalse(done.retainCalibration)
        assertFalse(policy.calibrationPaused)
        assertEquals(AirRecoveryDecision(false, false), policy.inputLost(AirInputLoss.TIMEOUT))
    }

    @Test fun explicitlyStartingCalibrationFromArmedInputRevokesInputButKeepsConfirmedNewRun() {
        val policy = AirTransportRecoveryPolicy()
        policy.authorized("authenticated-session")
        policy.frameAccepted(frame("READY", "ARMED", "armed-epoch", armed = true))
        policy.command("start_calibration")
        val begun = policy.frameAccepted(frame("NEUTRAL", "COUNTDOWN", "new-calibration", seq = 3))
        assertTrue(begun.releaseLocalInput)
        assertTrue(begun.retainCalibration)
        assertEquals(AirRecoveryDecision(true, false), policy.inputLost(AirInputLoss.TIMEOUT))
        assertEquals("NEUTRAL", policy.retainedCalibrationStage)
    }

    @Test fun pendingStartBeforeFirstFrameCanPauseOnlyAsProgressFreePresentation() {
        val policy = AirTransportRecoveryPolicy()
        policy.authorized("authenticated-session")
        policy.command("start_calibration")
        assertEquals(AirRecoveryDecision(true, false), policy.inputLost(AirInputLoss.TIMEOUT))
        assertTrue(policy.calibrationPaused)
        assertNull(policy.retainedCalibrationStage)
        val confirmed = policy.frameAccepted(frame("NEUTRAL", "COUNTDOWN"))
        assertTrue(confirmed.retainCalibration)
        assertFalse(policy.calibrationPaused)
        assertEquals(AirRecoveryDecision(true, false), policy.inputLost(AirInputLoss.TIMEOUT))
        assertEquals("NEUTRAL", policy.retainedCalibrationStage)
    }

    @Test fun firstEmptyNeutralPauseDoesNotClearPendingStartOrInventProgress() {
        val policy = AirTransportRecoveryPolicy()
        policy.authorized("authenticated-session"); policy.command("start_calibration")
        val paused = policy.frameAccepted(frame("NEUTRAL", "WAITING", "first-pause"))
        assertFalse(paused.releaseLocalInput)
        assertFalse(paused.retainCalibration) // The start has not been confirmed as calibration progress.
        assertEquals(AirRecoveryDecision(true, false), policy.inputLost(AirInputLoss.TIMEOUT))
        assertTrue(policy.calibrationPaused)
        assertNull(policy.retainedCalibrationStage)
        policy.frameAccepted(frame("NEUTRAL", "WAITING", "first-pause", seq = 3))
        assertEquals(AirRecoveryDecision(true, false), policy.inputLost(AirInputLoss.STALE_VALID_FRAME))
        assertNull(policy.retainedCalibrationStage)
        val begun = policy.frameAccepted(frame("NEUTRAL", "COUNTDOWN", "first-pause", seq = 4))
        assertTrue(begun.retainCalibration)
        assertFalse(policy.calibrationPaused)
    }

    @Test fun replacingUnconfirmedEmptyPauseIdentityCannotRetainPendingStart() {
        val policy = AirTransportRecoveryPolicy()
        policy.authorized("authenticated-session"); policy.command("start_calibration")
        policy.frameAccepted(frame("NEUTRAL", "WAITING", "first-pause"))
        policy.inputLost(AirInputLoss.TIMEOUT)
        val replaced = policy.frameAccepted(frame("NEUTRAL", "WAITING", "different-pause", seq = 3))
        assertTrue(replaced.releaseLocalInput)
        assertFalse(replaced.retainCalibration)
        assertFalse(policy.inputLost(AirInputLoss.TIMEOUT).retainCalibration)
        assertNull(policy.retainedCalibrationStage)
    }

    @Test fun pendingStartCannotUseOldReadyArmedOrDifferentSessionAsCalibrationProgress() {
        for (unexpected in listOf(frame("READY", "READY"), frame("READY", "ARMED", armed = true),
            frame("NEUTRAL", "COUNTDOWN", session = "different-session"))) {
            val policy = AirTransportRecoveryPolicy()
            policy.authorized("authenticated-session"); policy.command("start_calibration")
            policy.inputLost(AirInputLoss.TIMEOUT)
            val outcome = policy.frameAccepted(unexpected)
            assertTrue(outcome.releaseLocalInput)
            assertFalse(outcome.retainCalibration)
            assertFalse(policy.calibrationPaused)
            assertNull(policy.retainedCalibrationStage)
        }
        val unauthenticated = AirTransportRecoveryPolicy()
        unauthenticated.command("start_calibration")
        assertFalse(unauthenticated.inputLost(AirInputLoss.TIMEOUT).retainCalibration)
    }

    @Test fun pendingArmLosingAllTrackingCancelsConsentBeforeALateArmedFrame() {
        val keyboard = DesktopKeyboardController().apply {
            registerEditor("note"); focusEditor("note"); press("Lang")
        }
        val controller = AirTypingController(keyboard).apply { setAllowed(true); setEnabled(true) }
        val policy = AirTransportRecoveryPolicy()
        val unarmed = frame("READY", "READY")
        policy.frameAccepted(unarmed); controller.accept(unarmed, 1000)
        policy.command("arm")
        val loss = policy.frameAccepted(unarmed.copy(seq = 3, receivedAtMs = 1010))
        assertTrue(loss.releaseLocalInput)
        assertTrue(loss.sendDisarm)
        assertFalse(loss.retainCalibration)
        controller.cancel("air_source_state_changed")
        controller.accept(unarmed.copy(armed = true, status = "ARMED", seq = 4, receivedAtMs = 1020), 1020)
        assertFalse(controller.snapshot.enabled)
        assertEquals("", keyboard.value("note").text)
    }

    @Test fun queuedUnarmedButTrackedFrameDoesNotCancelAnOrdinaryArmAcknowledgment() {
        val policy = AirTransportRecoveryPolicy()
        val tracked = frame("READY", "READY").copy(fingers = listOf(AirFinger("left", "index", .3f, .4f, false, true)))
        policy.frameAccepted(tracked)
        policy.command("arm")
        val queued = policy.frameAccepted(tracked.copy(seq = 3))
        assertFalse(queued.releaseLocalInput)
        assertFalse(queued.sendDisarm)
        assertFalse(policy.frameAccepted(tracked.copy(armed = true, status = "ARMED", seq = 4)).releaseLocalInput)
    }

    @Test fun disconnectReasonsAreBoundedKnownLabelsWithoutRawServerContent() {
        assertEquals(AirDisconnectNotice.INVALID_CAPTURE, AirDisconnectNotice.fromClose(1011, "invalid AIR capture"))
        assertEquals(AirDisconnectNotice.CAMERA_BUSY, AirDisconnectNotice.fromClose(1013, "camera profile already active"))
        assertEquals(AirDisconnectNotice.AUTH_REJECTED, AirDisconnectNotice.fromClose(1008, "not authorized"))
        assertEquals(AirDisconnectNotice.PROFILE_ENDED, AirDisconnectNotice.fromClose(1008, "AIR profile ended"))
        assertEquals(AirDisconnectNotice.DIAGNOSTIC_ENDED, AirDisconnectNotice.fromClose(1000, "AIR diagnostic ended"))
        val privateReason = "sensitive arbitrary peer content"
        val unknown = AirDisconnectNotice.fromClose(1011, privateReason)
        assertEquals(AirDisconnectNotice.CLOSED, unknown)
        assertFalse(unknown.label.contains(privateReason))
        assertEquals(AirDisconnectNotice.CLOSED, AirDisconnectNotice.fromClose(1000, "invalid AIR capture"))
        assertEquals(AirDisconnectNotice.INVALID_CAPTURE,
            AirDisconnectNotice.remember(AirDisconnectNotice.INVALID_CAPTURE, AirDisconnectNotice.CONNECTION_FAILED))
        assertEquals(AirDisconnectNotice.INVALID_CAPTURE,
            AirDisconnectNotice.remember(AirDisconnectNotice.INVALID_CAPTURE, AirDisconnectNotice.CLOSED))
        assertEquals(AirDisconnectNotice.AUTH_REJECTED,
            AirDisconnectNotice.remember(AirDisconnectNotice.INVALID_CAPTURE, AirDisconnectNotice.AUTH_REJECTED))
    }

    @Test fun sourceUnarmRevokesLocalConsentEvenWithoutAWatchdogTimeout() {
        val keyboard = DesktopKeyboardController().apply {
            registerEditor("note", TextFieldValue()); focusEditor("note"); press("Lang")
        }
        val controller = AirTypingController(keyboard).apply { setAllowed(true); setEnabled(true) }
        val policy = AirTransportRecoveryPolicy()
        val ready = frame("READY", "ARMED", armed = true)
        policy.frameAccepted(ready); controller.accept(ready, 1000)
        val paused = ready.copy(armed = false, status = "READY", seq = 3, receivedAtMs = 1010)
        val transition = policy.frameAccepted(paused)
        assertTrue(transition.releaseLocalInput)
        assertFalse(transition.retainCalibration)
        if (transition.releaseLocalInput) controller.cancel("air_source_state_changed")
        controller.accept(paused, 1010)
        controller.accept(ready.copy(seq = 4, receivedAtMs = 1020), 1020)
        assertFalse(controller.snapshot.enabled)
        assertFalse(controller.snapshot.ready)
        assertEquals("", keyboard.value("note").text)
    }
}
