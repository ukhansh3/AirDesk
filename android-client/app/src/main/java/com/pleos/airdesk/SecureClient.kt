package com.pleos.airdesk

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Authenticated phase2 is separate from debug gesture transport and native authority. */
class SecureClient(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences = app.getSharedPreferences("airdesk-public-enrollment-v1", Context.MODE_PRIVATE)
    private val identity = DeviceIdentity()
    private val schema = Phase2Schema(listOf("pairing/pairing-messages.schema.json", "session/session-messages.schema.json",
        "input/input-event.schema.json", "errors/error.schema.json").map { path ->
        app.assets.open(path).bufferedReader().use { JSONObject(it.readText()) }
    })
    private val reducer = SecureSessionReducer(schema)
    private val mutable = MutableStateFlow(reducer.state)
    val state: StateFlow<SecureUiState> = mutable
    private val publicMutable = MutableStateFlow("")
    val publicInfo: StateFlow<String> = publicMutable
    private val noteMutable = MutableStateFlow("공개 인증서 지문을 양쪽 화면에서 확인하세요.")
    val note: StateFlow<String> = noteMutable
    private val candidates = InputCandidates()
    private var host: HostBootstrap? = preferences.getString("bootstrap", null)?.let { runCatching { HostBootstrap.parse(it) }.getOrNull() }
    private var http: OkHttpClient? = null
    private var control: WebSocket? = null
    private var mockInput: WebSocket? = null
    private var heartbeat: Job? = null
    private var pairingSession = ""
    private val sendSequences = mutableMapOf<String, Long>()
    private data class SessionSend(val type: String, val session: String, val payload: JSONObject, val input: Boolean)
    private val barrier = ReceiptBarrier<SessionSend>()
    var gestureEnabled = false
        private set

    private fun publish() { mutable.value = reducer.state }
    private fun now() = SystemClock.elapsedRealtime()

    fun createIdentity() {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { identity.createOrReadPublicInfo() } }
            result.onSuccess { publicMutable.value = it; noteMutable.value = "공개 장치 정보를 PC에 등록하고 전체 SHA-256 지문을 대조하세요. 개인키는 내보내지 않습니다." }
                .onFailure { noteMutable.value = "장치 키를 준비하지 못했습니다. Android Keystore 지원/인증서 유효기간을 확인하세요." }
        }
    }

    fun inspectBootstrap(text: String): String? = runCatching { HostBootstrap.parse(text).fingerprint }
        .onFailure { noteMutable.value = "공개 연결 정보의 형식·인증서·지문을 확인해 주세요." }.getOrNull()

    fun trustBootstrap(text: String, fingerprintConfirmed: Boolean) {
        if (!fingerprintConfirmed) { noteMutable.value = "PC 화면과 전체 지문을 대조한 뒤 확인을 선택하세요."; return }
        val parsed = runCatching { HostBootstrap.parse(text) }.getOrElse {
            noteMutable.value = "연결 정보가 유효하지 않습니다."; return
        }
        disconnect("호스트 신뢰 정보를 변경했습니다. 새 보안 연결이 필요합니다.")
        host = parsed
        preferences.edit().putString("bootstrap", text).apply() // Public metadata only.
        noteMutable.value = "호스트 공개 인증서 등록됨 · ${parsed.fingerprint}"
    }

    fun connect() {
        val selected = host ?: run { noteMutable.value = "먼저 PC 공개 연결 정보와 지문을 확인해 주세요."; return }
        if (!identity.exists()) { noteMutable.value = "먼저 장치 공개 정보를 생성하고 PC에 등록해 주세요."; return }
        disconnect("보안 연결 준비 중")
        reducer.bind(selected.fingerprint)
        pairingSession = UUID.randomUUID().toString()
        sendSequences.clear()
        val client = runCatching { secureHttp(selected, identity) }.getOrElse {
            noteMutable.value = "보안 연결 설정을 만들지 못했습니다. 인증서와 장치 키를 확인해 주세요."; return
        }
        http = client
        control = client.newWebSocket(Request.Builder().url(selected.url + "/control").build(), object : WebSocketListener() {
            override fun onOpen(socket: WebSocket, response: Response) {
                scope.launch {
                    if (control !== socket) return@launch
                    reducer.pairingStarted(pairingSession); publish()
                    sendControl("pairing.begin", pairingSession, JSONObject().put("deviceLabel", "AirDesk Android device"))
                }
            }
            override fun onMessage(socket: WebSocket, text: String) {
                scope.launch {
                    if (control !== socket) return@launch
                    consume(text, selected.fingerprint)
                }
            }
            override fun onFailure(socket: WebSocket, error: Throwable, response: Response?) {
                scope.launch { if (control === socket) disconnect("TLS/보안 연결 실패 · 인증서 등록·지문·PC 서버를 확인하세요") }
            }
            override fun onClosing(socket: WebSocket, code: Int, reason: String) { socket.close(code, null) }
            override fun onClosed(socket: WebSocket, code: Int, reason: String) {
                scope.launch { if (control === socket) disconnect("보안 연결 종료 · 자동으로 입력 권한을 복원하지 않습니다") }
            }
        })
    }

    private fun consume(text: String, peer: String) {
        val previous = reducer.state
        if (!reducer.consume(text, peer, now(), System.currentTimeMillis())) return
        publish()
        val incoming = StrictJson.objectValue(text)
        if (incoming.getString("type") == "session.heartbeat") {
            if (barrier.acknowledge(incoming.getJSONObject("payload").getLong("ackSequence"))) drainSessionQueue()
        }
        if (reducer.state.grant != null && previous.grant == null) startHeartbeat()
        if (reducer.state.grant == null && previous.grant != null) {
            gestureEnabled = false
            candidates.release("REVOKED"); reducer.clearHeld(); mockInput?.close(1000, "grant ended"); mockInput = null
            heartbeat?.cancel(); barrier.clear(); publish()
        }
    }

    fun claim(code: String) {
        val selected = host ?: return
        if (!code.matches(Regex("^[0-9]{8}$"))) { noteMutable.value = "PC에 표시된 8자리 코드를 입력해 주세요."; return }
        if (!selected.canClaim(System.currentTimeMillis())) { noteMutable.value = "코드가 만료됐습니다. PC에서 새 연결 정보를 만들어 주세요."; return }
        sendControl("pairing.claim", pairingSession, JSONObject().put("pairingId", selected.pairingId).put("code", code).put("deviceLabel", "AirDesk Android device"))
        noteMutable.value = "코드를 전송했습니다. 장치 이름과 지문을 확인한 뒤 양쪽에서 승인하세요."
    }

    fun approvePairing(approved: Boolean) {
        val selected = host ?: return
        sendControl("pairing.approval", pairingSession, JSONObject().put("pairingId", selected.pairingId).put("side", "VEHICLE").put("approved", approved))
        if (approved) reducer.approvalSent() else reducer.lock("차량 화면에서 페어링을 거부했습니다")
        publish()
    }

    fun requestSession() {
        if (control == null) { noteMutable.value = "새 보안 연결 후 세션을 요청하세요."; return }
        val connection = reducer.state.pairedConnectionId ?: return
        if (reducer.state.grant != null || (reducer.state.phase == SecurePhase.WAITING_APPROVAL && reducer.state.sessionId != null)) return
        releaseHeld("USER_REQUEST")
        gestureEnabled = false
        barrier.clear()
        reducer.state.sessionId?.let { sendControl("session.revoke", it, JSONObject().put("reason", "USER_REQUEST")) }
        val sid = UUID.randomUUID().toString()
        reducer.requestStarted(sid, setOf("POINTER", "KEYBOARD")); publish()
        sendControl("session.request", sid, JSONObject().put("connectionId", connection).put("capabilities", JSONArray(listOf("POINTER", "KEYBOARD"))))
    }

    private fun startHeartbeat() {
        heartbeat?.cancel()
        val initialSession = reducer.state.sessionId ?: return
        queueSession(SessionSend("session.heartbeat", initialSession, JSONObject().put("ackSequence", reducer.state.lastSequence.coerceIn(0, Int.MAX_VALUE.toLong())), false))
        heartbeat = scope.launch {
            while (reducer.state.grant != null) {
                delay(1000)
                val sid = reducer.state.sessionId ?: break
                if (barrier.timedOut(now()) || reducer.tick(now())) {
                    endLocalSession("Heartbeat/권한 시간 초과 · 새 연결 필요", "TIMEOUT", true)
                    break
                }
                queueSession(SessionSend("session.heartbeat", sid, JSONObject().put("ackSequence", reducer.state.lastSequence.coerceIn(0, Int.MAX_VALUE.toLong())), false))
            }
        }
    }

    fun foreground(value: Boolean) {
        reducer.foreground(value); publish() // Lock synchronously BEFORE any network notification.
        if (!value) {
            endLocalSession("앱 포커스 해제 · 입력 잠금", "SAFETY_LOCK", true)
            disconnect("앱이 백그라운드로 전환되어 연결/입력을 잠갔습니다")
        }
    }

    fun safetyChanged(value: VehicleSafety) {
        reducer.safety(value); publish()
        if (value !in setOf(VehicleSafety.UNKNOWN, VehicleSafety.PARKED_CONFIRMED)) {
            endLocalSession("차량 안전 상태 변경 · 새 연결 필요", "SAFETY_LOCK", true)
        }
    }

    fun enableMock(enabled: Boolean) {
        if (!enabled && (reducer.state.mockEnabled || mockInput != null)) {
            gestureEnabled = false; revokeSession(); return
        }
        if (!enabled) releaseHeld("USER_REQUEST")
        reducer.setMock(enabled); publish()
        if (!enabled) { mockInput?.close(1000, "mock disabled"); mockInput = null; return }
        if (!reducer.state.mockAllowed("POINTER", now())) { reducer.setMock(false); publish(); noteMutable.value = "먼저 페어링과 PC 세션 승인이 필요합니다."; return }
        val selected = host ?: return
        mockInput = http?.newWebSocket(Request.Builder().url(selected.url + "/mock-input").build(), object : WebSocketListener() {
            override fun onMessage(socket: WebSocket, text: String) { scope.launch { if (mockInput === socket) consume(text, selected.fingerprint) } }
            override fun onFailure(socket: WebSocket, error: Throwable, response: Response?) { scope.launch {
                if (mockInput === socket) inputChannelFailed()
            } }
            override fun onClosing(socket: WebSocket, code: Int, reason: String) { socket.close(code, null) }
            override fun onClosed(socket: WebSocket, code: Int, reason: String) { scope.launch {
                if (mockInput === socket) inputChannelFailed()
            } }
        })
    }

    fun gestureBridge(enabled: Boolean) { releaseHeld("USER_REQUEST"); gestureEnabled = enabled }
    fun gesture(hint: DiagnosticUiState) {
        if (!gestureEnabled || !reducer.state.mockEnabled) return
        candidates.gesture(hint).forEach(::sendCandidate)
    }
    fun move(x: Float, y: Float) { candidates.move(x, y)?.let(::sendCandidate) }
    fun down(button: String = "left") { candidates.down(button)?.let(::sendCandidate) }
    fun up(button: String = "left") { candidates.up(button)?.let(::sendCandidate) }
    fun scroll(lines: Int) { candidates.scroll(lines)?.let(::sendCandidate) }
    fun committedText(text: String) { candidates.text(text)?.let(::sendCandidate) }
    fun key(code: String, down: Boolean) { candidates.key(code, down)?.let(::sendCandidate) }

    private fun sendCandidate(candidate: InputCandidate) {
        if (candidate.type == "input.release_all") { releaseHeld(candidate.payload.getString("reason")); return }
        val snapshot = reducer.state
        if (!snapshot.mockAllowed(candidate.capability, now())) { candidates.release("SAFETY_LOCK"); reducer.clearHeld(); publish(); return }
        val payload = JSONObject(candidate.payload.toString()).put("grantId", snapshot.grant!!.id)
        if (queueSession(SessionSend(candidate.type, snapshot.sessionId!!, payload, true))) { reducer.recordMock(candidates.heldCount); publish() }
    }

    fun releaseHeld(reason: String) {
        val candidate = candidates.release(reason)
        reducer.clearHeld(); publish()
        val sid = reducer.state.sessionId ?: return
        val channel = mockInput
        if (channel != null) queueSession(SessionSend(candidate.type, sid, candidate.payload, true))
    }

    private fun queueSession(item: SessionSend): Boolean {
        if (!barrier.offer(item, move = item.type == "pointer.move", heartbeat = item.type == "session.heartbeat")) {
            inputChannelFailed(); return false
        }
        return drainSessionQueue()
    }

    private fun drainSessionQueue(): Boolean {
        val item = barrier.take() ?: return true
        val message = envelope(item.type, item.session, item.payload)
        if (!schema.accepts(message)) { inputChannelFailed(); return false }
        val channel = if (item.input) mockInput else control
        barrier.dispatched(message.getLong("sequence"), now())
        if (channel?.send(message.toString()) != true) { inputChannelFailed(); return false }
        return true
    }

    /** A failed enqueue cannot leave heartbeats maintaining a remotely held button. */
    private fun inputChannelFailed() {
        endLocalSession("모의 입력 전송 실패 · 새 연결 필요", "ERROR", true)
    }

    private fun endLocalSession(status: String, reason: String, closeControl: Boolean) {
        val sid = reducer.state.sessionId
        reducer.lock(status); candidates.release("DISCONNECT"); publish()
        gestureEnabled = false
        heartbeat?.cancel(); heartbeat = null; barrier.clear()
        val failed = mockInput; mockInput = null; failed?.cancel()
        if (sid != null) sendControl("session.revoke", sid, JSONObject().put("reason", reason))
        if (closeControl) { val oldControl = control; control = null; oldControl?.cancel() }
    }

    fun revokeSession() {
        endLocalSession("사용자가 세션을 종료했습니다", "USER_REQUEST", false)
    }

    fun forgetPeer() {
        val pairing = reducer.state.pairedPairingId
        val sid = pairingSession
        revokeSession()
        if (pairing != null) sendControl("pairing.revoked", sid, JSONObject().put("pairingId", pairing).put("reason", "USER_REQUEST"))
        disconnect("호스트 연결 정보를 삭제했습니다")
        preferences.edit().remove("bootstrap").apply()
        host = null
        reducer.bind(""); publish()
    }

    fun disconnect(reason: String = "보안 연결 해제") {
        reducer.disconnected(reason); publish() // Local held state/authority is gone before close.
        candidates.release("DISCONNECT")
        heartbeat?.cancel(); heartbeat = null; barrier.clear()
        val oldInput = mockInput; mockInput = null; oldInput?.close(1000, "client disconnect")
        val oldControl = control; control = null; oldControl?.close(1000, "client disconnect")
        http?.connectionPool?.evictAll(); http?.dispatcher?.executorService?.shutdown(); http = null
        gestureEnabled = false
    }

    private fun envelope(type: String, sid: String, payload: JSONObject): JSONObject {
        val seq = sendSequences[sid] ?: 0
        sendSequences[sid] = seq + 1
        return JSONObject().put("protocolVersion", "0.1").put("type", type).put("sessionId", sid)
            .put("sequence", seq).put("timestampMs", now()).put("payload", payload)
    }

    private fun sendControl(type: String, sid: String, payload: JSONObject) {
        if (sid.isBlank()) return
        val message = envelope(type, sid, payload)
        if (schema.accepts(message)) control?.send(message.toString())
    }

    fun close() { disconnect(); scope.cancel() }
}
