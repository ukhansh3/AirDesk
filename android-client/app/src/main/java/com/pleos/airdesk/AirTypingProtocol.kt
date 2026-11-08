package com.pleos.airdesk

import org.json.JSONObject

sealed class AirWireEvent {
    data class Authorized(val sessionId: String) : AirWireEvent()
    data object Rejected : AirWireEvent()
    data class Frame(val value: AirFrame) : AirWireEvent()
}
enum class AirMessageRejection { NONE, IGNORE, STALE, INVALID }

/** Explicit v0.2 only. Frames are complete state, never text or operating-system commands. */
class AirTypingProtocol(private val schema: DiagnosticSchema) {
    private var session: String? = null
    private var sequence = -1L
    private var sourceTime = -1L
    private var offset = 0L
    private var epoch: String? = null
    private var acceptedStage: String? = null
    private var acceptedArmed = false
    private val retiredEpochs = mutableSetOf<String>()
    var lastRejection: AirMessageRejection = AirMessageRejection.NONE; private set

    fun reset() {
        session = null; sequence = -1; sourceTime = -1; offset = 0; epoch = null; retiredEpochs.clear()
        acceptedStage = null; acceptedArmed = false
        lastRejection = AirMessageRejection.NONE
    }

    fun consume(raw: String, nowMs: Long): AirWireEvent? {
      lastRejection = AirMessageRejection.INVALID
      return runCatching {
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
                lastRejection = AirMessageRejection.NONE
                AirWireEvent.Authorized(sid)
            }
            "auth.rejected" -> {
                require(session == null && seq == 0L)
                lastRejection = AirMessageRejection.NONE; AirWireEvent.Rejected
            }
            "air.frame" -> {
                require(session == sid)
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
                if (epoch != null && epoch != nextEpoch) require(!armed)
                val age = nowMs - timestamp - offset
                require(age >= -250) // An implausibly future timestamp is invalid, never a recoverable delay.
                // Only fully schema/semantic/session-valid frames can be harmless duplicates or
                // a stale camera sample. Malformed messages never get this recovery treatment.
                if (seq <= sequence) {
                    lastRejection = AirMessageRejection.IGNORE
                    return null
                }
                if (timestamp < sourceTime || age > 250) {
                    // Stale evidence can only retain an already accepted, unarmed calibration
                    // identity. It cannot introduce a new epoch, completion or input authority.
                    lastRejection = if (epoch == nextEpoch && acceptedStage != "READY" && !acceptedArmed &&
                        stage != "READY" && !armed) AirMessageRejection.STALE else AirMessageRejection.INVALID
                    return null
                }
                if (epoch != null && epoch != nextEpoch) retiredEpochs.add(epoch!!)
                epoch = nextEpoch; sequence = seq; sourceTime = timestamp
                acceptedStage = stage; acceptedArmed = armed
                lastRejection = AirMessageRejection.NONE
                AirWireEvent.Frame(AirFrame(nextEpoch, stage, status, armed, fingers, mouse, sid, seq, nowMs))
            }
            else -> error("Unexpected AIR message")
        }
      }.getOrNull()
    }
}
