package com.pleos.airdesk

import org.junit.Assert.*
import org.junit.Test

class AirCalibrationGuideTest {
    private fun frame(stage: String, status: String) = AirFrame("epoch", stage, status, false,
        listOf("left", "right").flatMap { side -> listOf("thumb", "index", "middle", "ring", "little")
            .map { finger -> AirFinger(side, finger, .3f, .4f, false, true) } },
        AirMouse(false, false, .5f, .5f, false, false), "session", 1, 1000)
    @Test fun idleDoesNotPretendAutomaticCalibrationIsRunning() {
        assertTrue(AirCalibrationGuide.prompt(null, false).title.contains("연결"))
        assertTrue(AirCalibrationGuide.prompt(frame("NEUTRAL", "WAITING"), false).title.contains("시작"))
    }
    @Test fun allElevenPosesHaveOrderedProgressAndNoRepeatedClickInstruction() {
        val stages = listOf("NEUTRAL") + listOf("LEFT", "RIGHT").flatMap { side ->
            listOf("THUMB", "INDEX", "MIDDLE", "RING", "LITTLE").map { "${side}_$it" }
        }
        stages.forEachIndexed { index, stage ->
            val p = AirCalibrationGuide.prompt(frame(stage, "WAITING"), true)
            assertEquals(index, p.completedSteps)
            assertEquals(11, p.totalSteps)
            assertTrue(p.title.startsWith("${index + 1}/11"))
            assertFalse(p.detail.contains("버튼"))
        }
        assertTrue(AirCalibrationGuide.prompt(frame("LEFT_INDEX", "WAITING"), true).detail.contains("클릭할 필요 없습니다"))
    }
    @Test fun intermediateCountdownRequiresNeutralBeforeTheNextStroke() {
        val p = AirCalibrationGuide.prompt(frame("LEFT_INDEX", "COUNTDOWN"), true)
        assertTrue(p.detail.contains("기본 자세로 되돌리세요"))
        assertFalse(p.detail.contains("아래로"))
        assertTrue(AirCalibrationGuide.prompt(frame("NEUTRAL", "COUNTDOWN"), true).detail.contains("5초"))
    }
    @Test fun failedPoseRetriesAndReadyNeverPromisesAutomaticTyping() {
        assertTrue(AirCalibrationGuide.prompt(frame("RIGHT_LITTLE", "FAILED"), true).detail.contains("다시"))
        val done = AirCalibrationGuide.prompt(frame("READY", "READY"), true)
        assertEquals(11, done.completedSteps)
        assertTrue(done.detail.contains("타건 켜기"))
        assertFalse(done.detail.contains("타건 ON"))
    }
    @Test fun delayedOldReadyFrameCannotFinishANewCalibrationRequest() {
        val old = frame("READY", "READY")
        val pending = AirCalibrationGuide.prompt(old, true, old.epoch)
        assertEquals(0, pending.completedSteps)
        assertTrue(pending.title.contains("시작 중"))
        assertFalse(pending.title.contains("보정 완료"))
        assertTrue(AirCalibrationGuide.prompt(old.copy(epoch = "new-epoch"), true, old.epoch).title.contains("완료"))
    }
    @Test fun missingHandShowsTheBlockInsteadOfAskingForMoreClicks() {
        val missing = frame("LEFT_INDEX", "WAITING").copy(fingers = emptyList())
        val prompt = AirCalibrationGuide.prompt(missing, true)
        assertTrue(prompt.detail.contains("손 0/2"))
        assertTrue(prompt.detail.contains("기다리는 중"))
        assertFalse(prompt.detail.contains("버튼"))
    }
}
