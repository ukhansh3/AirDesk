package com.pleos.airdesk

import org.junit.Assert.*
import org.junit.Test

class SecureSessionTest {
    @Test fun grantNeverUnlocksNativeAndMockRequiresExplicitOptIn() {
        val reducer = approvedSession()
        assertEquals(VehicleSafety.UNKNOWN, reducer.state.safety)
        assertEquals(SecurePhase.LOCKED, reducer.state.phase)
        assertFalse(reducer.state.nativeAllowed)
        assertFalse(reducer.state.mockAllowed("POINTER", 200))
        reducer.setMock(true)
        assertTrue(reducer.state.mockAllowed("POINTER", 200))
        reducer.safety(VehicleSafety.PARKED_CONFIRMED)
        assertFalse(reducer.state.nativeAllowed)
    }
    @Test fun foregroundLockIsImmediateAndResumeDoesNotRestoreGrant() {
        val reducer = approvedSession()
        reducer.setMock(true); reducer.recordMock(2)
        reducer.foreground(false)
        assertNull(reducer.state.grant)
        assertEquals(0, reducer.state.heldCount)
        assertFalse(reducer.state.mockEnabled)
        reducer.foreground(true)
        assertFalse(reducer.state.mockAllowed("POINTER", 201))
    }
    @Test fun unsafeTransitionRevokesAndReturningParkedDoesNotResume() {
        val reducer = approvedSession()
        reducer.setMock(true)
        reducer.safety(VehicleSafety.DRIVING)
        assertNull(reducer.state.grant)
        reducer.safety(VehicleSafety.PARKED_CONFIRMED)
        assertFalse(reducer.state.mockEnabled)
        assertFalse(reducer.state.nativeAllowed)
    }
    @Test fun heartbeatTimeoutAndExpiryLock() {
        val reducer = approvedSession()
        reducer.setMock(true)
        assertTrue(reducer.tick(3201))
        assertNull(reducer.state.grant)
        val expired = approvedSession()
        assertTrue(expired.tick(60200))
        assertNull(expired.state.grant)
    }
    @Test fun duplicateStaleCrossPeerAndOldSessionAreRejectedWithoutSideEffects() {
        val reducer = approvedSession()
        val heartbeat = wire("session.heartbeat", seq = 2, payload = """{"ackSequence":1}""")
        assertFalse(reducer.consume(heartbeat, "B".repeat(64), 200, 100000))
        assertFalse(reducer.consume(heartbeat.replace("session-1", "old-session"), PEER, 200, 100000))
        assertFalse(reducer.consume(heartbeat, PEER, 1000, 100800))
        assertTrue(reducer.consume(heartbeat, PEER, 300, 100100))
        assertFalse(reducer.consume(heartbeat, PEER, 301, 100101))
        assertEquals(300L, reducer.state.lastInboundMs)
    }
    @Test fun reconnectDropsGrantAndMockEvenForSameIdentity() {
        val reducer = approvedSession()
        reducer.setMock(true)
        reducer.disconnected("network")
        reducer.bind(PEER)
        assertNull(reducer.state.grant)
        assertFalse(reducer.state.mockEnabled)
        assertFalse(reducer.state.nativeAllowed)
    }
    @Test fun grantWithoutHostApprovalIsRejected() {
        val reducer = SecureSessionReducer(phase2Schema())
        reducer.bind(PEER); reducer.foreground(true); reducer.pairingStarted("pair-1")
        reducer.consume(wire("pairing.completed", "pair-1", payload = """{"pairingId":"offer-1","connectionId":"connection-1"}"""), PEER, 200, 100000)
        reducer.requestStarted("session-1", setOf("POINTER"))
        assertFalse(reducer.consume(wire("session.granted", payload = """{"grantId":"grant-example-0001","capabilities":["POINTER"],"expiresAtMs":160000}"""), PEER, 200, 100000))
    }
    @Test fun protocolFailureLocksAndDoesNotRetryHeldInput() {
        val reducer = approvedSession(); reducer.setMock(true); reducer.recordMock(1)
        assertTrue(reducer.consume(wire("protocol.error", seq = 2, payload = """{"code":"REPLAY","message":"Input rejected","retryable":false,"correlationId":"test-1"}"""), PEER, 300, 100100))
        assertNull(reducer.state.grant)
        assertFalse(reducer.state.mockEnabled)
        assertEquals(0, reducer.state.heldCount)
    }
    @Test fun pairingAndSessionMessagesCannotCrossTheirEnvelopeSessions() {
        val reducer = approvedSession(); val before = reducer.state
        assertFalse(reducer.consume(wire("pairing.completed", seq = 2, payload = """{"pairingId":"other","connectionId":"other"}"""), PEER, 300, 100100))
        assertFalse(reducer.consume(wire("session.ended", "pair-1", seq = 2, payload = """{"reason":"USER_REQUEST"}"""), PEER, 300, 100100))
        assertEquals(before, reducer.state)
    }
}
