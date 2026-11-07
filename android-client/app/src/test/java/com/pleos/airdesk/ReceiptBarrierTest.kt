package com.pleos.airdesk

import org.junit.Assert.*
import org.junit.Test

class ReceiptBarrierTest {
    @Test fun channelSwitchWaitsForExactReceiptAndRejectsOldOrFutureAck() {
        val queue = ReceiptBarrier<String>()
        queue.offer("input-down"); queue.offer("heartbeat", heartbeat = true)
        assertEquals("heartbeat", queue.take()); queue.dispatched(10)
        assertNull(queue.take())
        assertFalse(queue.acknowledge(9)); assertFalse(queue.acknowledge(11)); assertNull(queue.take())
        assertTrue(queue.acknowledge(10)); assertEquals("input-down", queue.take())
    }
    @Test fun movesCoalesceOnlyBeforeDispatchWithoutDroppingButtonOrRelease() {
        val queue = ReceiptBarrier<String>()
        queue.offer("move1", move = true); queue.offer("move2", move = true)
        queue.offer("down"); queue.offer("move3", move = true); queue.offer("release")
        assertEquals(4, queue.size)
        assertEquals("move2", queue.take()); queue.dispatched(1); queue.acknowledge(1)
        assertEquals("down", queue.take()); queue.dispatched(2); queue.acknowledge(2)
        assertEquals("move3", queue.take()); queue.dispatched(3); queue.acknowledge(3)
        assertEquals("release", queue.take())
    }
    @Test fun overflowAndResetCannotRestoreAnOldHeldQueue() {
        val queue = ReceiptBarrier<String>(2)
        queue.offer("down"); queue.dispatched(1)
        queue.offer("up"); assertFalse(queue.offer("overflow"))
        queue.clear()
        assertFalse(queue.acknowledge(1)); assertNull(queue.take()); assertEquals(0, queue.size)
    }
    @Test fun heartbeatPriorityIsBoundedAndDoesNotRepeatQueuedHeartbeats() {
        val queue = ReceiptBarrier<String>()
        queue.offer("up"); queue.offer("old-heartbeat", heartbeat = true); queue.offer("new-heartbeat", heartbeat = true)
        assertEquals(2, queue.size); assertEquals("new-heartbeat", queue.take())
    }
    @Test fun timeoutThenFreshSessionCannotDispatchOrAcknowledgeOldHeldInput() {
        val reducer = approvedSession(); reducer.setMock(true); reducer.recordMock(1)
        val queue = ReceiptBarrier<String>()
        queue.offer("old-down"); assertEquals("old-down", queue.take()); queue.dispatched(5)
        queue.offer("old-up")
        assertTrue(reducer.tick(3201)); queue.clear()
        reducer.requestStarted("fresh-session", setOf("POINTER"))
        assertFalse(queue.acknowledge(5)); assertNull(queue.take())
        assertNull(reducer.state.grant); assertFalse(reducer.state.mockEnabled)
        queue.offer("fresh-heartbeat", heartbeat = true)
        assertEquals("fresh-heartbeat", queue.take())
    }
    @Test fun unrelatedHeartbeatsCannotPreventMissingReceiptTimeout() {
        val queue = ReceiptBarrier<String>()
        queue.offer("held-down"); queue.take(); queue.dispatched(7, 100)
        assertFalse(queue.timedOut(3100)); assertTrue(queue.timedOut(3101))
        assertFalse(queue.acknowledge(8)); assertTrue(queue.timedOut(3200))
        queue.clear(); assertFalse(queue.timedOut(10000))
    }
}
