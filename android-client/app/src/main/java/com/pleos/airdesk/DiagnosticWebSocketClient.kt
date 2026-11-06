package com.pleos.airdesk

import android.os.SystemClock
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.net.URI

/** Debug telemetry ONLY. Has no Windows input channel or remote keyboard. */
class DiagnosticWebSocketClient(context: Context) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val reducer = DiagnosticMessageReducer(DiagnosticSchema(listOf(
        "control/messages.schema.json", "gesture/gesture-event.schema.json",
    ).map { path -> context.assets.open(path).bufferedReader().use { JSONObject(it.readText()) } }))
    private val mutable = MutableStateFlow(DiagnosticUiState())
    val state: StateFlow<DiagnosticUiState> = mutable

    @Volatile private var socket: WebSocket? = null
    @Volatile private var stoppedByUser = true
    private var token: String = ""
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var trackingWatchdog: Job? = null
    private var handshakeWatchdog: Job? = null

    fun connect(token: String) {
        val uri = runCatching { URI(BuildConfig.DIAGNOSTIC_WS_URL) }.getOrNull()
        val localUrl = uri?.scheme == "ws" && uri.host in setOf("10.0.2.2", "127.0.0.1", "localhost", "[::1]") &&
            uri.userInfo == null && uri.query == null && uri.fragment == null
        if (!BuildConfig.DEBUG || token.length !in 24..512 || !localUrl) {
            publish(reducer.reset("개발용 설정 없음 — 진단 연결 차단"))
            return
        }
        disconnect(updateUi = false)
        this.token = token
        stoppedByUser = false
        reconnectAttempt = 0
        openSocket()
    }

    private fun openSocket() {
        if (stoppedByUser) return
        reconnectJob?.cancel()
        publish(reducer.awaitingConnection(if (reconnectAttempt == 0) "진단 연결 중" else "진단 재연결 중"))
        val request = Request.Builder().url(BuildConfig.DIAGNOSTIC_WS_URL).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch {
                if (socket !== webSocket || stoppedByUser) return@launch
                val hello = JSONObject()
                    .put("protocolVersion", "0.1")
                    .put("type", "auth.hello")
                    .put("sessionId", "diagnostic-client")
                    .put("sequence", 0)
                    .put("timestampMs", SystemClock.elapsedRealtime())
                    .put("payload", JSONObject().put("token", token))
                webSocket.send(hello.toString())
                handshakeWatchdog?.cancel()
                handshakeWatchdog = scope.launch {
                    delay(5000)
                    if (socket === webSocket && !mutable.value.authorized) webSocket.cancel()
                }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                if (socket !== webSocket || stoppedByUser) return@launch
                val next = reducer.consume(text) ?: return@launch
                handshakeWatchdog?.cancel()
                if (next.authorized) reconnectAttempt = 0
                publish(next)
                if (next.cursorVisible) armTrackingWatchdog(next.lastSequence)
                if (!next.authorized) {
                    stoppedByUser = true
                    trackingWatchdog?.cancel()
                    socket = null
                    webSocket.close(1000, "diagnostic session ended")
                }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch {
                if (socket !== webSocket || stoppedByUser) return@launch
                socket = null
                scheduleReconnect()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch {
                if (socket !== webSocket || stoppedByUser) return@launch
                socket = null
                scheduleReconnect()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }
        })
    }

    private fun scheduleReconnect() {
        trackingWatchdog?.cancel()
        handshakeWatchdog?.cancel()
        val seconds = (1 shl reconnectAttempt.coerceAtMost(3)).toLong()
        reconnectAttempt++
        publish(reducer.reset("연결 끊김 · ${seconds}초 후 재연결"))
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(seconds * 1_000)
            openSocket()
        }
    }

    private fun armTrackingWatchdog(sequence: Long) {
        trackingWatchdog?.cancel()
        handshakeWatchdog?.cancel()
        trackingWatchdog = scope.launch {
            delay(TRACKING_TIMEOUT_MS)
            reducer.trackingTimedOut(sequence)?.let(::publish)
        }
    }

    fun disconnect() = disconnect(updateUi = true)

    private fun disconnect(updateUi: Boolean) {
        stoppedByUser = true
        reconnectJob?.cancel()
        trackingWatchdog?.cancel()
        reconnectJob = null
        trackingWatchdog = null
        val old = socket
        socket = null
        old?.close(1000, "user disconnected")
        if (updateUi) publish(reducer.reset())
        token = ""
    }

    fun close() {
        disconnect()
        http.dispatcher.executorService.shutdown()
        scope.cancel()
    }

    private fun publish(value: DiagnosticUiState) {
        mutable.value = value
    }

    private companion object {
        const val TRACKING_TIMEOUT_MS = 750L
    }
}
