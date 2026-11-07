package com.pleos.airdesk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class AirFinger(val hand: String, val finger: String, val x: Float, val y: Float,
    val pressed: Boolean, val valid: Boolean) {
    val ownerId: String get() = "$hand.$finger"
}
data class AirMouse(val active: Boolean, val valid: Boolean, val x: Float, val y: Float,
    val pressed: Boolean, val dragging: Boolean)
data class AirFrame(val epoch: String, val stage: String, val status: String, val armed: Boolean,
    val fingers: List<AirFinger>, val mouse: AirMouse, val sessionId: String, val seq: Long, val receivedAtMs: Long)
enum class AirFingerState { LOST, MOUSE, BLOCKED, WAIT_NEUTRAL, HOVER, KEYDOWN, NO_TARGET, UNSUPPORTED }
data class AirTypingSnapshot(
    val enabled: Boolean = false,
    val ready: Boolean = false,
    val hoveredKeys: Map<String, String> = emptyMap(),
    val pressedKeyCodes: Set<String> = emptySet(),
    val status: String = "AIR DEMO · 보정 후 입력을 켜세요",
    val observedFingerCount: Int = 0,
    val detectedPressedCount: Int = 0,
    val rearmedOwners: Set<String> = emptySet(),
    val fingerStates: Map<String, AirFingerState> = emptyMap(),
)

/**
 * Local editor actions only. The caller first authenticates and validates complete v0.2 frames.
 * Every finger owns at most one key until release. Frame ordering is: modifier Downs, character
 * Downs in left/right + thumb/index/middle/ring/little order, then modifier Ups. A modifier released
 * in the same frame as a character remains effective for that character. No hold-repeat is generated.
 */
class AirTypingController(
    private val keyboard: DesktopKeyboardController,
    private val onChanged: (AirTypingSnapshot) -> Unit = {},
) {
    var snapshot by mutableStateOf(AirTypingSnapshot()); private set
    private var allowed = false
    private var localEnabled = false
    private var session: String? = null
    private var epoch: String? = null
    private var lastSequence = -1L
    private var lastReceive: Long? = null
    private var observedFocus = keyboard.focusedEditorId
    private val retiredSessions = linkedSetOf<String>()
    private val retiredEpochs = linkedSetOf<String>()
    private val neutral = mutableSetOf<String>()
    private val owned = linkedMapOf<String, String>()
    // Only the current held stroke's rejection reason survives; never keep a keystroke history.
    private val rejectedDown = mutableMapOf<String, AirFingerState>()
    private var releaseGeneration = 0L

    fun setAllowed(value: Boolean) {
        if (allowed == value) return
        allowed = value
        if (!value) cancel("전경/DEMO 조건 해제")
    }

    fun setEnabled(value: Boolean) {
        localEnabled = value && allowed
        release()
        publish(snapshot.copy(enabled = localEnabled, ready = false, pressedKeyCodes = emptySet(),
            rearmedOwners = emptySet(), fingerStates = snapshot.fingerStates.mapValues { (_, state) ->
                if (state in setOf(AirFingerState.LOST, AirFingerState.MOUSE)) state
                else if (localEnabled) AirFingerState.WAIT_NEUTRAL else AirFingerState.BLOCKED
            },
            status = if (localEnabled) "AIR DEMO · 손가락을 펴서 준비하세요" else "AIR DEMO · 입력 꺼짐"))
    }

    /** Global cancellation requires another explicit local enable. Identity/sequence history survives. */
    fun cancel(reason: String = "입력 해제") {
        localEnabled = false
        release()
        // Reasons are app-owned labels, not request content or typed text.
        publish(AirTypingSnapshot(status = "AIR DEMO · $reason"))
    }

    fun tick(nowMs: Long) {
        if (localEnabled && lastReceive?.let { nowMs < it || nowMs - it > MAX_AGE_MS } == true)
            cancel("손 추적 시간 초과")
    }

    fun accept(frame: AirFrame, nowMs: Long): Boolean {
        if (!valid(frame) || nowMs < frame.receivedAtMs || nowMs - frame.receivedAtMs > MAX_AGE_MS) {
            cancel("잘못되거나 오래된 손 프레임")
            return false
        }
        if (frame.sessionId in retiredSessions || (frame.sessionId == session && frame.epoch in retiredEpochs)) return false
        if (session == frame.sessionId && frame.seq <= lastSequence) return false
        if (session != frame.sessionId) {
            session?.let { rememberRetired(retiredSessions, it); localEnabled = false }
            session = frame.sessionId; epoch = null; lastSequence = -1
            retiredEpochs.clear(); release()
        }
        if (epoch != frame.epoch) {
            epoch?.let { rememberRetired(retiredEpochs, it); localEnabled = false }
            epoch = frame.epoch; release()
        }
        lastSequence = frame.seq
        lastReceive = frame.receivedAtMs
        val hover = frame.fingers.filter { it.valid && !(frame.mouse.active && it.hand == "right") }
            .mapNotNull { finger -> hit(finger)?.let { finger.ownerId to it } }.toMap()
        val focus = keyboard.focusedEditorId
        if (observedFocus != focus) { release(); observedFocus = focus }
        val canType = allowed && localEnabled && frame.armed && frame.stage == "READY" &&
            frame.status == "ARMED" && keyboard.visible && focus != null
        if (!canType) {
            release()
            publish(inputSnapshot(frame, false, hover, when {
                !allowed -> "AIR DEMO · 입력 잠금"
                !localEnabled -> "AIR DEMO · 입력 꺼짐 · 타건 켜기를 누르세요"
                !frame.armed -> "AIR DEMO · 보정/서버 입력 준비 대기"
                !keyboard.visible -> "AIR DEMO · 화면 키보드를 펼치세요"
                focus == null -> "AIR DEMO · 입력할 편집기를 선택하세요"
                else -> "AIR DEMO · 입력 준비 조건 대기"
            }))
            return true
        }

        val before = owned.toMap()
        val upcoming = linkedMapOf<String, String>()
        val released = mutableSetOf<String>()
        val cancelled = mutableSetOf<String>()
        val byOwner = frame.fingers.associateBy { it.ownerId }
        for (owner in (neutral + owned.keys + rejectedDown.keys + byOwner.keys).toSet()) {
            val finger = byOwner[owner]
            if (finger == null || !finger.valid || (frame.mouse.active && finger.hand == "right")) {
                neutral.remove(owner); released += owner; cancelled += owner
                rejectedDown.remove(owner)
                continue
            }
            if (!finger.pressed) {
                neutral += owner; released += owner; rejectedDown.remove(owner)
            } else if (owner !in owned && neutral.remove(owner)) {
                // Consume a Down even over a gap/disabled key: moving it while held cannot type.
                val code = hit(finger)
                when {
                    code == null -> rejectedDown[owner] = AirFingerState.NO_TARGET
                    !keyboard.enabled(code) -> rejectedDown[owner] = AirFingerState.UNSUPPORTED
                    else -> { rejectedDown.remove(owner); upcoming[owner] = code }
                }
            }
        }
        // A valid physical Up follows this frame's character Downs; tracking loss is cancellation
        // and takes effect first, so a lost Ctrl cannot turn another hand's character into a shortcut.
        val activeBefore = before.filterKeys { it !in cancelled }
        val modifierCodes = (activeBefore.values + upcoming.values).filter { it in MODIFIERS }.toSet()
        val shift = modifierCodes.any { it.startsWith("Shift") }
        val ctrl = modifierCodes.any { it.startsWith("Control") }
        val alt = modifierCodes.any { it.startsWith("Alt") }
        val alreadyHeld = activeBefore.values.toMutableSet()
        owned.putAll(upcoming)
        val dispatchGeneration = releaseGeneration
        for ((_, code) in upcoming.entries.sortedBy { ownerOrder(it.key) }) {
            if (code !in MODIFIERS && alreadyHeld.add(code)) {
                keyboard.pressAir(code, shift, ctrl, alt)
                // App callbacks (F1/Tab/Escape) can synchronously cancel or replace authority.
                // Do not dispatch the rest of this frame or restore a stale held snapshot.
                if (dispatchGeneration != releaseGeneration || !allowed || !localEnabled) return true
                if (keyboard.focusedEditorId != focus || !keyboard.visible) {
                    observedFocus = keyboard.focusedEditorId; release()
                    publish(inputSnapshot(frame, false, hover, "AIR DEMO · 편집기 변경 · 손가락을 펴세요"))
                    return true
                }
            }
        }
        released.forEach(owned::remove)
        val next = inputSnapshot(frame, true, hover, "")
        publish(next.copy(status = when {
            owned.isNotEmpty() -> "AIR DEMO · 키 누름 인식"
            next.ready -> "AIR DEMO · 입력 준비 · 키 위에서 손가락을 굽혔다 펴세요"
            AirFingerState.WAIT_NEUTRAL in next.fingerStates.values -> "AIR DEMO · 손가락을 펴서 준비하세요"
            AirFingerState.NO_TARGET in next.fingerStates.values -> "AIR DEMO · 손끝을 화면의 키 위로 이동하세요"
            AirFingerState.UNSUPPORTED in next.fingerStates.values -> "AIR DEMO · 지원하지 않는 키입니다"
            AirFingerState.MOUSE in next.fingerStates.values -> "AIR DEMO · 오른손 마우스 영역"
            else -> "AIR DEMO · 유효한 손끝을 기다립니다"
        }))
        return true
    }

    private fun inputSnapshot(frame: AirFrame, canType: Boolean, hover: Map<String, String>, status: String): AirTypingSnapshot {
        val states = frame.fingers.associate { finger -> finger.ownerId to when {
            frame.mouse.active && finger.hand == "right" -> AirFingerState.MOUSE
            !finger.valid -> AirFingerState.LOST
            !canType -> AirFingerState.BLOCKED
            finger.ownerId in owned -> AirFingerState.KEYDOWN
            finger.ownerId in rejectedDown -> rejectedDown.getValue(finger.ownerId)
            finger.pressed -> AirFingerState.WAIT_NEUTRAL
            finger.ownerId !in hover -> AirFingerState.NO_TARGET
            !keyboard.enabled(hover.getValue(finger.ownerId)) -> AirFingerState.UNSUPPORTED
            else -> AirFingerState.HOVER
        } }
        val ready = canType && (owned.isNotEmpty() || neutral.any { owner ->
            hover[owner]?.let(keyboard::enabled) == true
        })
        return AirTypingSnapshot(localEnabled, ready, hover, owned.values.toSet(), status,
            frame.fingers.count { it.valid }, frame.fingers.count { it.valid && it.pressed },
            neutral.toSet(), states)
    }
    private fun release() { releaseGeneration++; owned.clear(); neutral.clear(); rejectedDown.clear() }
    private fun publish(value: AirTypingSnapshot) { snapshot = value; onChanged(value) }
    private fun hit(finger: AirFinger): String? {
        val x = finger.x * DesktopKeyboardGeometry.WIDTH
        val y = finger.y * DesktopKeyboardGeometry.HEIGHT
        return DesktopKeyboardGeometry.keys.firstOrNull { p ->
            x >= p.bounds.left && x < p.bounds.right && y >= p.bounds.top && y < p.bounds.bottom
        }?.key?.code
    }
    private fun valid(frame: AirFrame): Boolean = frame.sessionId.isNotBlank() && frame.sessionId.length <= 128 &&
        frame.epoch.isNotBlank() && frame.epoch.length <= 128 && frame.seq >= 0 && frame.receivedAtMs >= 0 &&
        frame.fingers.size <= 10 && frame.fingers.map { it.ownerId }.toSet().size == frame.fingers.size &&
        frame.fingers.all { it.hand in HANDS && it.finger in FINGERS && normalized(it.x) && normalized(it.y) &&
            !(frame.mouse.active && it.hand == "right" && it.valid) } &&
        normalized(frame.mouse.x) && normalized(frame.mouse.y) &&
        (!frame.mouse.dragging || frame.mouse.pressed) && (!frame.mouse.pressed || frame.mouse.active && frame.mouse.valid) &&
        (!frame.armed || frame.stage == "READY" && frame.status == "ARMED")

    private fun normalized(value: Float) = value.isFinite() && value in 0f..1f
    private fun ownerOrder(owner: String): Int {
        val parts = owner.split('.')
        return HANDS.indexOf(parts[0]) * 5 + FINGERS.indexOf(parts[1])
    }
    private fun rememberRetired(values: LinkedHashSet<String>, value: String) {
        values += value
        while (values.size > 16) values.remove(values.first())
    }
    companion object {
        const val MAX_AGE_MS = 250L
        private val HANDS = listOf("left", "right")
        private val FINGERS = listOf("thumb", "index", "middle", "ring", "little")
        private val MODIFIERS = setOf("ShiftLeft", "ShiftRight", "ControlLeft", "ControlRight", "AltLeft", "AltRight")
    }
}
