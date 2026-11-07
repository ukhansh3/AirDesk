package com.pleos.airdesk

import kotlin.math.hypot
import kotlin.math.min

data class UiPoint(val x: Float, val y: Float)

/** Coordinates are physical pixels in the same measured Compose root. */
data class UiBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val valid: Boolean get() = listOf(left, top, right, bottom).all(Float::isFinite) && width >= 1f && height >= 1f
    fun contains(point: UiPoint): Boolean = valid && point.x >= left && point.x < right && point.y >= top && point.y < bottom
    fun map(x: Float, y: Float): UiPoint? = if (!valid || !x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) null
        else UiPoint(left + x * (width - 1f), top + y * (height - 1f))
}

/** Only explicitly registered AirDesk callbacks are dispatchable. No synthetic platform events. */
data class GestureUiTarget(
    val id: String,
    val bounds: UiBounds,
    val layer: Int = 0,
    val enabled: Boolean = true,
    val isModeControl: Boolean = false,
    val onClick: (() -> Unit)? = null,
    val onContext: (() -> Unit)? = null,
    val onDragStart: ((UiPoint) -> Unit)? = null,
    val onDrag: ((UiPoint) -> Unit)? = null,
    val onDragEnd: ((Boolean) -> Unit)? = null,
    val onScroll: ((Float) -> Unit)? = null,
    val parentId: String? = null,
)

enum class GestureMouseMode { LEFT, RIGHT_ONCE, SCROLL }
enum class UiPointerPhase { IDLE, HOVER, PRESSED, DRAGGING, SCROLLING, PENDING_RELEASE, LOST }
enum class UiInteractionScope { DIAGNOSTIC, DEMO, OPERATIONAL }

data class UiGestureSnapshot(
    val cursor: UiPoint? = null,
    val hoveredId: String? = null,
    val pressedId: String? = null,
    val capturedId: String? = null,
    val mode: GestureMouseMode = GestureMouseMode.LEFT,
    val enabled: Boolean = false,
    val phase: UiPointerPhase = UiPointerPhase.IDLE,
    val status: String = "DEMO 손동작 입력 꺼짐",
)

data class ValidatedGestureHint(
    val type: String,
    val sessionId: String,
    val sequence: Long,
    val timestampMs: Long,
    val receivedAtMs: Long,
    val x: Float? = null,
    val y: Float? = null,
    val confidence: Float? = null,
)

sealed interface DiagnosticGestureSignal {
    data class Hint(val value: ValidatedGestureHint) : DiagnosticGestureSignal
    data class Cancel(val reason: String) : DiagnosticGestureSignal
}

/** Main-thread event delivery; unlike StateFlow this never conflates start/release/loss events. */
class DiagnosticGestureBus {
    private val observers = linkedSetOf<(DiagnosticGestureSignal) -> Unit>()
    fun observe(observer: (DiagnosticGestureSignal) -> Unit): () -> Unit {
        observers.add(observer)
        return { observers.remove(observer) }
    }
    fun emit(signal: DiagnosticGestureSignal) {
        for (observer in observers.toList()) {
            try { observer(signal) }
            catch (_: Exception) {
                observers.remove(observer)
                runCatching { observer(DiagnosticGestureSignal.Cancel("gesture_observer_failure")) }
                observers.toList().forEach { runCatching { it(DiagnosticGestureSignal.Cancel("gesture_observer_failure")) } }
                return
            }
        }
    }
}

data class GestureUiConfig(
    val minimumPinchMs: Long = 80,
    val clickSlopDp: Float = 12f,
    val clickDebounceMs: Long = 250,
    val staleMs: Long = 750,
    val maximumPinchMs: Long = 5000,
    val minimumScrollIntervalMs: Long = 16,
    val maximumScrollDpPerEvent: Float = 48f,
    val maximumScrollDpPerSecond: Float = 1200f,
) {
    init {
        require(minimumPinchMs >= 0 && maximumPinchMs >= minimumPinchMs)
        require(clickDebounceMs >= 0 && staleMs > 0 && minimumScrollIntervalMs > 0)
        require(clickSlopDp.isFinite() && clickSlopDp >= 0f)
        require(maximumScrollDpPerEvent.isFinite() && maximumScrollDpPerEvent > 0f)
        require(maximumScrollDpPerSecond.isFinite() && maximumScrollDpPerSecond > 0f)
    }
}

/** Pure state machine for the isolated local DEMO. Operational authority has no issuer here. */
class GestureUiRouter(
    private val onChanged: () -> Unit = {},
    private val onCancelled: (String) -> Unit = {},
    private val config: GestureUiConfig = GestureUiConfig(),
) {
    private data class Press(val target: String, val point: UiPoint, val startedMs: Long, val mode: GestureMouseMode,
        val geometry: UiBounds, var maximumDistance: Float = 0f, var dragging: Boolean = false,
        var scrolling: Boolean = false, var lastScrollPoint: UiPoint = point, var lastScrollMs: Long = startedMs,
        var endDrag: ((Boolean) -> Unit)? = null, var lastDragPoint: UiPoint = point)
    private data class Release(val press: Press, val point: UiPoint, val releasedMs: Long, val geometry: UiBounds) {
        val target: String get() = press.target
        val mode: GestureMouseMode get() = press.mode
    }
    private val targets = linkedMapOf<String, GestureUiTarget>()
    private var viewport = UiBounds(0f, 0f, 0f, 0f)
    private var density = 1f
    private var demoEnabled = false
    private var foreground = false
    private var scope = UiInteractionScope.DEMO
    private var safety = VehicleSafety.UNKNOWN
    private var session: String? = null
    private var sequence = -1L
    private var producerTime = -1L
    private var lastReceiveMs = -1L
    private var press: Press? = null
    private var pending: Release? = null
    private var lastClickMs = Long.MIN_VALUE
    var snapshot = UiGestureSnapshot()
        private set

    private fun allowed() = scope == UiInteractionScope.DEMO && demoEnabled && foreground && viewport.valid &&
        safety in setOf(VehicleSafety.UNKNOWN, VehicleSafety.PARKED_CONFIRMED)

    fun setViewport(bounds: UiBounds, density: Float = 1f) {
        require(density.isFinite() && density > 0f)
        if (viewport == bounds && this.density == density) return
        viewport = bounds; this.density = density
        cancel("작업 영역 변경")
    }
    fun setDemoEnabled(enabled: Boolean) {
        if (demoEnabled == enabled) return
        demoEnabled = enabled
        if (!enabled) cancel("DEMO 손동작 입력 꺼짐") else update(snapshot.copy(enabled = allowed(), status = "DEMO 손동작 입력 준비"))
    }
    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (!value) { demoEnabled = false; cancel("앱 일시 중지") }
        else update(snapshot.copy(enabled = allowed()))
    }
    fun setScope(value: UiInteractionScope) {
        if (scope == value) return
        scope = value; demoEnabled = false; cancel("입력 영역 변경 · 운영 입력 잠금")
    }
    fun setVehicleSafety(value: VehicleSafety) {
        if (safety == value) return
        safety = value
        if (value !in setOf(VehicleSafety.UNKNOWN, VehicleSafety.PARKED_CONFIRMED)) {
            demoEnabled = false; cancel("차량 안전 상태 잠금")
        } else update(snapshot.copy(enabled = allowed()))
    }
    fun setMode(mode: GestureMouseMode) {
        if (snapshot.mode == mode) return
        cancel("마우스 모드 변경")
        update(snapshot.copy(mode = mode, enabled = allowed(), status = "DEMO ${mode.name}"))
    }

    fun register(target: GestureUiTarget) {
        require(target.id.isNotBlank())
        val previous = targets.put(target.id, target)
        val dependent = press ?: pending?.press
        if ((!target.enabled || !target.bounds.valid) && dependent != null && isDescendant(dependent.target, target.id))
            cancel("상위 타깃 잠금")
        if (press?.target == target.id && (!target.enabled || !target.bounds.valid ||
                previous?.isModeControl != target.isModeControl || previous?.parentId != target.parentId || press?.dragging == true && target.onDrag == null ||
                press?.scrolling == true && target.onScroll == null)) cancel("입력 타깃 변경")
        if (press?.target == target.id && press?.dragging == false && previous?.bounds != target.bounds) cancel("누른 타깃 이동")
        if (pending?.target == target.id && (pending?.press?.dragging == false && pending?.geometry != target.bounds ||
                !target.enabled || previous?.parentId != target.parentId)) cancel("해제 타깃 변경")
        val structuralChange = previous == null || previous.bounds != target.bounds || previous.layer != target.layer ||
            previous.enabled != target.enabled || previous.parentId != target.parentId
        val captured = press ?: pending?.press
        if (structuralChange && captured != null && (captured.dragging || captured.scrolling)) {
            val owner = targets[captured.target]
            val point = snapshot.cursor
            if (owner != null && point != null && target.id != owner.id && target.layer > owner.layer &&
                target.bounds.contains(point) && !isDescendant(target.id, owner.id) && !isDescendant(owner.id, target.id))
                cancel("드래그 타깃 가림")
        }
        update(snapshot.copy(hoveredId = hit(snapshot.cursor)?.id))
    }
    fun unregister(id: String) {
        val owned = press?.let { isDescendant(it.target, id) } == true || pending?.let { isDescendant(it.target, id) } == true
        targets.remove(id)
        if (press?.target == id || pending?.target == id || owned) cancel("입력 타깃 제거")
        else update(snapshot.copy(hoveredId = hit(snapshot.cursor)?.id))
    }

    fun cancel(reason: String = "입력 취소") {
        val active = press ?: pending?.press
        press = null; pending = null
        if (active?.dragging == true) runCatching { active.endDrag?.invoke(true) }
        update(UiGestureSnapshot(enabled = allowed(), phase = UiPointerPhase.LOST, status = reason))
        runCatching { onCancelled(reason) }
    }

    fun tick(nowMs: Long) {
        if (lastReceiveMs >= 0 && nowMs < lastReceiveMs) { cancel("수신 시계 역행"); return }
        if (lastReceiveMs >= 0 && nowMs - lastReceiveMs > config.staleMs &&
            (snapshot.cursor != null || press != null || pending != null)) cancel("손동작 시간 초과")
        if (press?.let { nowMs - it.startedMs > config.maximumPinchMs } == true) cancel("Pinch 유지 시간 초과")
        if (pending?.let { nowMs - it.releasedMs > config.staleMs } == true) cancel("클릭 확인 시간 초과")
    }

    fun accept(hint: ValidatedGestureHint, nowMs: Long): Boolean {
        if (!allowed()) return false
        if (hint.sequence < 0 || hint.timestampMs < 0 || hint.receivedAtMs < 0 ||
            nowMs - hint.receivedAtMs !in 0..config.staleMs) return reject("오래된 손동작")
        if (lastReceiveMs >= 0 && hint.receivedAtMs < lastReceiveMs) return reject("수신 시계 역행")
        if (hint.sessionId != session) {
            // The user's explicit mode may be selected before the first camera frame. Only
            // replacing an already-bound source invalidates that choice and held state.
            if (session != null) cancel("새 손동작 세션")
            session = hint.sessionId; sequence = -1; producerTime = -1
        }
        if (hint.sequence <= sequence || hint.timestampMs < producerTime) return false
        if (hint.type == "tracking.lost") {
            sequence = hint.sequence; producerTime = hint.timestampMs; lastReceiveMs = hint.receivedAtMs
            cancel("손 추적 해제")
            return true
        }
        if (hint.type !in setOf("gesture.move", "gesture.pinch_start", "gesture.pinch_end", "gesture.drag_start", "gesture.drag_move")) return false
        val point = viewport.map(hint.x ?: return reject("좌표 없음"), hint.y ?: return reject("좌표 없음")) ?: return reject("좌표 범위 오류")
        val confidence = hint.confidence ?: return reject("신뢰도 없음")
        if (!confidence.isFinite() || confidence !in 0f..1f) return reject("신뢰도 범위 오류")
        sequence = hint.sequence; producerTime = hint.timestampMs; lastReceiveMs = hint.receivedAtMs
        if (confidence <= 0f) { cancel("추적 신뢰도 해제"); return true }
        update(snapshot.copy(cursor = point, hoveredId = hit(point)?.id, enabled = allowed()))
        press?.let { it.maximumDistance = maxOf(it.maximumDistance, hypot(point.x - it.point.x, point.y - it.point.y)) }
        when (hint.type) {
            "gesture.pinch_start" -> begin(point, nowMs)
            "gesture.drag_start" -> startDrag(point, nowMs)
            "gesture.drag_move" -> drag(point, nowMs)
            "gesture.pinch_end" -> release(point, nowMs)
            "gesture.move" -> {
                if (press?.dragging == true || press?.scrolling == true) drag(point, nowMs)
                else if (pending != null) confirmRelease(point, nowMs)
                else if (press == null) update(snapshot.copy(phase = UiPointerPhase.HOVER))
            }
        }
        return true
    }

    private fun reject(reason: String): Boolean { cancel(reason); return false }
    private fun begin(point: UiPoint, nowMs: Long) {
        if (press != null || pending != null) { cancel("중복 Pinch 시작"); return }
        val target = hit(point)?.takeIf { it.enabled && it.bounds.valid } ?: return
        val mode = if (target.isModeControl) GestureMouseMode.LEFT else snapshot.mode
        press = Press(target.id, point, nowMs, mode, target.bounds)
        update(snapshot.copy(pressedId = target.id, phase = UiPointerPhase.PRESSED))
    }
    private fun startDrag(point: UiPoint, nowMs: Long) {
        val active = press ?: return
        if (active.dragging || active.scrolling) return
        pending = null
        val target = targets[active.target]?.takeIf { it.enabled } ?: run { cancel("드래그 타깃 없음"); return }
        if (active.mode == GestureMouseMode.SCROLL && target.onScroll != null) {
            active.scrolling = true; active.lastScrollPoint = active.point; active.lastScrollMs = active.startedMs
            update(snapshot.copy(capturedId = target.id, phase = UiPointerPhase.SCROLLING)); drag(point, nowMs)
        } else if (active.mode == GestureMouseMode.LEFT && target.onDrag != null) {
            active.dragging = true
            active.endDrag = target.onDragEnd
            active.lastDragPoint = point
            update(snapshot.copy(capturedId = target.id, phase = UiPointerPhase.DRAGGING))
            action { target.onDragStart?.invoke(active.point); target.onDrag.invoke(point) }
        } else {
            // A drag on a non-draggable click target can never become a later click.
            cancel("해당 타깃 드래그 미지원")
        }
    }
    private fun drag(point: UiPoint, nowMs: Long) {
        val active = press ?: return
        val target = targets[active.target]?.takeIf { it.enabled } ?: run { cancel("드래그 타깃 해제"); return }
        if (active.scrolling) {
            val elapsed = nowMs - active.lastScrollMs
            if (elapsed < config.minimumScrollIntervalMs) return
            val limit = min(config.maximumScrollDpPerEvent * density, config.maximumScrollDpPerSecond * density * elapsed / 1000f)
            val delta = (active.lastScrollPoint.y - point.y).coerceIn(-limit, limit)
            active.lastScrollPoint = point; active.lastScrollMs = nowMs
            if (delta != 0f) action { target.onScroll?.invoke(delta) }
        } else if (active.dragging && active.lastDragPoint != point) {
            active.lastDragPoint = point
            action { target.onDrag?.invoke(point) }
        }
    }
    private fun release(point: UiPoint, nowMs: Long) {
        val active = press ?: return
        press = null
        if (active.dragging || active.scrolling) {
            pending = Release(active, point, nowMs, targets[active.target]?.bounds ?: active.geometry)
            update(snapshot.copy(pressedId = null, phase = UiPointerPhase.PENDING_RELEASE))
            return
        }
        val target = targets[active.target]
        if (target?.enabled != true || !target.bounds.contains(point) || target.bounds != active.geometry ||
            nowMs - active.startedMs !in config.minimumPinchMs..config.maximumPinchMs || active.maximumDistance > config.clickSlopDp * density) {
            update(snapshot.copy(pressedId = null, phase = UiPointerPhase.HOVER)); return
        }
        // Phase 1 emits pinch_end immediately before tracking.lost on disappearance. A later valid
        // hand move confirms natural release; loss/timeout/background instead cancels this candidate.
        pending = Release(active, point, nowMs, active.geometry)
        update(snapshot.copy(pressedId = null, phase = UiPointerPhase.PENDING_RELEASE))
    }
    private fun confirmRelease(point: UiPoint, nowMs: Long) {
        val released = pending ?: return
        pending = null
        val target = targets[released.target]
        update(snapshot.copy(capturedId = null, phase = UiPointerPhase.HOVER))
        if (released.press.dragging || released.press.scrolling) {
            if (released.press.dragging) action { released.press.endDrag?.invoke(target?.enabled != true || nowMs - released.releasedMs > config.staleMs) }
            return
        }
        if (target?.enabled != true || target.bounds != released.geometry || !target.bounds.contains(point) ||
            hypot(point.x - released.point.x, point.y - released.point.y) > config.clickSlopDp * density ||
            nowMs - released.releasedMs > config.staleMs ||
            (lastClickMs != Long.MIN_VALUE && nowMs - lastClickMs < config.clickDebounceMs)) return
        if (hit(point)?.id != released.target) { cancel("클릭 타깃 가림"); return }
        if (released.mode == GestureMouseMode.RIGHT_ONCE) {
            update(snapshot.copy(mode = GestureMouseMode.LEFT, status = if (target.onContext != null) "DEMO 우클릭" else "우클릭 미지원 타깃"))
            if (target.onContext != null) { lastClickMs = nowMs; action { target.onContext.invoke() } }
        } else if (target.onClick != null) {
            lastClickMs = nowMs
            action { target.onClick.invoke() }
        }
    }
    private fun hit(point: UiPoint?): GestureUiTarget? {
        if (point == null) return null
        val ordered = targets.values.filter { it.bounds.contains(point) }
            .sortedWith(compareByDescending<GestureUiTarget> { it.layer }.thenBy { it.bounds.width * it.bounds.height })
        val top = ordered.firstOrNull() ?: return null
        // Disabled keys/overlays occlude parents; mode controls remain usable to leave either mode.
        if (!top.enabled) return top
        val chain = mutableListOf(top)
        val visited = mutableSetOf(top.id)
        var current = top
        while (current.parentId != null) {
            val parentId = current.parentId!!
            if (!visited.add(parentId)) return null
            val parent = targets[parentId] ?: return null
            if (!parent.bounds.contains(point)) return null
            if (!parent.enabled) return parent
            chain.add(parent); current = parent
        }
        if (top.isModeControl) return top
        return when (snapshot.mode) {
            GestureMouseMode.SCROLL -> chain.firstOrNull { it.onScroll != null } ?: top
            GestureMouseMode.RIGHT_ONCE -> chain.firstOrNull { it.onContext != null } ?: top
            GestureMouseMode.LEFT -> top
        }
    }
    private fun isDescendant(id: String, ancestor: String): Boolean {
        val visited = mutableSetOf<String>()
        var parent = targets[id]?.parentId
        while (parent != null) {
            if (parent == ancestor) return true
            if (!visited.add(parent)) return false
            parent = targets[parent]?.parentId
        }
        return false
    }
    private fun update(value: UiGestureSnapshot) { if (snapshot != value) { snapshot = value; onChanged() } }
    private fun action(callback: () -> Unit) { try { callback() } catch (_: Exception) { cancel("타깃 처리 실패") } }
}
