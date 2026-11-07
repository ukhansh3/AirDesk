package com.pleos.airdesk

import org.json.JSONObject

enum class SecurePhase { DISCONNECTED, PAIRING, WAITING_APPROVAL, CONNECTED, LOCKED, ERROR }

data class InputGrant(val id: String, val capabilities: Set<String>, val deadlineMs: Long)

data class SecureUiState(
    val phase: SecurePhase = SecurePhase.DISCONNECTED,
    val status: String = "보안 연결 안 됨",
    val pairedConnectionId: String? = null,
    val pairedPairingId: String? = null,
    val sessionId: String? = null,
    val grant: InputGrant? = null,
    val foreground: Boolean = false,
    val safety: VehicleSafety = VehicleSafety.UNKNOWN,
    val mockEnabled: Boolean = false,
    val lastInboundMs: Long = -1,
    val lastSequence: Long = -1,
    val mockEventCount: Long = 0,
    val heldCount: Int = 0,
) {
    fun mockAllowed(capability: String, nowMs: Long): Boolean = mockEnabled && foreground &&
        pairedConnectionId != null && sessionId != null && grant?.capabilities?.contains(capability) == true &&
        nowMs < grant.deadlineMs && lastInboundMs >= 0 && nowMs - lastInboundMs <= 3000 &&
        safety in setOf(VehicleSafety.UNKNOWN, VehicleSafety.PARKED_CONFIRMED)
    // No verified platform-to-host safety evidence exists in this implementation.
    val nativeAllowed: Boolean get() = false
}

class SecureSessionReducer(private val schema: Phase2Schema) {
    var state = SecureUiState()
        private set
    private var peer = ""
    private var pairingSession: String? = null
    private var requested = emptySet<String>()
    private var hostApproved = false
    private val receiveSequence = mutableMapOf<String, Long>()
    private val receiveTimestamp = mutableMapOf<String, Long>()
    private val clockOffset = mutableMapOf<String, Long>()

    fun bind(fingerprint: String) {
        peer = fingerprint
        state = SecureUiState(foreground = state.foreground)
        receiveSequence.clear(); receiveTimestamp.clear(); clockOffset.clear()
        pairingSession = null; hostApproved = false; requested = emptySet()
    }

    fun pairingStarted(session: String) {
        pairingSession = session
        state = state.copy(phase = SecurePhase.PAIRING, status = "장치 인증 연결됨 · 8자리 코드와 양쪽 승인을 기다립니다", grant = null, sessionId = null)
    }

    fun approvalSent() {
        state = state.copy(phase = SecurePhase.WAITING_APPROVAL, status = "차량 화면 승인 완료 · PC 승인을 기다립니다")
    }

    fun requestStarted(session: String, capabilities: Set<String>) {
        require(state.pairedConnectionId != null)
        require(capabilities.isNotEmpty() && capabilities.all { it in setOf("POINTER", "KEYBOARD") })
        requested = capabilities
        hostApproved = false
        state = state.copy(phase = SecurePhase.WAITING_APPROVAL, status = "PC 사용자의 세션 승인을 기다립니다", sessionId = session, grant = null, mockEnabled = false, heldCount = 0)
    }

    fun foreground(value: Boolean) {
        state = state.copy(foreground = value)
        if (!value) lock("앱이 백그라운드로 전환되어 입력을 잠갔습니다")
    }

    fun safety(value: VehicleSafety) {
        state = state.copy(safety = value)
        if (value !in setOf(VehicleSafety.UNKNOWN, VehicleSafety.PARKED_CONFIRMED)) lock("차량 안전 상태 변경 · 입력 잠금")
    }

    fun setMock(enabled: Boolean) {
        state = state.copy(mockEnabled = enabled, heldCount = 0)
    }

    fun recordMock(held: Int) {
        state = state.copy(mockEventCount = state.mockEventCount + 1, heldCount = held)
    }

    fun clearHeld() { state = state.copy(heldCount = 0) }

    fun lock(reason: String) {
        state = state.copy(phase = SecurePhase.LOCKED, status = reason, grant = null, mockEnabled = false, heldCount = 0)
        hostApproved = false
    }

    fun disconnected(reason: String) {
        state = state.copy(phase = SecurePhase.DISCONNECTED, status = reason, sessionId = null, grant = null,
            mockEnabled = false, heldCount = 0, lastInboundMs = -1)
        receiveSequence.clear(); receiveTimestamp.clear(); clockOffset.clear()
        hostApproved = false
    }

    fun tick(nowMs: Long): Boolean {
        val grant = state.grant ?: return false
        if (nowMs >= grant.deadlineMs || nowMs - state.lastInboundMs > 3000) {
            lock(if (nowMs >= grant.deadlineMs) "세션/권한 만료 · 새 PC 승인이 필요합니다" else "Heartbeat 시간 초과 · 입력 잠금")
            return true
        }
        return false
    }

    fun consume(text: String, fingerprint: String, nowMs: Long, nowEpochMs: Long): Boolean {
        if (fingerprint != peer || text.toByteArray().size > 8192) return false
        val message = try { StrictJson.objectValue(text) } catch (_: Exception) { return false }
        if (!schema.accepts(message)) return false
        val sid = message.optString("sessionId")
        if (sid != pairingSession && sid != state.sessionId) return false
        val sequence = message.integer("sequence") ?: return false
        val timestamp = message.integer("timestampMs") ?: return false
        if (sequence <= (receiveSequence[sid] ?: -1)) return false
        if (timestamp < (receiveTimestamp[sid] ?: -1)) return false
        val offset = clockOffset[sid]
        if (offset != null && nowMs - offset - timestamp !in -250..750) return false
        val payload = message.getJSONObject("payload")
        val type = message.getString("type")
        if (type.startsWith("pairing.") && sid != pairingSession) return false
        if (type.startsWith("session.") && sid != state.sessionId) return false
        if (type in setOf("pairing.approval", "pairing.completed") && state.sessionId != null) return false
        val next = when (type) {
            "pairing.approval" -> {
                if (payload.getString("side") != "HOST") return false
                if (!payload.getBoolean("approved")) state.copy(phase = SecurePhase.LOCKED, status = "PC에서 페어링 요청을 거부했습니다", grant = null)
                else state.copy(phase = SecurePhase.WAITING_APPROVAL, status = "PC 승인 완료 · 양쪽 승인 확인 중")
            }
            "pairing.completed" -> state.copy(phase = SecurePhase.CONNECTED, status = "장치 페어링 완료 · 세션 승인이 별도로 필요합니다",
                pairedConnectionId = payload.getString("connectionId"), pairedPairingId = payload.getString("pairingId"))
            "pairing.revoked" -> state.copy(phase = SecurePhase.LOCKED, status = "장치 연결 권한이 회수됐습니다", pairedConnectionId = null,
                pairedPairingId = null, grant = null, sessionId = null, mockEnabled = false, heldCount = 0)
            "session.approve" -> {
                if (sid != state.sessionId) return false
                val remaining = (payload.integer("expiresAtMs") ?: return false) - nowEpochMs
                if (remaining !in 1..600000) return false
                hostApproved = payload.getBoolean("approved")
                state.copy(phase = if (hostApproved) SecurePhase.WAITING_APPROVAL else SecurePhase.LOCKED,
                    status = if (hostApproved) "PC 세션 승인 완료 · 권한 확인 중" else "PC에서 세션을 거부했습니다", grant = null)
            }
            "session.granted" -> {
                if (!hostApproved || sid != state.sessionId) return false
                val capsJson = payload.getJSONArray("capabilities")
                val caps = (0 until capsJson.length()).map { capsJson.getString(it) }.toSet()
                if (caps.isEmpty() || !requested.containsAll(caps) || caps.any { it == "VIEW_VIDEO" }) return false
                val ttl = (payload.integer("expiresAtMs") ?: return false) - nowEpochMs
                if (ttl !in 1..600000) return false
                state.copy(phase = SecurePhase.LOCKED, status = "세션 승인됨 · 차량 안전 미검증으로 실제 PC 제어 잠금",
                    grant = InputGrant(payload.getString("grantId"), caps, nowMs + ttl))
            }
            "session.heartbeat" -> {
                if (sid != state.sessionId || state.grant == null) return false
                state
            }
            "session.revoke", "session.ended" -> {
                hostApproved = false
                state.copy(phase = SecurePhase.LOCKED, status = "세션 종료/권한 회수 · 입력 잠금", grant = null, mockEnabled = false, heldCount = 0)
            }
            "protocol.error" -> {
                hostApproved = false
                state.copy(phase = SecurePhase.LOCKED, status = "호스트 응답: ${payload.getString("code")} · 입력 잠금",
                    grant = null, mockEnabled = false, heldCount = 0)
            }
            else -> return false
        }
        clockOffset.putIfAbsent(sid, nowMs - timestamp)
        receiveSequence[sid] = sequence
        receiveTimestamp[sid] = timestamp
        state = next.copy(lastInboundMs = nowMs, lastSequence = sequence)
        return true
    }
}
