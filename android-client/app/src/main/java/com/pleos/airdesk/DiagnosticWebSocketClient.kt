package com.pleos.airdesk

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

data class DiagnosticUiState(
    val status: String = "연결 안 됨",
    val authorized: Boolean = false,
    val cursorVisible: Boolean = false,
    val x: Float = .5f,
    val y: Float = .5f,
    val lastSequence: Long = -1L,
)

/** Debug telemetry ONLY. Has no Windows input channel or remote keyboard. */
class DiagnosticWebSocketClient {
    private val http = OkHttpClient()
    private val mutable = MutableStateFlow(DiagnosticUiState())
    val state: StateFlow<DiagnosticUiState> = mutable
    private var socket: WebSocket? = null

    fun connect(token: String) {
        if (!BuildConfig.DEBUG || token.length < 24) {
            mutable.value = DiagnosticUiState(status = "개발용 토큰 없음 — 진단 연결 차단")
            return
        }
        disconnect()
        mutable.value = DiagnosticUiState(status = "진단 연결 중")
        val request = Request.Builder().url("ws://10.0.2.2:8765").build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val hello = JSONObject()
                    .put("protocolVersion", "0.1")
                    .put("type", "auth.hello")
                    .put("sessionId", "diagnostic-client")
                    .put("sequence", 0)
                    .put("timestampMs", SystemClock.elapsedRealtime())
                    .put("payload", JSONObject().put("token", token))
                webSocket.send(hello.toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val event = JSONObject(text)
                    if (event.optString("protocolVersion") != "0.1") return
                    val type = event.optString("type")
                    if (type == "auth.accepted") {
                        mutable.value = mutable.value.copy(authorized = true, status = "DEMO 연결됨 (PC 제어 불가)")
                        return
                    }
                    if (!mutable.value.authorized) return
                    val sequence = event.optLong("sequence", -1L)
                    if (sequence <= mutable.value.lastSequence) return
                    val payload = event.optJSONObject("payload") ?: return
                    when (type) {
                        "gesture.move", "gesture.drag_move" -> {
                            val x = payload.optDouble("x", Double.NaN)
                            val y = payload.optDouble("y", Double.NaN)
                            if (!x.isFinite() || !y.isFinite()) return
                            mutable.value = mutable.value.copy(
                                x = x.toFloat().coerceIn(0f, 1f),
                                y = y.toFloat().coerceIn(0f, 1f),
                                cursorVisible = true, lastSequence = sequence
                            )
                        }
                        "tracking.lost" -> mutable.value = mutable.value.copy(cursorVisible = false, lastSequence = sequence)
                        else -> mutable.value = mutable.value.copy(lastSequence = sequence)
                    }
                } catch (_: Exception) {
                    // Malformed diagnostic message must never unlock a remote input path.
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                mutable.value = DiagnosticUiState(status = "진단 연결 실패: 네트워크 확인")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                mutable.value = DiagnosticUiState(status = "연결 종료")
            }
        })
    }

    fun disconnect() {
        socket?.close(1000, "user disconnected")
        socket = null
        mutable.value = DiagnosticUiState()
    }
}
