package com.pleos.airdesk

import org.json.JSONObject
import kotlin.math.abs

sealed class AirWireEvent {
    data class Authorized(val sessionId: String) : AirWireEvent()
    data object Rejected : AirWireEvent()
    data class Frame(val value: AirFrame) : AirWireEvent()
}

/** Explicit v0.2 only. Frames are complete state, never text or operating-system commands. */
class AirTypingProtocol(private val schema: DiagnosticSchema) {
    private var session: String? = null
    private var sequence = -1L
    private var sourceTime = -1L
    private var offset = 0L
    private var epoch: String? = null
    private val retiredEpochs = mutableSetOf<String>()

    fun reset() { session = null; sequence = -1; sourceTime = -1; offset = 0; epoch = null; retiredEpochs.clear() }

    fun consume(raw: String, nowMs: Long): AirWireEvent? = runCatching {
        val event = StrictJson.objectValue(raw)
        require(schema.accepts(event))
        val sid = event.getString("sessionId")
        val seq = event.getLong("sequence")
        val timestamp = event.getLong("timestampMs")
        val payload = event.getJSONObject("payload")
        when (event.getString("type")) {
            "auth.accepted" -> {
                require(session == null && seq == 0L)
                session = sid; sequence = seq; sourceTime = timestamp; offset = nowMs - timestamp
                AirWireEvent.Authorized(sid)
            }
            "auth.rejected" -> { require(session == null && seq == 0L); AirWireEvent.Rejected }
            "air.frame" -> {
                require(session == sid && seq > sequence && timestamp >= sourceTime && abs(nowMs - timestamp - offset) <= 250)
                val nextEpoch = payload.getString("epoch")
                require(nextEpoch !in retiredEpochs)
                val array = payload.getJSONArray("fingers")
                val fingers = (0 until array.length()).map {
                    val f = array.getJSONObject(it)
                    AirFinger(f.getString("hand"), f.getString("finger"), f.getDouble("x").toFloat(),
                        f.getDouble("y").toFloat(), f.getBoolean("pressed"), f.getBoolean("valid"))
                }
                require(fingers.map { it.hand to it.finger }.toSet().size == fingers.size)
                require(fingers.none { it.pressed && !it.valid })
                val m = payload.getJSONObject("mouse")
                val mouse = AirMouse(m.getBoolean("active"), m.getBoolean("valid"), m.getDouble("x").toFloat(),
                    m.getDouble("y").toFloat(), m.getBoolean("pressed"), m.getBoolean("dragging"))
                require(!mouse.pressed || (mouse.active && mouse.valid))
                require(!mouse.dragging || mouse.pressed)
                val armed = payload.getBoolean("armed")
                val stage = payload.getString("stage"); val status = payload.getString("status")
                require(armed == (stage == "READY" && status == "ARMED"))
                require(armed || (fingers.none { it.pressed } && !mouse.pressed))
                require(!mouse.active || fingers.none { it.hand == "right" && it.valid })
                if (epoch != null && epoch != nextEpoch) {
                    require(!armed)
                    retiredEpochs.add(epoch!!)
                }
                epoch = nextEpoch; sequence = seq; sourceTime = timestamp
                AirWireEvent.Frame(AirFrame(nextEpoch, stage, status, armed, fingers, mouse, sid, seq, nowMs))
            }
            else -> error("Unexpected AIR message")
        }
    }.getOrNull()
}
