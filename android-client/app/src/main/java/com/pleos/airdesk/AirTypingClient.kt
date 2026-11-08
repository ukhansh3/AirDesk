package com.pleos.airdesk

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

data class AirTransportState(val authorized: Boolean = false, val status: String = "허공 타이핑 연결 안 됨", val frame: AirFrame? = null,
    val retainedCalibrationStage: String? = null, val calibrationPaused: Boolean = false, val disconnectReason: String? = null)
sealed class AirSignal {
    data class Frame(val value: AirFrame) : AirSignal()
    data class Cancel(val reason: String, val retainCalibration: Boolean = false) : AirSignal()
}

/** Local DEBUG DEMO only; no frame recording, text transport or automatic input rearming. */
class AirTypingClient(context: Context) {
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val protocol = AirTypingProtocol(DiagnosticSchema(listOf(context.assets.open("air/air-messages.schema.json")
        .bufferedReader().use { JSONObject(it.readText()) })))
    private val mutable = MutableStateFlow(AirTransportState())
    val state: StateFlow<AirTransportState> = mutable
    private val observers = linkedSetOf<(AirSignal) -> Unit>()
    private var socket: WebSocket? = null
    private var wanted = false
    private var token = ""
    private var url = ""
    private var session: String? = null
    private var commandSeq = 0L
    private var lastFrameAt = -1L
    private var attempt = 0
    private var retry: Job? = null
    private var handshake: Job? = null
    private val recovery = AirTransportRecoveryPolicy()
    private var disconnectNotice: AirDisconnectNotice? = null

    init {
        scope.launch {
            while (true) {
                delay(50)
                if (lastFrameAt >= 0 && SystemClock.elapsedRealtime() - lastFrameAt > 250) {
                    releaseForLoss("air_timeout", AirInputLoss.TIMEOUT)
                }
            }
        }
    }

    fun observe(observer: (AirSignal) -> Unit): () -> Unit { observers.add(observer); return { observers.remove(observer) } }
    private fun emit(signal: AirSignal) { observers.toList().forEach { it(signal) } }

    fun connect() {
        disconnect()
        val uri = runCatching { URI(BuildConfig.DIAGNOSTIC_WS_URL) }.getOrNull()
        if (!BuildConfig.DEBUG || BuildConfig.DEV_TOKEN.length !in 24..512 || uri?.scheme != "ws" ||
            uri.host !in setOf("10.0.2.2", "127.0.0.1", "localhost", "[::1]") || uri.userInfo != null ||
            uri.query != null || uri.fragment != null || uri.path !in setOf("", "/")) {
            mutable.value = AirTransportState(status = "로컬 개발 설정 없음 · 허공 입력 차단")
            return
        }
        url = BuildConfig.DIAGNOSTIC_WS_URL.trimEnd('/') + "/air"
        token = BuildConfig.DEV_TOKEN; wanted = true; attempt = 0; open()
    }

    private fun open() {
        if (!wanted) return
        protocol.reset(); recovery.reset(); session = null; commandSeq = 0; lastFrameAt = -1
        mutable.value = AirTransportState(status = disconnectNotice?.let { "${it.label} · 다시 연결 중" }
            ?: "허공 타이핑 연결 중 · 자동 입력 재개 없음", disconnectReason = disconnectNotice?.label)
        socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            @Volatile private var closingNotice: AirDisconnectNotice? = null
            override fun onOpen(webSocket: WebSocket, response: Response) { scope.launch {
                if (socket !== webSocket || !wanted) return@launch
                webSocket.send(envelope("auth.hello", "air-client-${UUID.randomUUID()}", 0, JSONObject().put("token", token)).toString())
                handshake?.cancel(); handshake = scope.launch { delay(5000); if (socket === webSocket && session == null) webSocket.cancel() }
            } }
            override fun onMessage(webSocket: WebSocket, text: String) { scope.launch {
                if (socket !== webSocket || !wanted) return@launch
                when (val event = protocol.consume(text, SystemClock.elapsedRealtime())) {
                    is AirWireEvent.Authorized -> {
                        handshake?.cancel(); session = event.sessionId; attempt = 0
                        recovery.authorized(event.sessionId)
                        mutable.value = AirTransportState(true, disconnectNotice?.let { "${it.label} · 연결 복구됨 · 새 보정 필요" }
                            ?: "연결됨 · 양손 보정부터 시작하세요", disconnectReason = disconnectNotice?.label)
                    }
                    AirWireEvent.Rejected -> {
                        disconnectNotice = AirDisconnectNotice.AUTH_REJECTED
                        disconnect(); mutable.value = AirTransportState(status = "개발 인증 거부", disconnectReason = disconnectNotice?.label)
                    }
                    is AirWireEvent.Frame -> {
                        val continuity = recovery.frameAccepted(event.value)
                        if (continuity.releaseLocalInput) emit(AirSignal.Cancel("air_source_state_changed", continuity.retainCalibration))
                        if (continuity.sendDisarm) sendCommand("disarm", notifyCancel = false)
                        lastFrameAt = event.value.receivedAtMs
                        mutable.value = AirTransportState(true, disconnectNotice?.let { "${it.label} · 새 보정 필요" }
                            ?: "실험용 허공 타이핑 · ${event.value.stage}", event.value, disconnectReason = disconnectNotice?.label)
                        emit(AirSignal.Frame(event.value))
                    }
                    null -> when (protocol.lastRejection) {
                        AirMessageRejection.IGNORE -> Unit
                        AirMessageRejection.STALE -> releaseForLoss("stale_air_frame", AirInputLoss.STALE_VALID_FRAME)
                        else -> releaseForLoss("invalid_air_message", AirInputLoss.INVALID_MESSAGE)
                    }
                }
            } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch { failed(webSocket, closingNotice ?: AirDisconnectNotice.CONNECTION_FAILED) }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch { failed(webSocket, AirDisconnectNotice.fromClose(code, reason)) }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                closingNotice = AirDisconnectNotice.fromClose(code, reason); webSocket.close(code, null)
            }
        })
    }

    private fun failed(old: WebSocket, notice: AirDisconnectNotice) {
        if (socket !== old || !wanted) return
        socket = null; handshake?.cancel(); session = null; lastFrameAt = -1; recovery.reset()
        val remembered = AirDisconnectNotice.remember(disconnectNotice, notice)
        disconnectNotice = remembered
        emit(AirSignal.Cancel("air_disconnected"))
        mutable.value = AirTransportState(status = remembered.label, disconnectReason = remembered.label)
        if (attempt >= 5) { wanted = false; return }
        val wait = (1 shl attempt.coerceAtMost(3)) * 1000L; attempt++
        retry?.cancel(); retry = scope.launch { delay(wait); open() }
    }

    fun command(action: String) = sendCommand(action, notifyCancel = true)

    private fun sendCommand(action: String, notifyCancel: Boolean) {
        val sid = session ?: return
        if (action !in setOf("start_calibration", "capture", "arm", "disarm", "reset")) return
        recovery.command(action)
        if (action == "start_calibration") {
            disconnectNotice = null
            mutable.value = mutable.value.copy(disconnectReason = null)
        }
        if (notifyCancel && action != "arm") emit(AirSignal.Cancel("air_command_$action"))
        socket?.send(envelope("air.command", sid, ++commandSeq, JSONObject().put("action", action)).toString())
    }

    private fun releaseForLoss(reason: String, kind: AirInputLoss) {
        val decision = recovery.inputLost(kind)
        lastFrameAt = -1
        // Preserve only progress metadata, never stale fingertip positions or press state.
        mutable.value = mutable.value.copy(status = if (kind == AirInputLoss.INVALID_MESSAGE)
            "잘못된 손 메시지 거부 · 입력 해제" else "손 데이터 지연 · 입력 해제",
            frame = null, retainedCalibrationStage = recovery.retainedCalibrationStage,
            calibrationPaused = recovery.calibrationPaused)
        emit(AirSignal.Cancel(reason, decision.retainCalibration))
        if (decision.sendDisarm) sendCommand("disarm", notifyCancel = false)
    }

    private fun envelope(type: String, sid: String, seq: Long, payload: JSONObject) = JSONObject()
        .put("protocolVersion", "0.2").put("type", type).put("sessionId", sid).put("sequence", seq)
        .put("timestampMs", SystemClock.elapsedRealtime()).put("payload", payload)

    fun disconnect() {
        wanted = false; retry?.cancel(); handshake?.cancel(); session = null; lastFrameAt = -1
        val old = socket; socket = null; old?.close(1000, "user disconnected")
        token = ""; protocol.reset(); recovery.reset(); mutable.value = AirTransportState(disconnectReason = disconnectNotice?.label)
        emit(AirSignal.Cancel("air_disconnected"))
    }
    fun close() { disconnect(); scope.cancel(); http.dispatcher.executorService.shutdown() }
}
