package com.pleos.airdesk

/** One in-flight authenticated session message across both TCP channels. */
class ReceiptBarrier<T>(private val capacity: Int = 64) {
    data class Pending<T>(val value: T, val move: Boolean, val heartbeat: Boolean)
    private val queued = mutableListOf<Pending<T>>()
    private var awaiting: Long? = null
    private var dispatchedAtMs: Long = 0
    val size: Int get() = queued.size
    fun offer(value: T, move: Boolean = false, heartbeat: Boolean = false): Boolean {
        if (move && queued.lastOrNull()?.move == true) queued.removeAt(queued.lastIndex)
        if (heartbeat) queued.removeAll { it.heartbeat }
        if (queued.size >= capacity) return false
        val item = Pending(value, move, heartbeat)
        if (heartbeat) queued.add(0, item) else queued.add(item)
        return true
    }
    fun take(): T? = if (awaiting == null && queued.isNotEmpty()) queued.removeAt(0).value else null
    fun dispatched(sequence: Long, nowMs: Long = 0) { check(awaiting == null); awaiting = sequence; dispatchedAtMs = nowMs }
    fun timedOut(nowMs: Long): Boolean = awaiting != null && nowMs - dispatchedAtMs > 3000
    fun acknowledge(sequence: Long): Boolean {
        if (awaiting != sequence) return false
        awaiting = null
        return true
    }
    fun clear() { queued.clear(); awaiting = null; dispatchedAtMs = 0 }
}
