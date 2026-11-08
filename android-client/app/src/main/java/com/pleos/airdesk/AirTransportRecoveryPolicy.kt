package com.pleos.airdesk

enum class AirInputLoss { TIMEOUT, STALE_VALID_FRAME, INVALID_MESSAGE }
data class AirRecoveryDecision(val retainCalibration: Boolean, val sendDisarm: Boolean)
data class AirFrameContinuity(val releaseLocalInput: Boolean = false, val retainCalibration: Boolean = false,
    val sendDisarm: Boolean = false)

enum class AirDisconnectNotice(val label: String) {
    INVALID_CAPTURE("카메라 데이터 오류 · 새 보정 필요"),
    CAMERA_BUSY("다른 진단 모드가 카메라 사용 중"),
    AUTH_REJECTED("개발 인증 거부"),
    PROFILE_ENDED("서버가 AIR 프로필 종료 · 새 보정 필요"),
    DIAGNOSTIC_ENDED("카메라 진단 종료 · 새 보정 필요"),
    CONNECTION_FAILED("카메라/연결 오류 · 새 보정 필요"),
    CLOSED("연결 종료 · 새 보정 필요");

    companion object {
        /** Never display an arbitrary close reason, exception or request content. */
        fun fromClose(code: Int, reason: String): AirDisconnectNotice = when (code to reason) {
            1011 to "invalid AIR capture" -> INVALID_CAPTURE
            1013 to "camera profile already active" -> CAMERA_BUSY
            1008 to "not authorized" -> AUTH_REJECTED
            1008 to "AIR profile ended" -> PROFILE_ENDED
            1000 to "AIR diagnostic ended" -> DIAGNOSTIC_ENDED
            else -> CLOSED
        }
        fun remember(previous: AirDisconnectNotice?, next: AirDisconnectNotice): AirDisconnectNotice =
            if (previous != null && previous !in setOf(CLOSED, CONNECTION_FAILED) && next in setOf(CLOSED, CONNECTION_FAILED))
                previous else next
    }
}

/**
 * Input authority and calibration presentation are separate. Only a user-requested, confirmed,
 * unarmed calibration may retain its progress through a temporary data gap. No method issues arm,
 * accepts an old frame, or restores local input consent.
 */
class AirTransportRecoveryPolicy {
    private data class Identity(val session: String, val epoch: String)
    private var latestFrame: AirFrame? = null
    private var calibrationIntent = false
    private var awaitingStartEpoch = false
    private var previousIdentity: Identity? = null
    private var pendingStartIdentity: Identity? = null
    private var confirmedIdentity: Identity? = null
    private var confirmedStage: String? = null
    private var armRequested = false
    private var observedArmed = false
    private var authenticatedSession: String? = null
    var calibrationPaused: Boolean = false; private set
    val retainedCalibrationStage: String? get() = if (calibrationPaused) confirmedStage else null

    fun reset() {
        latestFrame = null; armRequested = false; observedArmed = false; authenticatedSession = null
        clearCalibration()
    }

    fun authorized(sessionId: String) { reset(); authenticatedSession = sessionId }

    fun command(action: String) {
        when (action) {
            "start_calibration" -> {
                previousIdentity = latestFrame?.identity()
                pendingStartIdentity = null
                calibrationIntent = authenticatedSession != null; awaitingStartEpoch = calibrationIntent
                confirmedIdentity = null; confirmedStage = null; calibrationPaused = false
                armRequested = false
            }
            "arm" -> { armRequested = true; clearCalibration() }
            "disarm", "reset", "capture" -> { armRequested = false; clearCalibration() }
        }
    }

    fun frameAccepted(frame: AirFrame): AirFrameContinuity {
        val wasArmed = observedArmed
        val identity = frame.identity()
        var calibrationReplaced = false
        val pendingArmLostTracking = armRequested && !wasArmed && !frame.armed &&
            frame.fingers.none { it.valid } && !frame.mouse.valid
        latestFrame = frame; observedArmed = frame.armed
        if (wasArmed && !frame.armed || pendingArmLostTracking) armRequested = false

        if (calibrationIntent && frame.sessionId != authenticatedSession) {
            calibrationReplaced = true; clearCalibration()
        }

        if (calibrationIntent) {
            if (awaitingStartEpoch) {
                if (previousIdentity == null && (frame.armed || frame.stage == "READY")) {
                    calibrationReplaced = true; clearCalibration()
                }
                if (calibrationIntent && previousIdentity == null && frame.isEmptyStartPause()) {
                    if (pendingStartIdentity != null && pendingStartIdentity != identity) {
                        calibrationReplaced = true; clearCalibration()
                    } else pendingStartIdentity = identity
                }
                val newStartConfirmed = if (previousIdentity != null) identity != previousIdentity else
                    frame.status in setOf("COUNTDOWN", "CAPTURING", "FAILED") || frame.stage !in setOf("NEUTRAL", "READY")
                if (calibrationIntent && newStartConfirmed) {
                    awaitingStartEpoch = false
                    if (!frame.armed && frame.stage != "READY") {
                        confirmedIdentity = identity; confirmedStage = frame.stage
                    } else clearCalibration()
                }
            } else if (confirmedIdentity != identity) {
                calibrationReplaced = true; clearCalibration()
            }

            if (calibrationIntent && !awaitingStartEpoch) {
                if (frame.stage == "READY" || frame.armed) clearCalibration()
                else { confirmedStage = frame.stage; calibrationPaused = false }
            }
        }
        val retain = calibrationIntent && confirmedIdentity == identity && !frame.armed && frame.stage != "READY"
        return AirFrameContinuity(calibrationReplaced || wasArmed && !frame.armed || pendingArmLostTracking,
            retain && !pendingArmLostTracking, sendDisarm = pendingArmLostTracking || calibrationReplaced && frame.armed)
    }

    fun inputLost(kind: AirInputLoss): AirRecoveryDecision {
        val frame = latestFrame
        val transient = kind != AirInputLoss.INVALID_MESSAGE
        val pendingStart = calibrationIntent && awaitingStartEpoch && previousIdentity == null && authenticatedSession != null &&
            (frame == null || frame.sessionId == authenticatedSession && frame.isEmptyStartPause() &&
                frame.identity() == pendingStartIdentity)
        val confirmed = calibrationIntent && !awaitingStartEpoch && confirmedIdentity != null &&
            frame != null && confirmedIdentity == frame.identity() && !frame.armed && frame.stage != "READY"
        val retain = transient && (pendingStart || confirmed) && !armRequested && !observedArmed
        val disarm = !transient || armRequested || observedArmed
        if (retain) calibrationPaused = true else clearCalibration()
        if (disarm) armRequested = false
        return AirRecoveryDecision(retain, disarm)
    }

    private fun clearCalibration() {
        calibrationIntent = false; awaitingStartEpoch = false; previousIdentity = null
        pendingStartIdentity = null
        confirmedIdentity = null; confirmedStage = null; calibrationPaused = false
    }
    private fun AirFrame.identity() = Identity(sessionId, epoch)
    private fun AirFrame.isEmptyStartPause() = !armed && stage == "NEUTRAL" && status == "WAITING" &&
        fingers.isEmpty() && !mouse.active && !mouse.valid && !mouse.pressed && !mouse.dragging
}
