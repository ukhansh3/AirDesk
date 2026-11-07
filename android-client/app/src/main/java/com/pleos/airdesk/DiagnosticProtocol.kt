package com.pleos.airdesk

import org.json.JSONObject

enum class GesturePhase { IDLE, TRACKING, PINCHED, DRAGGING, LOST }

data class DiagnosticUiState(
    val status: String = "연결 안 됨",
    val authorized: Boolean = false,
    val sourceSessionId: String? = null,
    val cursorVisible: Boolean = false,
    val handDetected: Boolean = false,
    val gesturePhase: GesturePhase = GesturePhase.IDLE,
    val x: Float = .5f,
    val y: Float = .5f,
    val confidence: Float = 0f,
    val lastSequence: Long = -1L,
)

private data class Envelope(
    val type: String,
    val sessionId: String,
    val sequence: Long,
    val timestampMs: Long,
    val payload: JSONObject,
)

/** Strict read-only reducer for the canonical diagnostic and gesture schemas. */
class DiagnosticMessageReducer(private val schema: DiagnosticSchema) {
    private var clockOffsetMs = 0L
    private var lastTimestampMs = -1L
    var state: DiagnosticUiState = DiagnosticUiState()
        private set
    var lastAcceptedHint: ValidatedGestureHint? = null
        private set

    fun reset(status: String = "연결 안 됨"): DiagnosticUiState {
        lastTimestampMs = -1L
        lastAcceptedHint = null
        state = DiagnosticUiState(status = status)
        return state
    }

    fun awaitingConnection(status: String): DiagnosticUiState = reset(status)

    fun consume(text: String, nowMs: Long = System.nanoTime() / 1_000_000): DiagnosticUiState? {
        lastAcceptedHint = null
        if (text.toByteArray(Charsets.UTF_8).size > 8192) return null
        val envelope = parseEnvelope(text) ?: return null
        if (state.authorized) {
            val ageMs = nowMs - clockOffsetMs - envelope.timestampMs
            if (envelope.timestampMs < lastTimestampMs || ageMs > 750 || ageMs < -250) return null
        }
        val next = when (envelope.type) {
            "auth.accepted" -> acceptAuth(envelope)
            "auth.rejected" -> rejectAuth(envelope)
            else -> acceptAuthorized(envelope)
        } ?: return null
        if (envelope.type == "auth.accepted") clockOffsetMs = nowMs - envelope.timestampMs
        lastTimestampMs = envelope.timestampMs
        state = next
        if (envelope.type.startsWith("gesture.") || envelope.type == "tracking.lost") {
            lastAcceptedHint = ValidatedGestureHint(envelope.type, envelope.sessionId, envelope.sequence,
                envelope.timestampMs, nowMs, if (envelope.type == "tracking.lost") null else next.x,
                if (envelope.type == "tracking.lost") null else next.y,
                if (envelope.type == "tracking.lost") null else next.confidence)
        }
        return next
    }

    fun trackingTimedOut(expectedSequence: Long): DiagnosticUiState? {
        lastAcceptedHint = null
        if (!state.authorized || state.lastSequence != expectedSequence || !state.cursorVisible) return null
        state = state.copy(
            status = "DEMO 연결됨 · 손 추적 시간 초과",
            cursorVisible = false,
            handDetected = false,
            gesturePhase = GesturePhase.LOST,
        )
        return state
    }

    private fun acceptAuth(event: Envelope): DiagnosticUiState? {
        if (state.authorized || event.payload.keysSet() != setOf("mode") ||
            event.payload.strictString("mode") != "diagnostic_only") return null
        return DiagnosticUiState(
            status = "DEMO 연결됨 (PC 제어 불가)",
            authorized = true,
            sourceSessionId = event.sessionId,
            lastSequence = event.sequence,
        )
    }

    private fun rejectAuth(event: Envelope): DiagnosticUiState? {
        if (state.authorized) return null
        if (event.payload.keysSet() != setOf("errorCode")) return null
        val code = event.payload.strictString("errorCode") ?: return null
        if (code !in setOf("UNAUTHENTICATED", "INVALID_VERSION", "RATE_LIMITED")) return null
        return DiagnosticUiState(status = "진단 인증 거부: $code")
    }

    private fun acceptAuthorized(event: Envelope): DiagnosticUiState? {
        if (!state.authorized || event.sessionId != state.sourceSessionId ||
            event.sequence <= state.lastSequence) return null
        return when (event.type) {
            "gesture.move" -> gesture(event, if (state.gesturePhase in setOf(GesturePhase.PINCHED, GesturePhase.DRAGGING)) state.gesturePhase else GesturePhase.TRACKING)
            "gesture.pinch_start" -> gesture(event, GesturePhase.PINCHED)
            "gesture.pinch_end" -> gesture(event, GesturePhase.TRACKING)
            "gesture.drag_start", "gesture.drag_move" -> gesture(event, GesturePhase.DRAGGING)
            "tracking.lost" -> trackingLost(event)
            "session.locked" -> terminalControl(
                event,
                setOf("UNSAFE_VEHICLE", "AUTH_REVOKED", "APP_BACKGROUND", "STALE_HEARTBEAT", "NETWORK_FAILURE"),
                "진단 화면 잠금",
            )
            "session.closed" -> terminalControl(
                event,
                setOf("USER_REQUEST", "EXPIRED", "ERROR", "REVOKED", "SAFETY_LOCK"),
                "진단 세션 종료",
            )
            else -> null
        }
    }

    private fun gesture(event: Envelope, phase: GesturePhase): DiagnosticUiState? {
        if (event.payload.keysSet() != setOf("x", "y", "confidence")) return null
        val x = event.payload.strictDouble("x") ?: return null
        val y = event.payload.strictDouble("y") ?: return null
        val confidence = event.payload.strictDouble("confidence") ?: return null
        if (!x.isFinite() || !y.isFinite() || !confidence.isFinite() ||
            x !in 0.0..1.0 || y !in 0.0..1.0 || confidence !in 0.0..1.0) return null
        return state.copy(
            status = "DEMO 연결됨 · 손 인식",
            x = x.toFloat(), y = y.toFloat(), confidence = confidence.toFloat(),
            cursorVisible = true, handDetected = true,
            gesturePhase = phase, lastSequence = event.sequence,
        )
    }

    private fun trackingLost(event: Envelope): DiagnosticUiState? {
        if (event.payload.keysSet() != setOf("reason")) return null
        val reason = event.payload.strictString("reason") ?: return null
        if (reason !in setOf(
                "hand_not_detected", "camera_disconnected", "capture_failed",
                "model_failed", "tracking_timeout",
            )) return null
        return state.copy(
            status = "DEMO 연결됨 · 손 인식 안 됨 ($reason)",
            cursorVisible = false, handDetected = false,
            gesturePhase = GesturePhase.LOST, lastSequence = event.sequence,
        )
    }

    private fun terminalControl(event: Envelope, reasons: Set<String>, status: String): DiagnosticUiState? {
        if (event.payload.keysSet() != setOf("reason") ||
            event.payload.strictString("reason") !in reasons) return null
        return DiagnosticUiState(status = status)
    }

    private fun parseEnvelope(text: String): Envelope? {
        return try {
        val json = StrictJson.objectValue(text)
        if (!schema.accepts(json)) return null
        if (json.keysSet() != setOf(
                "protocolVersion", "type", "sessionId", "sequence", "timestampMs", "payload",
            )) return null
        if (json.strictString("protocolVersion") != "0.1") return null
        val type = json.strictString("type") ?: return null
        val sessionId = json.strictString("sessionId") ?: return null
        if (!SESSION.matches(sessionId)) return null
        val sequence = json.strictLong("sequence") ?: return null
        val timestamp = json.strictLong("timestampMs") ?: return null
        if (sequence < 0 || timestamp < 0) return null
        val payload = json.opt("payload") as? JSONObject ?: return null
        Envelope(type, sessionId, sequence, timestamp, payload)
    } catch (_: Exception) {
        null
        }
    }

    private companion object {
        val SESSION = Regex("^[A-Za-z0-9._-]{1,128}$")
    }
}

private fun JSONObject.keysSet(): Set<String> = keys().asSequence().toSet()
private fun JSONObject.strictString(name: String): String? = opt(name) as? String
private fun JSONObject.strictDouble(name: String): Double? = (opt(name) as? Number)?.toDouble()
private fun JSONObject.strictLong(name: String): Long? = when (val value = opt(name)) {
    is Int -> value.toLong()
    is Long -> value
    else -> null
}
