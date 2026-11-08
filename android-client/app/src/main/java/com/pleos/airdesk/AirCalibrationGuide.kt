package com.pleos.airdesk

data class AirCalibrationPrompt(val title: String, val detail: String, val completedSteps: Int, val totalSteps: Int = 11)

/** Presentation of existing calibration states; neither timers nor this UI advance the recognizer. */
object AirCalibrationGuide {
    private val stages = listOf("NEUTRAL") + listOf("LEFT", "RIGHT").flatMap { side ->
        listOf("THUMB", "INDEX", "MIDDLE", "RING", "LITTLE").map { "${side}_$it" }
    }
    fun prompt(frame: AirFrame?, running: Boolean, previousEpoch: String? = null,
               retainedStage: String? = null, paused: Boolean = false): AirCalibrationPrompt {
        if (frame == null) {
            val index = stages.indexOf(retainedStage)
            if (running && paused && index >= 0) return AirCalibrationPrompt(
                "${index + 1}/11 · ${pose(retainedStage!!)}",
                "카메라 신호 일시 대기 · 완료한 보정 단계 유지 · 신호가 돌아오면 같은 단계에서 계속합니다", index)
            if (running && paused && retainedStage == null) return AirCalibrationPrompt(
                "자동 보정 시작 응답 대기", "카메라 신호를 기다리고 있습니다 · 확인된 단계가 올 때까지 입력은 해제됩니다", 0)
            return AirCalibrationPrompt("카메라 연결을 확인하세요", "연결 후 자동 보정 시작을 한 번 누르세요", 0)
        }
        if (running && previousEpoch != null && frame.epoch == previousEpoch)
            return AirCalibrationPrompt("자동 보정 시작 중", "곧 5초 준비 시간이 시작됩니다 · 양손을 기본 자세로 가져오세요", 0)
        if (frame.stage == "READY") return AirCalibrationPrompt("보정 완료 · 11/11", "메모 입력 선택 → 타건 켜기 · 기준 자세로 돌아온 뒤 입력", 11)
        val index = stages.indexOf(frame.stage).coerceAtLeast(0)
        if (!running) return AirCalibrationPrompt("자동 보정 시작을 누르세요", "단계마다 클릭하지 않고 양손 자세만 바꾸면 됩니다", index)
        val pose = pose(frame.stage)
        val heading = "${index + 1}/11 · $pose"
        val visibleHands = frame.fingers.filter { it.valid }.map { it.hand }.toSet().size
        val visibleFingers = frame.fingers.count { it.valid }
        if (frame.status != "COUNTDOWN" && (visibleHands < 2 || visibleFingers < 10)) return AirCalibrationPrompt(heading,
            "손 $visibleHands/2 · 손끝 $visibleFingers/10 감지 · 카메라 신호와 양손 인식 대기 · 완료한 단계는 유지하며 자동으로 기다리는 중입니다", index)
        val detail = when (frame.status) {
            "COUNTDOWN" -> if (frame.stage == "NEUTRAL") "5초 준비 시간 · 양손을 카메라 앞으로 가져오고 기본 자세를 유지하세요"
                else "손가락을 기본 자세로 되돌리세요 · 안정되면 다음 동작을 안내합니다"
            "CAPTURING" -> "이 자세를 잠깐 유지하세요 · 안정되면 자동으로 다음 단계로 이동합니다"
            "FAILED" -> "동작이 구분되지 않았어요 · 기본 자세로 돌아왔다가 안내한 손가락만 다시 움직이세요"
            else -> if (frame.stage == "NEUTRAL") "양손을 편안한 기본 자세로 유지하세요"
                else "이 손가락만 키를 누르듯 아래로 움직여 잠깐 유지하세요 · 클릭할 필요 없습니다"
        }
        return AirCalibrationPrompt(heading, detail, index)
    }
    private fun pose(stage: String): String {
        if (stage == "NEUTRAL") return "양손 기본 자세"
        val side = if (stage.startsWith("LEFT_")) "왼손" else "오른손"
        val finger = when (stage.substringAfter('_')) {
            "THUMB" -> "엄지"; "INDEX" -> "검지"; "MIDDLE" -> "중지"; "RING" -> "약지"; else -> "새끼손가락"
        }
        return "$side $finger"
    }
}
